package io.kscriptx.resolve

import io.kscriptx.KPaths
import io.kscriptx.config.UserConfig
import io.kscriptx.model.*
import io.kscriptx.parse.AnnotationParser
import io.kscriptx.util.Hasher
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

object ScriptResolver {
    private const val SUPPORT_API = "com.github.holgerbrandl:kscript-support-api:1.2.5"
    // Prefer a stdin-safe lines binding: System.in.available() is unreliable on Windows pipes.
    private val TEXT_PREAMBLE = """
        |@file:DependsOn("$SUPPORT_API")
        |import kscript.*
        |import kscript.text.*
        |val lines: Sequence<String> = run {
        |    if (args.isNotEmpty()) {
        |        val f = java.io.File(args[0])
        |        if (f.canRead()) return@run kscript.text.linesFrom(f)
        |    }
        |    generateSequence { readLine() }
        |}
    """.trimMargin()

    fun resolve(
        source: String,
        scriptArgs: List<String>,
        textMode: Boolean,
        userConfig: UserConfig,
    ): ResolvedScript {
        val (kind, displayName, rootPath, rawContent) = loadSource(source)
        val withPreamble = buildString {
            if (userConfig.preamble.isNotBlank()) {
                appendLine(userConfig.preamble)
            }
            if (textMode) {
                appendLine(TEXT_PREAMBLE)
            }
            append(rawContent)
        }

        val visited = linkedSetOf<String>()
        val sources = mutableListOf<SourceUnit>()
        var config = userConfig.asScriptConfig()

        fun ingest(name: String, content: String, origin: Path?, baseDir: Path?) {
            val key = Hasher.md5(content)
            if (!visited.add(key)) return
            val (ann, body) = AnnotationParser.parse(content)
            config += ann
            sources += SourceUnit(name, body, origin)
            for (imp in ann.imports) {
                val imported = resolveImport(imp, baseDir)
                ingest(imported.fileName, imported.content, imported.origin, imported.origin?.parent)
            }
        }

        val baseDir = rootPath?.parent
        ingest(displayName, withPreamble, rootPath, baseDir)

        val hashMaterial = buildString {
            append(withPreamble)
            sources.forEach { append('\n').append(it.fileName).append('\n').append(it.content) }
            append(config.dependencies.joinToString())
            append(config.repositories.joinToString { it.encode() })
            append(config.compilerOptions.joinToString())
            append(config.kotlinOptions.joinToString())
            append(config.entryPoint.orEmpty())
            append(textMode)
        }

        return ResolvedScript(
            displayName = displayName,
            kind = kind,
            rootFile = rootPath,
            sources = sources,
            config = config,
            scriptArgs = scriptArgs,
            rawHashMaterial = hashMaterial,
        )
    }

    private data class Loaded(val kind: ScriptKind, val name: String, val path: Path?, val content: String)

    private fun loadSource(source: String): Loaded {
        when {
            source == "-" -> {
                val content = System.`in`.bufferedReader().readText()
                return Loaded(ScriptKind.INLINE, "stdin.kts", null, content)
            }
            isUrl(source) -> {
                val content = fetchUrl(source)
                val name = source.substringAfterLast('/').substringBefore('?').ifBlank { "remote.kts" }
                return Loaded(kindForName(name), name, null, content)
            }
            looksLikeInlineCode(source) -> {
                return Loaded(ScriptKind.INLINE, "inline.kts", null, source)
            }
            else -> {
                val path = Path(source).toAbsolutePath().normalize()
                require(path.exists()) { "Script not found: $source" }
                val name = path.fileName.toString()
                return Loaded(kindForName(name), name, path, path.readText())
            }
        }
    }

    private fun kindForName(name: String): ScriptKind = when {
        name.endsWith(".kt") -> ScriptKind.KT
        else -> ScriptKind.KTS
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    private fun looksLikeInlineCode(s: String): Boolean {
        if (s.contains('\n')) return true
        if (pathExistsSafe(s)) return false
        if (s.contains(' ')) return true
        if (!s.contains('/') && !s.contains('\\') && !s.endsWith(".kts") && !s.endsWith(".kt")) {
            return s.any { it == '(' || it == '"' || it == '\'' || it == '{' } ||
                s.contains("println") || s.contains("fun ") || s.contains("val ") || s.contains("lines.")
        }
        return false
    }

    private fun pathExistsSafe(s: String): Boolean = try {
        Files.exists(Path(s))
    } catch (_: Exception) {
        false
    }

    private fun fetchUrl(url: String): String {
        KPaths.urlCache.createDirectories()
        val key = Hasher.md5(url)
        val cached = KPaths.urlCache / key
        if (cached.exists()) return cached.readText()
        val text = URI(url).toURL().openStream().bufferedReader().readText()
        cached.writeText(text)
        return text
    }

    private fun resolveImport(spec: String, baseDir: Path?): SourceUnit {
        if (isUrl(spec)) {
            val content = fetchUrl(spec)
            val name = spec.substringAfterLast('/').ifBlank { "import_${Hasher.md5(spec).take(8)}.kt" }
            return SourceUnit(name, content, null)
        }
        val path = when {
            Path(spec).isAbsolute -> Path(spec)
            baseDir != null -> (baseDir / spec).normalize()
            else -> Path(spec).toAbsolutePath().normalize()
        }
        require(path.exists()) { "Imported file not found: $spec (resolved to $path)" }
        return SourceUnit(path.fileName.toString(), path.readText(), path)
    }
}
