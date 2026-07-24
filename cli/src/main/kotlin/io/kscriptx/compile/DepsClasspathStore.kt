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
            appendLine("17")
        }
    )

    fun load(depsHash: String): String? {
        val cp = KPaths.depsCache / depsHash / "classpath"
        if (!cp.exists()) return null
        val text = cp.readText().trim()
        return text.ifEmpty { null }
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
