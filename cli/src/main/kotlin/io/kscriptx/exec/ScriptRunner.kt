package io.kscriptx.exec

import io.kscriptx.ExecutionContext
import io.kscriptx.model.CompiledScript
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

/**
 * How to launch a compiled script as a **separate JVM process**.
 * Built by the daemon (compile-only) and executed by the original client / dclient.
 */
data class ScriptRunPlan(
    val javaBinary: String,
    /** Arguments after the java binary (`-D…`, `-cp`, main, script args). */
    val javaArgs: List<String>,
    val workingDir: String,
)

object ScriptRunner {
    /**
     * Always runs the script in a **child JVM**. The caller process owns that child:
     * shutdown / kill of this process destroys the script JVM.
     */
    fun run(
        compiled: CompiledScript,
        scriptArgs: List<String>,
        workingDir: Path? = null,
        execEnv: Map<String, String>? = null,
    ): Int {
        val wd = workingDir
            ?: execEnv?.let { Path(ExecutionContext.userDir()) }
            ?: Path(ExecutionContext.userDir()).takeIf { ExecutionContext.userDir().isNotBlank() }
        val plan = buildRunPlan(compiled, scriptArgs, wd, execEnv)
        return executePlan(plan, execEnv)
    }

    fun buildRunPlan(
        compiled: CompiledScript,
        scriptArgs: List<String>,
        workingDir: Path?,
        execEnv: Map<String, String>? = null,
    ): ScriptRunPlan {
        val cp = buildString {
            append(compiled.classesDir.absolutePathString())
            if (compiled.classpath.isNotBlank()) {
                append(File.pathSeparator)
                append(compiled.classpath)
            }
        }
        val javaOpts = compiled.kotlinOptions.flatMap { opt ->
            when {
                opt.startsWith("-J") -> listOf(opt.removePrefix("-J"))
                opt.startsWith("-D") -> listOf(opt)
                else -> emptyList()
            }
        }
        val javaArgs = buildList {
            addAll(javaOpts)
            add("-cp")
            add(cp)
            add(compiled.entryPoint)
            addAll(scriptArgs)
        }
        val wd = workingDir?.absolutePathString()
            ?: ExecutionContext.userDir().takeIf { it.isNotBlank() }
            ?: System.getProperty("user.dir")
            ?: "."
        return ScriptRunPlan(
            javaBinary = javaBinary(execEnv),
            javaArgs = javaArgs,
            workingDir = wd,
        )
    }

    fun executePlan(plan: ScriptRunPlan, execEnv: Map<String, String>? = null): Int {
        val cmd = buildList {
            add(plan.javaBinary)
            addAll(plan.javaArgs)
        }
        val pb = ProcessBuilder(cmd)
            .directory(File(plan.workingDir))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
        if (execEnv != null) {
            pb.environment().clear()
            pb.environment().putAll(execEnv)
        }
        val proc = pb.start()
        val child = proc.toHandle()
        val hook = Thread {
            try {
                if (child.isAlive) child.destroy()
            } catch (_: Exception) {
            }
            try {
                if (child.isAlive) child.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        return try {
            proc.waitFor()
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: Exception) {
            }
            if (child.isAlive) {
                child.destroyForcibly()
            }
        }
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
}
