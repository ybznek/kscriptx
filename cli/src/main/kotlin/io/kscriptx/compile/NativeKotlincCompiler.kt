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
    @Volatile private var cachedRoot: Path? = null
    @Volatile private var cachedResolved = false
    @Volatile private var prewarmStarted = false

    val nativeRoot: Path
        get() = resolveRoot()
            ?: error(
                "Native kotlinc is required. Looked in:\n" +
                    candidateRoots().joinToString("\n") { "  - $it" } +
                    "\nInstall via the Debian package, a release tarball, or " +
                    "./scripts/build-native-kotlinc.sh (or set KSCRIPTX_NATIVE_KOTLINC)."
            )

    fun isAvailable(): Boolean = resolveRoot() != null

    fun resolveRoot(): Path? {
        if (cachedResolved) return cachedRoot
        synchronized(this) {
            if (cachedResolved) return cachedRoot
            cachedRoot = candidateRoots().firstOrNull { isValidInstall(it) }
            cachedResolved = true
            return cachedRoot
        }
    }

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

    /**
     * Best-effort: pull native binary + jars into the OS page cache from the daemon
     * so the first real compile after idle is less cold.
     */
    fun prewarmAsync() {
        if (prewarmStarted) return
        prewarmStarted = true
        val root = resolveRoot() ?: return
        Thread({
            try {
                val files = listOf(
                    root / "kotlinc-native",
                    root / "java.base.jar",
                    root / "kotlin-compiler-embeddable.jar",
                    root / "kotlin-home" / "lib" / "kotlin-stdlib.jar",
                )
                val buf = ByteArray(1024 * 1024)
                for (f in files) {
                    if (!f.isRegularFile()) continue
                    Files.newInputStream(f).use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "kscriptx-native-prewarm").apply { isDaemon = true; start() }
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
        sourceWorkDir: Path? = null,
    ) {
        val root = nativeRoot
        val bin = root / "kotlinc-native"
        val kHome = root / "kotlin-home"
        val jBase = root / "java.base.jar"
        val cJar = root / "kotlin-compiler-embeddable.jar"

        if (outputClassesDir.toFile().exists()) outputClassesDir.toFile().deleteRecursively()
        outputClassesDir.createDirectories()

        val work = sourceWorkDir ?: Files.createTempDirectory("kscriptx-native-")
        val deleteWork = sourceWorkDir == null
        try {
            val srcDir = work.resolve("src")
            if (srcDir.toFile().exists()) srcDir.toFile().deleteRecursively()
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

            val opts = LinkedHashSet<String>()
            // kscriptx wraps .kts → .kt; the scripting plugin is unused overhead.
            opts.add("-Xdisable-default-scripting-plugin")
            opts.addAll(compilerOptions)

            val cmd = buildList {
                add(bin.absolutePathString())
                add("-kotlin-home"); add(kHome.absolutePathString())
                add("-no-jdk")
                add("-classpath"); add(fullCp)
                add("-d"); add(outputClassesDir.absolutePathString())
                add("-jvm-target"); add("17")
                add("-no-stdlib")
                add("-no-reflect")
                addAll(opts)
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
            if (deleteWork) {
                work.toFile().deleteRecursively()
            }
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
        out.createDirectories()
        val classes = out / "classes"
        // Keep sources next to classes (no extra temp dir create/delete on the hot miss path).
        compile(sources, classpath, classes, compilerOptions, sourceWorkDir = out)
        (out / "classpath").writeText(classpath)
        (out / "entry").writeText(entry)
        (out / "ok").writeText("1")
    }
}
