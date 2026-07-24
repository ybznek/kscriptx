package io.kscriptx.compile

import io.kscriptx.KPaths
import io.kscriptx.model.SourceUnit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * Fast source-only compiles via GraalVM native kotlinc.
 *
 * Search order:
 * 1. `KSCRIPTX_NATIVE_KOTLINC`
 * 2. `$KSCRIPTX_DIRECTORY/native-kotlinc` (default `~/.kscriptx/native-kotlinc`)
 * 3. Install-relative `native-kotlinc/` next to `kscriptx.jar` (portable / Debian)
 * 4. `/usr/lib/kscriptx/native-kotlinc` (Debian package)
 */
object NativeKotlincCompiler {
    val nativeRoot: Path
        get() = resolveRoot()
            ?: error(
                "Native kotlinc is required. Looked in:\n" +
                    candidateRoots().joinToString("\n") { "  - $it" } +
                    "\nInstall via the Debian package, a release tarball, or " +
                    "./scripts/build-native-kotlinc.sh (or set KSCRIPTX_NATIVE_KOTLINC)."
            )

    private val binary get() = nativeRoot / "kotlinc-native"
    private val kotlinHome get() = nativeRoot / "kotlin-home"
    private val javaBaseJar get() = nativeRoot / "java.base.jar"
    private val compilerJar get() = nativeRoot / "kotlin-compiler-embeddable.jar"

    fun isAvailable(): Boolean = resolveRoot() != null

    fun resolveRoot(): Path? = candidateRoots().firstOrNull { isValidInstall(it) }

    fun candidateRoots(): List<Path> {
        val out = linkedSetOf<Path>()
        System.getenv("KSCRIPTX_NATIVE_KOTLINC")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.add(Path(it))
        }
        out.add(KPaths.home / "native-kotlinc")
        installDirBesideJar()?.let { out.add(it / "native-kotlinc") }
        out.add(Path("/usr/lib/kscriptx/native-kotlinc"))
        return out.toList()
    }

    fun isValidInstall(root: Path): Boolean {
        val bin = root / "kotlinc-native"
        return bin.exists() && bin.isRegularFile() && bin.isExecutable() &&
            (root / "kotlin-home").exists() &&
            (root / "java.base.jar").exists() &&
            (root / "kotlin-compiler-embeddable.jar").exists()
    }

    /** Directory containing kscriptx.jar when running from an install tree. */
    private fun installDirBesideJar(): Path? {
        return try {
            val loc = NativeKotlincCompiler::class.java.protectionDomain?.codeSource?.location
                ?: return null
            val jar = java.nio.file.Paths.get(loc.toURI())
            jar.parent
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compile [sources] into [outputClassesDir] using the native binary.
     * [classpath] is the resolved dependency classpath (stdlib + deps).
     */
    fun compile(
        sources: List<SourceUnit>,
        classpath: String,
        outputClassesDir: Path,
        compilerOptions: List<String> = emptyList(),
    ) {
        val root = nativeRoot
        val bin = root / "kotlinc-native"
        val kHome = root / "kotlin-home"
        val jBase = root / "java.base.jar"
        val cJar = root / "kotlin-compiler-embeddable.jar"

        if (outputClassesDir.toFile().exists()) outputClassesDir.toFile().deleteRecursively()
        outputClassesDir.createDirectories()

        val work = Files.createTempDirectory("kscriptx-native-")
        try {
            val srcDir = work.resolve("src")
            srcDir.createDirectories()
            val sourcePaths = sources.map { unit ->
                val target = srcDir.resolve(unit.fileName)
                target.parent?.createDirectories()
                target.writeText(unit.content)
                target.absolutePathString()
            }

            val sep = java.io.File.pathSeparator
            val fullCp = buildString {
                append(jBase.absolutePathString())
                if (classpath.isNotBlank()) {
                    append(sep)
                    append(classpath)
                }
            }

            val cmd = buildList {
                add(bin.absolutePathString())
                add("-kotlin-home"); add(kHome.absolutePathString())
                add("-no-jdk")
                add("-classpath"); add(fullCp)
                add("-d"); add(outputClassesDir.absolutePathString())
                add("-jvm-target"); add("17")
                add("-no-stdlib")
                add("-no-reflect")
                addAll(compilerOptions)
                addAll(sourcePaths)
            }

            val pb = ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(true)
            // Ensure PathUtil substitution finds the sidecar jar next to the binary.
            // Set both names: new builds read KSCRIPTX_*; older native images still check KSCRIPT3_*.
            val jarPath = cJar.absolutePathString()
            pb.environment()["KSCRIPTX_KOTLIN_COMPILER_JAR"] = jarPath
            pb.environment()["KSCRIPT3_KOTLIN_COMPILER_JAR"] = jarPath
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                error("Native kotlinc failed (exit $code):\n$output")
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Compile into the content-addressed cache layout used by [CacheStore].
     */
    fun compileToCache(
        contentHash: String,
        entry: String,
        classpath: String,
        compilerOptions: List<String>,
        sources: List<SourceUnit>,
    ) {
        val out = KPaths.cache / contentHash
        val classes = out / "classes"
        compile(sources, classpath, classes, compilerOptions)
        out.createDirectories()
        (out / "classpath").writeText(classpath)
        (out / "entry").writeText(entry)
        (out / "ok").writeText("1")
    }
}
