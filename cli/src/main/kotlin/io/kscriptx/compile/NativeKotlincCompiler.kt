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
 * Fast source-only compiles via GraalVM native kotlinc when installed under
 * ~/.kscriptx/native-kotlinc/ (or KSCRIPTX_NATIVE_KOTLINC).
 */
object NativeKotlincCompiler {
    private val nativeRoot: Path
        get() {
            System.getenv("KSCRIPTX_NATIVE_KOTLINC")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return Path(it)
            }
            return KPaths.home / "native-kotlinc"
        }

    private val binary get() = nativeRoot / "kotlinc-native"
    private val kotlinHome get() = nativeRoot / "kotlin-home"
    private val javaBaseJar get() = nativeRoot / "java.base.jar"
    private val compilerJar get() = nativeRoot / "kotlin-compiler-embeddable.jar"

    fun isAvailable(): Boolean =
        binary.exists() && binary.isRegularFile() && binary.isExecutable() &&
            kotlinHome.exists() && javaBaseJar.exists() && compilerJar.exists()

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
        require(isAvailable()) { "Native kotlinc is not installed at $nativeRoot" }

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
                append(javaBaseJar.absolutePathString())
                if (classpath.isNotBlank()) {
                    append(sep)
                    append(classpath)
                }
            }

            val cmd = buildList {
                add(binary.absolutePathString())
                add("-kotlin-home"); add(kotlinHome.absolutePathString())
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
                .directory(nativeRoot.toFile())
                .redirectErrorStream(true)
            // Ensure PathUtil substitution finds the sidecar jar next to the binary.
            // Set both names: new builds read KSCRIPTX_*; older native images still check KSCRIPT3_*.
            val jarPath = compilerJar.absolutePathString()
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
