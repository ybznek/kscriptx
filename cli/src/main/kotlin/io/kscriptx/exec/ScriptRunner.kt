package io.kscriptx.exec

import io.kscriptx.ExecutionContext
import io.kscriptx.model.CompiledScript
import java.io.File
import java.lang.ref.SoftReference
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess

object ScriptRunner {
    private data class CachedEntry(
        val urls: Array<URL>,
        val loader: URLClassLoader,
        val main: Method,
    )

    /** Reused across non-forked daemon compiles only (script runs fork with client env). */
    private val loaderCache = ConcurrentHashMap<String, SoftReference<CachedEntry>>()

    fun clearLoaderCache() {
        loaderCache.clear()
    }

    /**
     * @param execEnv When set (daemon client environment), run in a child JVM with this exact env
     *   and [workingDir] so `System.getenv()` / PWD match the invoking shell.
     */
    fun run(
        compiled: CompiledScript,
        scriptArgs: List<String>,
        workingDir: Path? = null,
        execEnv: Map<String, String>? = null,
    ): Int {
        val wd = workingDir ?: execEnv?.let { Path(ExecutionContext.userDir()) }
        val needsSeparateJvm = compiled.kotlinOptions.any {
            it.startsWith("-J") || (it.startsWith("-D") && it.contains(" "))
        }
        return if (execEnv != null || needsSeparateJvm || wd != null) {
            runForked(compiled, scriptArgs, wd, execEnv)
        } else {
            runInProcess(compiled, scriptArgs)
        }
    }

    private fun runInProcess(compiled: CompiledScript, scriptArgs: List<String>): Int {
        val restoredProps = LinkedHashMap<String, String?>()
        for (opt in compiled.kotlinOptions) {
            if (opt.startsWith("-D") && opt.contains("=")) {
                val body = opt.removePrefix("-D")
                val eq = body.indexOf('=')
                if (eq > 0) {
                    val key = body.substring(0, eq)
                    restoredProps.putIfAbsent(key, System.getProperty(key))
                    System.setProperty(key, body.substring(eq + 1))
                }
            }
        }

        val paths = buildList {
            add(compiled.classesDir.absolutePathString())
            if (compiled.classpath.isNotBlank()) {
                addAll(compiled.classpath.split(File.pathSeparator).filter { it.isNotBlank() })
            }
        }
        val urls = paths.map { File(it).toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
        val mainClass = Class.forName(compiled.entryPoint, true, loader)
        val mainMethod = mainClass.getMethod("main", Array<String>::class.java)

        try {
            mainMethod.invoke(null, scriptArgs.toTypedArray())
            return 0
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw RuntimeException(cause)
        } finally {
            for ((key, old) in restoredProps) {
                if (old == null) System.clearProperty(key) else System.setProperty(key, old)
            }
            try {
                loader.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun runForked(
        compiled: CompiledScript,
        scriptArgs: List<String>,
        workingDir: Path?,
        execEnv: Map<String, String>?,
    ): Int {
        val cp = buildString {
            append(compiled.classesDir.absolutePathString())
            if (compiled.classpath.isNotBlank()) {
                append(File.pathSeparator)
                append(compiled.classpath)
            }
        }
        val javaOpts = compiled.kotlinOptions
            .flatMap { opt ->
                when {
                    opt.startsWith("-J") -> listOf(opt.removePrefix("-J"))
                    opt.startsWith("-D") -> listOf(opt)
                    else -> emptyList()
                }
            }
        val cmd = buildList {
            add(javaBinary(execEnv))
            addAll(javaOpts)
            add("-cp")
            add(cp)
            add(compiled.entryPoint)
            addAll(scriptArgs)
        }

        val pb = ProcessBuilder(cmd)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        if (execEnv != null) {
            pb.environment().clear()
            pb.environment().putAll(execEnv)
        }
        if (workingDir != null) {
            pb.directory(workingDir.toFile())
        }
        val proc = pb.start()
        Thread {
            try {
                System.`in`.copyTo(proc.outputStream)
            } catch (_: Exception) {
            } finally {
                try {
                    proc.outputStream.close()
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; start() }
        return proc.waitFor()
    }

    fun javaBinary(env: Map<String, String>? = null): String {
        val home = env?.get("JAVA_HOME")
            ?: ExecutionContext.getenv("JAVA_HOME")
        if (!home.isNullOrBlank()) {
            val bin = if (isWindows()) "$home\\bin\\java.exe" else "$home/bin/java"
            if (File(bin).exists()) return bin
        }
        return "java"
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    fun runOrExit(
        compiled: CompiledScript,
        scriptArgs: List<String>,
        workingDir: Path? = null,
        execEnv: Map<String, String>? = null,
    ) {
        exitProcess(run(compiled, scriptArgs, workingDir, execEnv))
    }
}
