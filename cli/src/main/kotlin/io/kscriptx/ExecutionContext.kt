package io.kscriptx

import java.nio.file.Files
import java.nio.file.Path

/**
 * Optional per-request environment overlay (daemon client env) and working directory.
 * Falls back to [System.getenv] / [System.getProperty] when unset.
 */
object ExecutionContext {
    private val envOverlay = ThreadLocal<Map<String, String>?>()
    private val cwdOverlay = ThreadLocal<String?>()

    /** `{{NAME}}` placeholders in script sources / `@Repository` values. */
    val envVarPattern = Regex("""\{\{(\w+)\}\}""")

    fun getenv(name: String): String? = envOverlay.get()?.get(name) ?: System.getenv(name)

    fun userDir(): String = cwdOverlay.get()?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.dir")
        ?: ""

    fun withContext(
        env: Map<String, String>,
        cwd: String,
        mutateProcessUserDir: Boolean = true,
        block: () -> Int,
    ): Int {
        envOverlay.set(env)
        cwdOverlay.set(cwd)
        val previousDir = System.getProperty("user.dir")
        try {
            // Concurrent daemon compiles must not clobber process-global user.dir.
            if (mutateProcessUserDir && cwd.isNotBlank()) {
                System.setProperty("user.dir", cwd)
            }
            return block()
        } finally {
            if (mutateProcessUserDir) {
                System.setProperty("user.dir", previousDir)
            }
            envOverlay.remove()
            cwdOverlay.remove()
        }
    }

    /** Stable fingerprint for env vars referenced as `{{NAME}}` in script sources. */
    fun envStamp(varNames: Collection<String>): String {
        if (varNames.isEmpty()) return "-"
        val parts = varNames.distinct().sorted().map { name ->
            "$name=${getenv(name).orEmpty()}"
        }
        return parts.joinToString("\u0001")
    }

    fun extractEnvVarNames(text: String): Set<String> =
        envVarPattern.findAll(text).map { it.groupValues[1] }.toSet()

    fun envStampForFiles(paths: Collection<Path>): String {
        val names = linkedSetOf<String>()
        for (p in paths) {
            try {
                if (p.toString().endsWith(".kts") || p.toString().endsWith(".kt")) {
                    names.addAll(extractEnvVarNames(Files.readString(p)))
                }
            } catch (_: Exception) {
            }
        }
        return envStamp(names)
    }
}
