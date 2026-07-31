package io.kscriptx.compile

import io.kscriptx.KPaths
import io.kscriptx.KscriptVersions
import io.kscriptx.model.ResolvedScript
import io.kscriptx.util.Hasher
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Disk cache of resolved dependency classpaths, keyed without script body. */
object DepsClasspathStore {
    fun hashOf(script: ResolvedScript, entryPoint: String): String = Hasher.md5(
        buildString {
            appendLine(script.config.dependencies.joinToString("|"))
            appendLine(script.config.repositories.joinToString("|") { it.encode() })
            appendLine(script.config.compilerOptions.joinToString(" "))
            appendLine(entryPoint)
            appendLine(KscriptVersions.KOTLIN)
            appendLine(KscriptVersions.JVM)
        }
    )

    fun load(depsHash: String): String? {
        val cp = KPaths.depsCache / depsHash / "classpath"
        if (!cp.exists()) return null
        val text = cp.readText().trim()
        if (text.isEmpty()) return null
        // Reject stale entries: spot-check jars (full scan is O(n) stat on large CPs).
        val sep = java.io.File.pathSeparator
        val parts = text.split(sep).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val sample = when {
            parts.size <= 4 -> parts
            else -> listOf(parts.first(), parts[parts.size / 2], parts.last())
        }
        if (sample.any { !java.io.File(it).isFile }) return null
        return text
    }

    fun save(depsHash: String, classpath: String) {
        val dir = KPaths.depsCache / depsHash
        dir.createDirectories()
        (dir / "classpath").writeText(classpath)
    }

    fun clear() {
        if (KPaths.depsCache.exists()) {
            KPaths.depsCache.toFile().deleteRecursively()
        }
        KPaths.depsCache.createDirectories()
    }
}
