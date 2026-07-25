package io.kscriptx.parse

import io.kscriptx.ExecutionContext
import io.kscriptx.model.Repository
import io.kscriptx.model.ScriptConfig

object AnnotationParser {
    private val annotationLine = Regex("""^\s*(?:@file:|@)(\w+)\s*(?:\((.*)\))?\s*$""")
    private val namedArg = Regex("""(\w+)\s*=\s*(?:"((?:\\.|[^"])*)"|'((?:\\.|[^'])*)')""")

    fun parse(source: String): Pair<ScriptConfig, String> {
        val lines = source.replace("\r\n", "\n").lines()
        var i = 0
        if (lines.isNotEmpty() && lines[0].startsWith("#!")) i = 1

        var config = ScriptConfig()
        val remaining = mutableListOf<String>()
        var inHeader = true

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (inHeader && (trimmed.isEmpty() || trimmed.startsWith("//"))) {
                remaining += line
                i++
                continue
            }
            val match = annotationLine.matchEntire(trimmed)
            if (inHeader && match != null) {
                config = config + parseAnnotation(match.groupValues[1], match.groupValues.getOrElse(2) { "" })
                i++
                continue
            }
            inHeader = false
            remaining.addAll(lines.subList(i, lines.size))
            break
        }
        return config to remaining.joinToString("\n")
    }

    private fun parseAnnotation(name: String, argsRaw: String): ScriptConfig {
        val args = splitArgs(argsRaw)
        return when (name) {
            "DependsOn", "file:DependsOn" -> ScriptConfig(dependencies = args)
            "DependsOnMaven" -> ScriptConfig(dependencies = args.take(1))
            "Repository" -> {
                val named = namedArg.findAll(argsRaw).associate { m ->
                    m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] })
                }
                val strings = extractStringLiterals(argsRaw)
                val repo = when {
                    strings.isEmpty() -> null
                    strings.size == 1 -> Repository(url = resolveEnv(strings[0]))
                    else -> Repository(
                        id = strings[0],
                        url = resolveEnv(strings[1]),
                        user = named["user"]?.let(::resolveEnv),
                        password = named["password"]?.let(::resolveEnv),
                    )
                }
                ScriptConfig(repositories = listOfNotNull(repo))
            }
            "Import", "Include" -> ScriptConfig(imports = args)
            "CompilerOptions" -> ScriptConfig(compilerOptions = args)
            "KotlinOptions" -> ScriptConfig(kotlinOptions = args)
            "EntryPoint" -> ScriptConfig(entryPoint = args.firstOrNull())
            else -> ScriptConfig()
        }
    }

    private fun extractStringLiterals(raw: String): List<String> {
        val re = Regex(""""((?:\\.|[^"])*)"|'((?:\\.|[^'])*)'""")
        return re.findAll(raw).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
    }

    private fun splitArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return extractStringLiterals(raw)
    }

    private fun resolveEnv(value: String): String {
        val re = Regex("""\{\{(\w+)\}\}""")
        return re.replace(value) { m ->
            ExecutionContext.getenv(m.groupValues[1])
                ?: error("Environment variable '${m.groupValues[1]}' required by @Repository is not set")
        }
    }
}
