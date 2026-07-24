package io.kscriptx.compile

import io.kscriptx.model.ResolvedScript
import io.kscriptx.model.ScriptKind
import io.kscriptx.model.SourceUnit

/**
 * Turns .kts script bodies into compilable Kotlin sources for the JVM Gradle toolchain.
 * Official kotlin-main-kts style top-level statements become `fun main(args: Array<String>)`.
 */
object ScriptWrapper {
    fun toCompilableSources(script: ResolvedScript): List<SourceUnit> {
        if (script.kind == ScriptKind.KT) {
            return script.sources.mapIndexed { idx, unit ->
                if (idx == 0) {
                    unit.copy(fileName = sanitizeName(unit.fileName))
                } else {
                    wrapIfNeeded(unit, forceWrap = unit.fileName.endsWith(".kts"))
                }
            }
        }

        return script.sources.mapIndexed { index, unit ->
            if (index == 0) {
                SourceUnit(
                    fileName = "Script.kt",
                    content = wrapRoot(unit.content, script.config.entryPoint),
                    origin = unit.origin,
                )
            } else {
                wrapIfNeeded(unit, forceWrap = unit.fileName.endsWith(".kts"))
            }
        }
    }

    private fun wrapIfNeeded(unit: SourceUnit, forceWrap: Boolean): SourceUnit {
        if (!forceWrap) return unit.copy(fileName = sanitizeName(unit.fileName))
        return unit.copy(
            fileName = sanitizeName(unit.fileName.removeSuffix(".kts") + "_inc.kt"),
            content = wrapAsObject(unit.content, sanitizeName(unit.fileName).removeSuffix(".kt")),
        )
    }

    private fun wrapRoot(body: String, entryPoint: String?): String {
        // If user already defined main, keep body
        if (Regex("""fun\s+main\s*\(""").containsMatchIn(body)) {
            return ensurePackageFree(body)
        }
        val (pkg, imports, rest) = splitHeader(body)
        return buildString {
            if (pkg != null) appendLine(pkg)
            imports.forEach { appendLine(it) }
            appendLine()
            appendLine("fun main(args: Array<String>) {")
            rest.lines().forEach { line ->
                append("    ").appendLine(line)
            }
            appendLine("}")
            // entryPoint annotation is handled at runtime via KS_ENTRY
            if (entryPoint != null) {
                // no-op: documented for kt files
            }
        }
    }

    private fun wrapAsObject(body: String, name: String): String {
        val (pkg, imports, rest) = splitHeader(body)
        // Includes are typically shared helpers — keep as top-level declarations when they look like declarations
        if (looksLikeDeclarationsOnly(rest)) {
            return buildString {
                if (pkg != null) appendLine(pkg)
                imports.forEach { appendLine(it) }
                appendLine(rest)
            }
        }
        return buildString {
            if (pkg != null) appendLine(pkg)
            imports.forEach { appendLine(it) }
            appendLine("object ${name}Include {")
            rest.lines().forEach { append("    ").appendLine(it) }
            appendLine("}")
        }
    }

    private fun looksLikeDeclarationsOnly(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.lines().all { line ->
            val t = line.trim()
            t.isEmpty() || t.startsWith("//") || t.startsWith("fun ") || t.startsWith("class ") ||
                t.startsWith("object ") || t.startsWith("interface ") || t.startsWith("val ") ||
                t.startsWith("var ") || t.startsWith("typealias ") || t.startsWith("enum ") ||
                t.startsWith("data ") || t.startsWith("sealed ") || t.startsWith("internal ") ||
                t.startsWith("private ") || t.startsWith("public ") || t.startsWith("override ") ||
                t.startsWith("}") || t.startsWith("@")
        }
    }

    private fun splitHeader(body: String): Triple<String?, List<String>, String> {
        val lines = body.replace("\r\n", "\n").lines()
        var pkg: String? = null
        val imports = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            when {
                t.isEmpty() || t.startsWith("//") -> i++
                t.startsWith("package ") -> { pkg = lines[i]; i++ }
                t.startsWith("import ") -> { imports += lines[i]; i++ }
                else -> break
            }
        }
        return Triple(pkg, imports, lines.drop(i).joinToString("\n"))
    }

    private fun ensurePackageFree(body: String): String = body

    private fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9_.]"), "_")
        return if (cleaned.endsWith(".kt")) cleaned else "$cleaned.kt"
    }
}
