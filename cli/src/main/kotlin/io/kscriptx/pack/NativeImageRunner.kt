package io.kscriptx.pack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Shared GraalVM `native-image` invocation used by executable and `--shared` builds.
 */
object NativeImageRunner {
    data class Request(
        val graalvmHome: String? = null,
        /** Optional reachability metadata directory (`-H:ConfigurationFileDirectories`). */
        val configDir: String? = null,
        /** Extra raw args appended before outputs. */
        val extraArgs: List<String> = emptyList(),
        val workingDir: Path? = null,
    )

    fun run(args: List<String>, request: Request): String {
        val home = GraalvmHome.resolve(request.graalvmHome)
        val ni = GraalvmHome.nativeImageBinary(home)
        val cmd = buildList {
            add(ni.absolutePathString())
            addAll(args)
            request.configDir?.let { dir ->
                val p = Path(dir)
                require(p.exists() && p.isDirectory()) {
                    "native config dir missing or not a directory: $dir"
                }
                add("-H:ConfigurationFileDirectories=${p.absolutePathString()}")
            }
            addAll(request.extraArgs)
        }
        println("==> native-image ($home)")
        println("    ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
            .directory((request.workingDir ?: home).toFile())
            .redirectErrorStream(true)
        pb.environment()["JAVA_HOME"] = home.absolutePathString()
        val pathKey = pb.environment().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val oldPath = pb.environment()[pathKey].orEmpty()
        val binDir = (home / "bin").absolutePathString()
        pb.environment()[pathKey] = if (oldPath.isBlank()) binDir else "$binDir${java.io.File.pathSeparator}$oldPath"

        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            error("native-image failed (exit $code):\n$output")
        }
        return output
    }

    /** Jars needed to compile `@CEntryPoint` bridge sources against this GraalVM. */
    fun svmCompileClasspath(graalvmHome: String? = null): List<Path> {
        val home = GraalvmHome.resolve(graalvmHome)
        val builder = home / "lib" / "svm" / "builder"
        require(builder.exists()) {
            "GraalVM SVM builder libs missing under $builder (need GraalVM with native-image)"
        }
        val jars = builder.listDirectoryEntries("*.jar")
        require(jars.isNotEmpty()) { "No jars under $builder" }
        return jars
    }

    fun findJavac(graalvmHome: String? = null): Path {
        val home = GraalvmHome.resolve(graalvmHome)
        val unix = home / "bin" / "javac"
        val win = home / "bin" / "javac.exe"
        return when {
            unix.exists() -> unix
            win.exists() -> win
            else -> error("javac not found under GraalVM home $home")
        }
    }
}
