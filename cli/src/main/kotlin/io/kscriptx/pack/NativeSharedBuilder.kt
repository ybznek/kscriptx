package io.kscriptx.pack

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * Build GraalVM `--shared` libraries for scripts, plus optional C runner and JVM helper jar.
 */
object NativeSharedBuilder {
    data class Result(
        val sharedLib: Path,
        val header: Path?,
        val runner: Path?,
        val loaderJar: Path?,
    )

    fun build(
        jarFile: Path,
        outDir: Path,
        stem: String,
        entryPoint: String,
        graalvmHome: String? = null,
        buildRunner: Boolean = false,
        configDir: String? = null,
        extraArgs: List<String> = emptyList(),
    ): Result {
        outDir.createDirectories()
        val work = Files.createTempDirectory("kscriptx-native-shared-")
        try {
            val bridgeJar = work / "bridge-classes"
            bridgeJar.createDirectories()
            compileBridge(entryPoint, bridgeJar, graalvmHome)

            val augmentedJar = work / "$stem-with-bridge.jar"
            mergeJarWithBridge(jarFile, bridgeJar, augmentedJar)

            val libPath = NativeArtifacts.sharedLibPath(outDir, stem)
            if (libPath.exists()) Files.delete(libPath)

            // native-image --shared -H:Name=lib<stem> (Name without lib/ext on Unix)
            val imageName = if (NativeArtifacts.isWindows()) stem else "lib$stem"
            val outputHint = outDir / imageName

            NativeImageRunner.run(
                args = listOf(
                    "--shared",
                    "-jar", augmentedJar.absolutePathString(),
                    "-H:Name=$imageName",
                    "--no-fallback",
                    "-o", outputHint.absolutePathString(),
                ),
                request = NativeImageRunner.Request(
                    graalvmHome = graalvmHome,
                    configDir = configDir,
                    extraArgs = extraArgs,
                    workingDir = outDir,
                ),
            )

            val produced = findSharedLib(outDir, stem, imageName)
                ?: error("native-image --shared succeeded but library not found under $outDir")
            if (produced != libPath && !libPath.exists()) {
                Files.move(produced, libPath)
            }
            val finalLib = if (libPath.exists()) libPath else produced

            val header = outDir.listDirectoryEntriesOrEmpty()
                .firstOrNull { it.name == "lib$stem.h" || it.name == "$stem.h" }
                ?: outDir.listDirectoryEntriesOrEmpty()
                    .firstOrNull { it.name.endsWith(".h") && it.nameWithoutExtension.contains(stem) }
                ?: outDir.listDirectoryEntriesOrEmpty().firstOrNull {
                    it.extension == "h" && !it.name.startsWith("graal_")
                }

            val loaderJar = writeLoaderJar(outDir, stem, graalvmHome)

            val runner = if (buildRunner) {
                buildCRunner(outDir, stem, finalLib, header, graalvmHome)
            } else {
                null
            }

            // JVM helper launcher script beside the lib
            writeJvmSharedHelper(outDir, stem, finalLib, runner, loaderJar)

            println("Shared library: $finalLib")
            header?.let { println("Header:         $it") }
            loaderJar.let { println("Loader jar:     $it") }
            runner?.let { println("Native runner:  $it") }

            return Result(finalLib, header, runner, loaderJar)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun Path.listDirectoryEntriesOrEmpty(): List<Path> =
        try {
            Files.list(this).use { it.toList() }
        } catch (_: Exception) {
            emptyList()
        }

    private fun findSharedLib(outDir: Path, stem: String, imageName: String): Path? {
        val candidates = listOf(
            NativeArtifacts.sharedLibPath(outDir, stem),
            outDir / imageName,
            outDir / "$imageName.so",
            outDir / "$imageName.dylib",
            outDir / "$imageName.dll",
            outDir / "lib$stem.so",
            outDir / "lib$stem.dylib",
            outDir / "$stem.dll",
        )
        return candidates.firstOrNull { it.exists() }
            ?: outDir.listDirectoryEntriesOrEmpty().firstOrNull {
                val n = it.name
                n.endsWith(".so") || n.endsWith(".dylib") || n.endsWith(".dll")
            }
    }

    private fun compileBridge(entryPoint: String, outClasses: Path, graalvmHome: String?) {
        val srcDir = outClasses.parent!! / "bridge-src"
        val pkgDir = srcDir / NativeBridgeGenerator.BRIDGE_PACKAGE.replace('.', '/')
        pkgDir.createDirectories()
        val src = pkgDir / "${NativeBridgeGenerator.BRIDGE_CLASS}.java"
        src.writeText(NativeBridgeGenerator.bridgeJavaSource(entryPoint))

        val cp = NativeImageRunner.svmCompileClasspath(graalvmHome)
            .joinToString(java.io.File.pathSeparator) { it.absolutePathString() }
        val javac = NativeImageRunner.findJavac(graalvmHome)
        val cmd = listOf(
            javac.absolutePathString(),
            "-cp", cp,
            "-d", outClasses.absolutePathString(),
            src.absolutePathString(),
        )
        println("==> javac bridge")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            error("Failed to compile native bridge (exit $code):\n$output")
        }
    }

    private fun mergeJarWithBridge(inputJar: Path, bridgeClasses: Path, outputJar: Path) {
        val manifest = Manifest()
        JarFile(inputJar.toFile()).use { jf ->
            jf.manifest?.let { m ->
                m.mainAttributes.forEach { k, v ->
                    manifest.mainAttributes[k] = v
                }
            }
        }
        if (manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] == null) {
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }

        JarOutputStream(Files.newOutputStream(outputJar), manifest).use { jos ->
            JarFile(inputJar.toFile()).use { jf ->
                jf.entries().asSequence().forEach { entry ->
                    if (entry.name.equals("META-INF/MANIFEST.MF", true)) return@forEach
                    jos.putNextEntry(JarEntry(entry.name))
                    jf.getInputStream(entry).use { it.copyTo(jos) }
                    jos.closeEntry()
                }
            }
            Files.walk(bridgeClasses).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val rel = bridgeClasses.relativize(file).toString().replace('\\', '/')
                    jos.putNextEntry(JarEntry(rel))
                    Files.copy(file, jos)
                    jos.closeEntry()
                }
            }
        }
    }

    private fun writeLoaderJar(outDir: Path, stem: String, graalvmHome: String?): Path {
        val work = Files.createTempDirectory("kscriptx-loader-")
        return try {
            val classes = work / "classes"
            classes.createDirectories()
            val pkg = classes / NativeBridgeGenerator.LOADER_PACKAGE.replace('.', '/')
            pkg.createDirectories()
            val src = work / "${NativeBridgeGenerator.LOADER_CLASS}.java"
            // System.loadLibrary name: stem (libstem.so → stem)
            src.writeText(NativeBridgeGenerator.loaderJavaSource(stem))
            val pkgSrc = pkg / "${NativeBridgeGenerator.LOADER_CLASS}.java"
            Files.copy(src, pkgSrc, StandardCopyOption.REPLACE_EXISTING)

            val javac = NativeImageRunner.findJavac(graalvmHome)
            val cmd = listOf(
                javac.absolutePathString(),
                "-d", classes.absolutePathString(),
                pkgSrc.absolutePathString(),
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) {
                error("Failed to compile SharedLibLoader:\n$output")
            }

            val loaderJar = outDir / "$stem-shared-loader.jar"
            val manifest = Manifest()
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = NativeBridgeGenerator.LOADER_FQCN
            JarOutputStream(Files.newOutputStream(loaderJar), manifest).use { jos ->
                Files.walk(classes).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val rel = classes.relativize(file).toString().replace('\\', '/')
                        jos.putNextEntry(JarEntry(rel))
                        Files.copy(file, jos)
                        jos.closeEntry()
                    }
                }
            }
            loaderJar
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun buildCRunner(
        outDir: Path,
        stem: String,
        sharedLib: Path,
        header: Path?,
        graalvmHome: String?,
    ): Path {
        val headerName = header?.name ?: "$stem.h"
        if (header == null) {
            // Minimal stub header if Graal did not emit one next to the lib.
            val stub = outDir / headerName
            stub.writeText(
                """
                |#pragma once
                |typedef struct graal_isolatethread_t graal_isolatethread_t;
                |graal_isolatethread_t* kscriptx_create_isolate(void);
                |int kscriptx_tear_down_isolate(graal_isolatethread_t* thread);
                |int kscriptx_run(graal_isolatethread_t* thread, int argc, char** argv);
                """.trimMargin() + "\n",
            )
        }
        val cFile = outDir / "${stem}_runner.c"
        cFile.writeText(NativeBridgeGenerator.runnerCSource(stem, headerName))

        val runner = NativeArtifacts.primaryBinPath(outDir, "${stem}-runner")
            .let { if (NativeArtifacts.isWindows()) outDir / "$stem-runner.exe" else outDir / "$stem-runner" }

        val cc = findCc()
        val libDir = sharedLib.parent?.absolutePathString() ?: outDir.absolutePathString()
        val libName = sharedLib.nameWithoutExtension.removePrefix("lib")
        val cmd = buildList {
            add(cc)
            add("-O2")
            add("-I${outDir.absolutePathString()}")
            add("-o")
            add(runner.absolutePathString())
            add(cFile.absolutePathString())
            add("-L$libDir")
            add("-l$libName")
            if (!NativeArtifacts.isWindows()) {
                add("-Wl,-rpath,\$ORIGIN")
            }
        }
        println("==> cc native runner")
        println("    ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).directory(outDir.toFile()).redirectErrorStream(true)
        // Ensure Graal headers / libs visible if needed
        graalvmHome?.let { pb.environment()["GRAALVM_HOME"] = it }
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            error(
                "Failed to link native shared runner (exit $code).\n" +
                    "Need a C toolchain (cc/gcc/clang) and the shared lib.\n$output",
            )
        }
        runner.toFile().setExecutable(true)
        return runner
    }

    private fun findCc(): String {
        val names = if (NativeArtifacts.isWindows()) listOf("clang.exe", "gcc.exe", "cc.exe")
        else listOf("cc", "clang", "gcc")
        for (n in names) {
            val pb = ProcessBuilder(if (NativeArtifacts.isWindows()) listOf("where", n) else listOf("which", n))
                .redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotBlank()) {
                return out.lineSequence().first().trim()
            }
        }
        error("No C compiler (cc/gcc/clang) on PATH — required for --native-runner")
    }

    private fun writeJvmSharedHelper(
        outDir: Path,
        stem: String,
        sharedLib: Path,
        runner: Path?,
        loaderJar: Path?,
    ) {
        val helper = if (NativeArtifacts.isWindows()) outDir / "$stem-shared.cmd" else outDir / "$stem-shared"
        if (NativeArtifacts.isWindows()) {
            helper.writeText(
                """
                |@echo off
                |set DIR=%~dp0
                |if exist "%DIR%$stem-runner.exe" (
                |  "%DIR%$stem-runner.exe" %*
                |  exit /b %ERRORLEVEL%
                |)
                |echo Prefer building with --native-runner, or run the C runner next to the shared lib.
                |echo Shared lib: %DIR%${sharedLib.name}
                |if exist "%DIR%$stem-shared-loader.jar" (
                |  java -Djava.library.path="%DIR%" -Dkscriptx.shared.lib="%DIR%${sharedLib.name}" -jar "%DIR%$stem-shared-loader.jar" %*
                |  exit /b %ERRORLEVEL%
                |)
                |exit /b 1
                """.trimMargin() + "\n",
            )
        } else {
            helper.writeText(
                """
                |#!/usr/bin/env bash
                |DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
                |if [[ -x "${'$'}DIR/$stem-runner" ]]; then
                |  exec "${'$'}DIR/$stem-runner" "${'$'}@"
                |fi
                |echo "Prefer: kscriptx --native-shared --native-runner <script>" >&2
                |echo "Shared lib: ${'$'}DIR/${sharedLib.name}" >&2
                |if [[ -f "${'$'}DIR/$stem-shared-loader.jar" ]]; then
                |  exec java -Djava.library.path="${'$'}DIR" \
                |    -Dkscriptx.shared.lib="${'$'}DIR/${sharedLib.name}" \
                |    -jar "${'$'}DIR/$stem-shared-loader.jar" "${'$'}@"
                |fi
                |exit 1
                """.trimMargin() + "\n",
            )
            helper.toFile().setExecutable(true)
        }
        println("Shared helper:  $helper")
    }
}
