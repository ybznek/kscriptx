package io.kscriptx.exec

import io.kscriptx.model.CompiledScript
import java.io.File
import java.lang.ref.SoftReference
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess

object ScriptRunner {
    private data class CachedEntry(
        val urls: Array<URL>,
        val loader: URLClassLoader,
        val main: Method,
    )

    /** Reused across daemon requests (avoids reopening dependency jars every run). */
    private val loaderCache = ConcurrentHashMap<String, SoftReference<CachedEntry>>()

    fun run(compiled: CompiledScript, scriptArgs: List<String>, workingDir: Path? = null): Int {
        val needsSeparateJvm = compiled.kotlinOptions.any {
            it.startsWith("-J") || (it.startsWith("-D") && it.contains(" "))
        }
        return if (needsSeparateJvm || workingDir != null) {
            runForked(compiled, scriptArgs, workingDir)
        } else {
            runInProcess(compiled, scriptArgs)
        }
    }

    /**
     * Fast path: load script classes in-process. Avoids ~150–200ms for a second JVM.
     * Falls back to forking when [CompiledScript.kotlinOptions] need dedicated JVM flags.
     */
    private fun runInProcess(compiled: CompiledScript, scriptArgs: List<String>): Int {
        for (opt in compiled.kotlinOptions) {
            if (opt.startsWith("-D") && opt.contains("=")) {
                val body = opt.removePrefix("-D")
                val eq = body.indexOf('=')
                if (eq > 0) {
                    System.setProperty(body.substring(0, eq), body.substring(eq + 1))
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
        val cacheKey = compiled.hash + '\u0000' + compiled.entryPoint + '\u0000' + compiled.classpath
        val reuse = System.getProperty("KSCRIPTX_IN_DAEMON") == "1" ||
            System.getenv("KSCRIPTX_IN_DAEMON") == "1"

        val mainMethod: Method
        val loader: URLClassLoader
        var closeLoader = false
        if (reuse) {
            val cached = loaderCache[cacheKey]?.get()
            if (cached != null && cached.urls.contentEquals(urls)) {
                loader = cached.loader
                mainMethod = cached.main
            } else {
                loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
                val mainClass = Class.forName(compiled.entryPoint, true, loader)
                mainMethod = mainClass.getMethod("main", Array<String>::class.java)
                loaderCache[cacheKey] = SoftReference(CachedEntry(urls, loader, mainMethod))
            }
        } else {
            loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
            closeLoader = true
            val mainClass = Class.forName(compiled.entryPoint, true, loader)
            mainMethod = mainClass.getMethod("main", Array<String>::class.java)
        }

        try {
            mainMethod.invoke(null, scriptArgs.toTypedArray())
            return 0
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw RuntimeException(cause)
        } finally {
            if (closeLoader) {
                try {
                    loader.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun runForked(compiled: CompiledScript, scriptArgs: List<String>, workingDir: Path?): Int {
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
            add(javaBinary())
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
        if (workingDir != null) pb.directory(workingDir.toFile())
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

    fun javaBinary(): String {
        val home = System.getenv("JAVA_HOME")
        if (!home.isNullOrBlank()) {
            val bin = if (isWindows()) "$home\\bin\\java.exe" else "$home/bin/java"
            if (File(bin).exists()) return bin
        }
        return "java"
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    fun runOrExit(compiled: CompiledScript, scriptArgs: List<String>, workingDir: Path? = null) {
        exitProcess(run(compiled, scriptArgs, workingDir))
    }
}
