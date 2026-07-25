package io.kscriptx.perf

import io.kscriptx.compile.ScriptWrapper
import io.kscriptx.model.ResolvedScript
import io.kscriptx.model.ScriptConfig
import io.kscriptx.model.ScriptKind
import io.kscriptx.model.SourceUnit
import io.kscriptx.parse.AnnotationParser
import io.kscriptx.util.Hasher
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Micro-benchmarks that always run in CI. Writes markdown + JSON under build/reports/perf/.
 */
class PerfStatsTest {
    @Test
    fun collectMicrobenchStats() {
        val sample = """
            @file:DependsOn("org.jsoup:jsoup:1.17.2")
            @file:Repository("https://repo1.maven.org/maven2")
            @file:Import("utils.kt")
            println("hi")
        """.trimIndent()

        val parseNs = measureNanoTime {
            repeat(2_000) { AnnotationParser.parse(sample) }
        }
        val hashNs = measureNanoTime {
            repeat(5_000) { Hasher.md5(sample) }
        }
        val wrapNs = measureNanoTime {
            val script = ResolvedScript(
                displayName = "x.kts",
                kind = ScriptKind.KTS,
                rootFile = null,
                sources = listOf(SourceUnit("x.kts", "println(1)", null)),
                config = ScriptConfig(),
                scriptArgs = emptyList(),
                rawHashMaterial = "x",
            )
            repeat(2_000) { ScriptWrapper.toCompilableSources(script) }
        }

        val parseUs = parseNs / 2_000.0 / 1_000.0
        val hashUs = hashNs / 5_000.0 / 1_000.0
        val wrapUs = wrapNs / 2_000.0 / 1_000.0

        // Sanity: should complete quickly on CI runners
        assertTrue(parseUs < 5_000.0, "parse too slow: ${parseUs}µs")
        assertTrue(hashUs < 1_000.0, "hash too slow: ${hashUs}µs")
        assertTrue(wrapUs < 5_000.0, "wrap too slow: ${wrapUs}µs")

        val outDir = Files.createDirectories(
            java.nio.file.Path.of("build/reports/perf")
        )
        val md = buildString {
            appendLine("### Micro-benchmarks (unit)")
            appendLine()
            appendLine("| Operation | iters | avg (µs) |")
            appendLine("|---|---:|---:|")
            appendLine("| AnnotationParser.parse | 2000 | ${"%.2f".format(parseUs)} |")
            appendLine("| Hasher.md5 | 5000 | ${"%.2f".format(hashUs)} |")
            appendLine("| ScriptWrapper.toCompilableSources | 2000 | ${"%.2f".format(wrapUs)} |")
            appendLine()
        }
        outDir.resolve("microbench.md").writeText(md)
        outDir.resolve("microbench.json").writeText(
            """
            {
              "annotation_parser_us": $parseUs,
              "hasher_md5_us": $hashUs,
              "script_wrapper_us": $wrapUs
            }
            """.trimIndent() + "\n"
        )
        println(md)
    }
}
