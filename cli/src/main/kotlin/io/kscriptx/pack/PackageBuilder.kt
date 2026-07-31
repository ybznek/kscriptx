package io.kscriptx.pack

import io.kscriptx.compile.CompileBeside
import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.*

object PackageBuilder {
    data class Options(
        val nativeImage: Boolean = false,
        val nativeShared: Boolean = false,
        val nativeRunner: Boolean = false,
        val nativeConfigDir: String? = null,
        val nativeImageArgs: List<String> = emptyList(),
        val graalvmHome: String? = null,
        val proguard: Boolean = false,
        val proguardHome: String? = null,
        val proguardJar: String? = null,
        /** Write shell/cmd launcher (`--package`, or bare `--proguard`). */
        val writeLauncher: Boolean = true,
        /** Also copy the final jar into `<stem>.kscriptx/` when set. */
        val compileBeside: Boolean = false,
    )

    /**
     * Primary fat/standalone jar path: beside the script (package output dir),
     * or cwd when the script has no parent.
     */
    fun primaryJarPath(script: ResolvedScript): Path {
        val outName = NativeArtifacts.stemName(script.displayName)
        val outDir = script.rootFile?.parent ?: Path(System.getProperty("user.dir"))
        return outDir / "$outName.jar"
    }

    /** Jar copy under `--compile-beside` tree. */
    fun compileBesideJarPath(script: ResolvedScript): Path {
        val outName = NativeArtifacts.stemName(script.displayName)
        return CompileBeside.outputDir(script) / "$outName.jar"
    }

    fun packageBinary(
        script: ResolvedScript,
        compiled: CompiledScript,
        options: Options = Options(),
    ) {
        val outName = NativeArtifacts.stemName(script.displayName)
        val outDir = script.rootFile?.parent ?: Path(System.getProperty("user.dir"))
        val jarFile = primaryJarPath(script)
        val javaOpts = NativeArtifacts.javaOptsFrom(compiled.kotlinOptions)

        // Pipeline: compile (caller) → fat jar → optional ProGuard →
        // optional shared lib → optional native exe → optional launcher.
        createFatJar(compiled, jarFile)

        if (options.proguard) {
            ProguardOptimizer.optimizeJar(
                inputJar = jarFile,
                entryPoint = compiled.entryPoint,
                explicitJar = options.proguardJar,
                explicitHome = options.proguardHome,
                workDir = outDir,
            )
            println("ProGuard jar: $jarFile")
        }

        if (options.nativeShared) {
            NativeSharedBuilder.build(
                jarFile = jarFile,
                outDir = outDir,
                stem = outName,
                entryPoint = compiled.entryPoint,
                graalvmHome = options.graalvmHome,
                buildRunner = options.nativeRunner,
                configDir = options.nativeConfigDir,
                extraArgs = options.nativeImageArgs,
            )
        }

        if (options.nativeImage) {
            val dualWithLauncher = options.writeLauncher
            val nativeOut = if (dualWithLauncher) {
                NativeArtifacts.dualNativePath(outDir, outName)
            } else {
                NativeArtifacts.primaryBinPath(outDir, outName)
            }
            buildNativeImage(
                jarFile = jarFile,
                outputBinary = nativeOut,
                imageName = outName,
                graalvmHome = options.graalvmHome,
                configDir = options.nativeConfigDir,
                extraArgs = options.nativeImageArgs,
            )
            println("Native binary: $nativeOut")
            println("Jar:           $jarFile")

            if (dualWithLauncher) {
                val launcher = NativeArtifacts.primaryBinPath(outDir, outName)
                NativeArtifacts.writeSmartLauncher(launcher, outName, javaOpts)
                println("Smart launcher: $launcher (prefers .native, else java -jar)")
            }

            maybeCopyJarBeside(script, jarFile, options.compileBeside)
            return
        }

        if (options.writeLauncher) {
            val launcher = NativeArtifacts.primaryBinPath(outDir, outName)
            NativeArtifacts.writeJvmOnlyLauncher(launcher, outName, javaOpts)
            maybeCopyJarBeside(script, jarFile, options.compileBeside)
            println("Packaged: $launcher")
            println("Jar:      $jarFile")
        } else {
            maybeCopyJarBeside(script, jarFile, options.compileBeside)
            println("Standalone jar: $jarFile")
        }
    }

    private fun maybeCopyJarBeside(script: ResolvedScript, jarFile: Path, compileBeside: Boolean) {
        if (!compileBeside || script.rootFile == null) return
        val dest = compileBesideJarPath(script)
        dest.parent?.createDirectories()
        Files.copy(jarFile, dest, StandardCopyOption.REPLACE_EXISTING)
        println("Compile-beside jar: $dest")
    }

    /**
     * Run GraalVM `native-image` on the fat jar. Fails hard if GraalVM / native-image
     * is missing or the build exits non-zero.
     */
    fun buildNativeImage(
        jarFile: Path,
        outputBinary: Path,
        imageName: String,
        graalvmHome: String? = null,
        configDir: String? = null,
        extraArgs: List<String> = emptyList(),
    ) {
        if (outputBinary.exists()) {
            Files.delete(outputBinary)
        }
        val workDir = outputBinary.parent ?: jarFile.parent
        val output = NativeImageRunner.run(
            args = listOf(
                "-jar", jarFile.absolutePathString(),
                "-H:Name=$imageName",
                "--no-fallback",
                "-o", outputBinary.absolutePathString(),
            ),
            request = NativeImageRunner.Request(
                graalvmHome = graalvmHome,
                configDir = configDir,
                extraArgs = extraArgs,
                workingDir = workDir,
            ),
        )
        if (!outputBinary.exists()) {
            val cwdBin = workDir / imageName
            if (cwdBin.exists() && cwdBin != outputBinary) {
                Files.move(cwdBin, outputBinary)
            }
        }
        require(outputBinary.exists()) {
            "native-image reported success but binary missing at $outputBinary\n$output"
        }
        outputBinary.toFile().setExecutable(true)
    }

    internal fun createFatJar(compiled: CompiledScript, jarFile: Path) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = compiled.entryPoint

        JarOutputStream(Files.newOutputStream(jarFile), manifest).use { jos ->
            addDirectory(jos, compiled.classesDir, "")
            compiled.classpath.split(System.getProperty("path.separator"))
                .filter { it.isNotBlank() }
                .map { Path(it) }
                .filter { it.exists() }
                .forEach { dep ->
                    if (dep.isDirectory()) {
                        addDirectory(jos, dep, "")
                    } else if (dep.fileName.toString().endsWith(".jar")) {
                        java.util.zip.ZipFile(dep.toFile()).use { zip ->
                            zip.entries().asSequence().forEach { entry ->
                                if (entry.isDirectory) return@forEach
                                if (entry.name.equals("META-INF/MANIFEST.MF", true)) return@forEach
                                if (entry.name.startsWith("META-INF/") && entry.name.endsWith(".SF")) return@forEach
                                if (entry.name.startsWith("META-INF/") && entry.name.endsWith(".RSA")) return@forEach
                                if (entry.name.startsWith("META-INF/") && entry.name.endsWith(".DSA")) return@forEach
                                try {
                                    jos.putNextEntry(JarEntry(entry.name))
                                    zip.getInputStream(entry).use { it.copyTo(jos) }
                                    jos.closeEntry()
                                } catch (_: java.util.zip.ZipException) {
                                    // duplicate entry
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun addDirectory(jos: JarOutputStream, dir: Path, prefix: String) {
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val rel = prefix + dir.relativize(file).toString().replace('\\', '/')
                try {
                    jos.putNextEntry(JarEntry(rel))
                    Files.copy(file, jos)
                    jos.closeEntry()
                } catch (_: java.util.zip.ZipException) {
                }
            }
        }
    }
}
