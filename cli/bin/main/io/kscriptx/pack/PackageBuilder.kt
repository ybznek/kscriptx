package io.kscriptx.pack

import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.*

object PackageBuilder {
    fun packageBinary(script: ResolvedScript, compiled: CompiledScript) {
        val outName = script.displayName.substringBeforeLast('.')
        val outDir = script.rootFile?.parent ?: Path(System.getProperty("user.dir"))
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val jarFile = outDir / "$outName.jar"
        val binFile = outDir / if (isWin) "$outName.cmd" else outName

        createFatJar(compiled, jarFile)

        val javaOpts = compiled.kotlinOptions
            .mapNotNull { opt ->
                when {
                    opt.startsWith("-J") -> opt.removePrefix("-J")
                    opt.startsWith("-D") -> opt
                    else -> null
                }
            }.joinToString(" ")

        if (isWin) {
            binFile.writeText(
                """
                |@echo off
                |set DIR=%~dp0
                |java $javaOpts -jar "%DIR%$outName.jar" %*
                """.trimMargin() + "\n"
            )
        } else {
            binFile.writeText(
                """
                |#!/usr/bin/env bash
                |DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
                |exec java $javaOpts -jar "${'$'}DIR/$outName.jar" "${'$'}@"
                """.trimMargin() + "\n"
            )
            binFile.toFile().setExecutable(true)
        }
        println("Packaged: $binFile")
        println("Jar:      $jarFile")
    }

    private fun createFatJar(compiled: CompiledScript, jarFile: Path) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = compiled.entryPoint

        JarOutputStream(Files.newOutputStream(jarFile), manifest).use { jos ->
            // classes
            addDirectory(jos, compiled.classesDir, "")
            // dependency jars
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
