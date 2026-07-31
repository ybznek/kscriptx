package io.kscriptx.pack

import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Paths and dual-mode (jar + native) launchers for packaged scripts.
 *
 * Layout when combining `--package` and `--native`:
 * - `<stem>.jar` — fat jar (JVM)
 * - `<stem>.native` — GraalVM native executable
 * - `<stem>` — smart wrapper (prefer native, else `java -jar`)
 *
 * `--native` alone still writes the native binary as `<stem>` (backward compatible)
 * and keeps `<stem>.jar`.
 */
object NativeArtifacts {
    fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    fun stemName(scriptDisplayName: String): String =
        scriptDisplayName.substringBeforeLast('.')

    /** Native binary when dual with a JVM launcher / smart wrapper. */
    fun dualNativePath(outDir: Path, stem: String): Path =
        if (isWindows()) outDir / "$stem.native.exe" else outDir / "$stem.native"

    /** Primary launcher / native-only binary path (`--package` or bare `--native`). */
    fun primaryBinPath(outDir: Path, stem: String): Path =
        if (isWindows()) outDir / "$stem.cmd" else outDir / stem

    fun sharedLibName(stem: String): String = when {
        isWindows() -> "$stem.dll"
        System.getProperty("os.name").lowercase().contains("mac") -> "lib$stem.dylib"
        else -> "lib$stem.so"
    }

    fun sharedLibPath(outDir: Path, stem: String): Path = outDir / sharedLibName(stem)

    /**
     * Smart wrapper: run `<stem>.native` when present and executable, else `java -jar`.
     */
    fun writeSmartLauncher(
        launcher: Path,
        stem: String,
        javaOpts: String,
    ) {
        if (isWindows()) {
            launcher.writeText(
                """
                |@echo off
                |set DIR=%~dp0
                |if exist "%DIR%$stem.native.exe" (
                |  "%DIR%$stem.native.exe" %*
                |  exit /b %ERRORLEVEL%
                |)
                |if exist "%DIR%$stem.native" (
                |  "%DIR%$stem.native" %*
                |  exit /b %ERRORLEVEL%
                |)
                |java $javaOpts -jar "%DIR%$stem.jar" %*
                """.trimMargin() + "\n",
            )
        } else {
            launcher.writeText(
                """
                |#!/usr/bin/env bash
                |DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
                |NATIVE="${'$'}DIR/$stem.native"
                |if [[ -x "${'$'}NATIVE" ]]; then
                |  exec "${'$'}NATIVE" "${'$'}@"
                |fi
                |exec java $javaOpts -jar "${'$'}DIR/$stem.jar" "${'$'}@"
                """.trimMargin() + "\n",
            )
            launcher.toFile().setExecutable(true)
        }
    }

    fun writeJvmOnlyLauncher(
        launcher: Path,
        stem: String,
        javaOpts: String,
    ) {
        if (isWindows()) {
            launcher.writeText(
                """
                |@echo off
                |set DIR=%~dp0
                |java $javaOpts -jar "%DIR%$stem.jar" %*
                """.trimMargin() + "\n",
            )
        } else {
            launcher.writeText(
                """
                |#!/usr/bin/env bash
                |DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
                |exec java $javaOpts -jar "${'$'}DIR/$stem.jar" "${'$'}@"
                """.trimMargin() + "\n",
            )
            launcher.toFile().setExecutable(true)
        }
    }

    fun javaOptsFrom(kotlinOptions: List<String>): String =
        kotlinOptions.mapNotNull { opt ->
            when {
                opt.startsWith("-J") -> opt.removePrefix("-J")
                opt.startsWith("-D") -> opt
                else -> null
            }
        }.joinToString(" ")
}
