package io.kscriptx.pack

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import java.nio.file.Path as JPath

/**
 * Run ProGuard on a packaged fat jar with a curated, Kotlin-safe config.
 *
 * Intentionally avoids aggressive options that commonly break Kotlin metadata,
 * coroutines, ServiceLoader, and reflective entry points:
 * no `-overloadaggressively`, no `-repackageclasses`, no class merging,
 * limited optimization filter, keep attributes / Kotlin metadata / services.
 */
object ProguardOptimizer {
    /**
     * Shrink/optimize [inputJar] in place (atomic replace via temp out jar).
     * Fails hard if ProGuard is missing or exits non-zero.
     */
    fun optimizeJar(
        inputJar: JPath,
        entryPoint: String,
        explicitJar: String? = null,
        explicitHome: String? = null,
        workDir: JPath = inputJar.parent ?: Path(System.getProperty("java.io.tmpdir")),
    ): JPath {
        val resolved = ProguardHome.resolve(explicitJar = explicitJar, explicitHome = explicitHome)
        val outJar = workDir / "${inputJar.fileName}.pg.jar"
        val configFile = workDir / "${inputJar.fileName}.proguard.pro"
        workDir.createDirectories()
        if (outJar.exists()) outJar.deleteExisting()

        configFile.writeText(
            buildConfig(
                inputJar = inputJar,
                outputJar = outJar,
                entryPoint = entryPoint,
            )
        )

        val javaBin = javaExecutable()
        val cmd = listOf(
            javaBin,
            "-jar", resolved.jar.absolutePathString(),
            "@${configFile.absolutePathString()}",
        )
        println("==> proguard (${resolved.jar})")
        val pb = ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            error("proguard failed (exit $code):\n$output")
        }
        require(outJar.exists()) {
            "proguard reported success but output jar missing at $outJar\n$output"
        }
        Files.move(outJar, inputJar, StandardCopyOption.REPLACE_EXISTING)
        try {
            configFile.deleteExisting()
        } catch (_: Exception) {
        }
        return inputJar
    }

    /**
     * Curated ProGuard rules for kscriptx fat jars.
     * Kept [internal] for unit tests.
     */
    internal fun buildConfig(
        inputJar: JPath,
        outputJar: JPath,
        entryPoint: String,
        javaHome: JPath = Path(System.getProperty("java.home")),
    ): String {
        val mainClass = entryPoint.replace('/', '.')
        val lines = mutableListOf<String>()
        lines += "-injars ${quote(inputJar.absolutePathString())}"
        lines += "-outjars ${quote(outputJar.absolutePathString())}"
        lines += libraryJarDirectives(javaHome)
        lines += ""
        // Safe optimization subset — exclude arithmetic/cast simplification,
        // field opts, and class merging (break Kotlin / coroutines / reflection).
        lines += "-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*"
        lines += "-optimizationpasses 2"
        lines += "-allowaccessmodification"
        // Explicitly NOT: -overloadaggressively, -repackageclasses, -flattenpackagehierarchy
        lines += ""
        lines += "-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*," +
            "RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations," +
            "RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations," +
            "AnnotationDefault,SourceFile,LineNumberTable,Exceptions"
        lines += "-keepdirectories META-INF/services/**"
        lines += "-adaptresourcefilenames **.properties,**.xml,**.json"
        lines += "-adaptresourcefilecontents **.properties,META-INF/services/**"
        lines += ""
        // Kotlin metadata + common reflective surfaces
        lines += "-keep class kotlin.Metadata { *; }"
        lines += "-keep class kotlin.reflect.** { *; }"
        lines += "-keepclassmembers class **\$WhenMappings {"
        lines += "    <fields>;"
        lines += "}"
        lines += "-keepclassmembers class ** {"
        lines += "    @kotlin.jvm.JvmStatic *;"
        lines += "    @kotlin.jvm.JvmField *;"
        lines += "    @kotlin.jvm.JvmOverloads *;"
        lines += "}"
        // Coroutines / continuation machinery
        lines += "-keepnames class kotlinx.coroutines.** { *; }"
        lines += "-keepclassmembers class kotlinx.coroutines.** {"
        lines += "    volatile <fields>;"
        lines += "}"
        lines += "-keepclassmembernames class kotlinx.** {"
        lines += "    volatile <fields>;"
        lines += "}"
        lines += ""
        // Kotlin companions / serializers often reflective
        lines += "-keepclassmembers,allowshrinking,allowobfuscation interface * {"
        lines += "    public static ** Companion;"
        lines += "}"
        lines += "-keepclasseswithmembers,includedescriptorclasses class * {"
        lines += "    public static ** Companion;"
        lines += "}"
        lines += ""
        // Main entry (kscriptx / @file:EntryPoint)
        lines += "-keep public class $mainClass {"
        lines += "    public static void main(java.lang.String[]);"
        lines += "}"
        // Kotlin file facades often expose main via the Kt class
        lines += "-keepclasseswithmembers public class * {"
        lines += "    public static void main(java.lang.String[]);"
        lines += "}"
        lines += ""
        lines += "-dontnote **"
        lines += "-dontwarn **"
        return lines.joinToString("\n") + "\n"
    }

    internal fun libraryJarDirectives(javaHome: JPath): List<String> {
        val jmods = javaHome / "jmods"
        if (jmods.exists() && jmods.isDirectory()) {
            val mods = Files.list(jmods).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jmod") }
                    .sorted()
                    .map { it.absolutePathString() }
                    .toList()
            }
            if (mods.isNotEmpty()) {
                return mods.map { "-libraryjars ${quote(it)}(!**.jar;!module-info.class)" }
            }
        }
        val rt = javaHome / "lib" / "rt.jar"
        if (rt.exists()) {
            return listOf("-libraryjars ${quote(rt.absolutePathString())}")
        }
        // Last resort: whole java.home (ProGuard may still resolve)
        return listOf("-libraryjars ${quote(javaHome.absolutePathString())}")
    }

    private fun quote(path: String): String =
        if (path.any { it.isWhitespace() }) "\"$path\"" else path

    private fun javaExecutable(): String {
        val home = Path(System.getProperty("java.home"))
        val unix = home / "bin" / "java"
        val win = home / "bin" / "java.exe"
        return when {
            win.exists() -> win.absolutePathString()
            unix.exists() -> unix.absolutePathString()
            else -> "java"
        }
    }
}
