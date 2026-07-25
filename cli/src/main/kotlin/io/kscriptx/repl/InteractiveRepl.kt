package io.kscriptx.repl

import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import kotlin.io.path.absolutePathString

object InteractiveRepl {
    fun start(script: ResolvedScript, compiled: CompiledScript): Int {
        val cp = buildString {
            append(compiled.classesDir.absolutePathString())
            if (compiled.classpath.isNotBlank()) {
                append(System.getProperty("path.separator"))
                append(compiled.classpath)
            }
        }
        println("Creating REPL from ${script.displayName}")
        val kotlinc = findKotlinc()
        if (kotlinc == null) {
            System.err.println("kotlinc not found on PATH. Install Kotlin or add kotlinc to PATH.")
            System.err.println("Classpath prepared at:")
            System.err.println(cp)
            return 1
        }
        val cmd = listOf(kotlinc, "-classpath", cp)
        return ProcessBuilder(cmd).inheritIO().start().waitFor()
    }

    private fun findKotlinc(): String? {
        val candidates = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("kotlinc.bat", "kotlinc")
        } else listOf("kotlinc")
        for (c in candidates) {
            try {
                val p = ProcessBuilder(c, "-version").redirectErrorStream(true).start()
                p.waitFor()
                if (p.exitValue() == 0 || p.exitValue() == 1) return c
            } catch (_: Exception) {
            }
        }
        val kotlinHome = System.getenv("KOTLIN_HOME")
        if (!kotlinHome.isNullOrBlank()) {
            val bin = if (System.getProperty("os.name").lowercase().contains("win")) {
                "$kotlinHome\\bin\\kotlinc.bat"
            } else {
                "$kotlinHome/bin/kotlinc"
            }
            if (java.io.File(bin).exists()) return bin
        }
        return null
    }
}