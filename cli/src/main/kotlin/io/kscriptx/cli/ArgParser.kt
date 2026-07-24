package io.kscriptx.cli

import io.kscriptx.model.CliRequest
import io.kscriptx.model.RunMode

object ArgParser {
    fun parse(args: Array<String>): CliRequest {
        if (args.isEmpty()) return CliRequest(RunMode.HELP, null, emptyList(), false)

        var mode = RunMode.RUN
        var textMode = false
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help" -> return CliRequest(RunMode.HELP, null, emptyList(), false)
                "-v", "--version" -> return CliRequest(RunMode.VERSION, null, emptyList(), false)
                "--clear-cache" -> return CliRequest(RunMode.CLEAR_CACHE, null, emptyList(), false)
                "--idea" -> { mode = RunMode.IDEA; i++; continue }
                "--package" -> { mode = RunMode.PACKAGE; i++; continue }
                "--interactive" -> { mode = RunMode.INTERACTIVE; i++; continue }
                "--add-bootstrap-header" -> { mode = RunMode.ADD_BOOTSTRAP; i++; continue }
                "-t", "--text" -> { textMode = true; i++; continue }
                else -> break
            }
        }

        if (i >= args.size) {
            return if (mode == RunMode.RUN) CliRequest(RunMode.HELP, null, emptyList(), textMode)
            else CliRequest(mode, null, emptyList(), textMode)
        }

        val scriptSource = args[i]
        val scriptArgs = args.drop(i + 1)
        return CliRequest(mode, scriptSource, scriptArgs, textMode)
    }

    fun helpText(): String = """
        |kscriptx — Kotlin scripting (Coursier + native kotlinc)
        |
        |Usage:
        |  kscriptx [options] <script> [args...]
        |
        |Script may be a .kts/.kt file, URL, '-', or inline Kotlin code.
        |
        |Options:
        |  -t, --text                 Text-processing mode (kscript support API)
        |  --idea                     Generate and open an IntelliJ project
        |  --package                  Build a standalone binary next to the script
        |  --interactive              Start a REPL with script dependencies
        |  --clear-cache              Wipe compiled and URL caches
        |  --add-bootstrap-header     Embed self-install shebang helper into a script
        |  -h, --help                 Show help
        |  -v, --version              Show version
        |
        |Annotations (kotlin-main-kts / kscript compatible):
        |  @file:DependsOn("g:a:v", ...)
        |  @file:DependsOnMaven("g:a:v")
        |  @file:Repository("url")
        |  @file:Repository("id", "url", user="u", password="p")
        |  @file:Import("other.kt")
        |  @file:CompilerOptions("...")
        |  @file:KotlinOptions("-J-Xmx1g")
        |  @file:EntryPoint("pkg.MainKt")
        |
        |Cache home: KSCRIPTX_DIRECTORY (default ~/.kscriptx)
        |Native kotlinc: ./scripts/build-native-kotlinc.sh
    """.trimMargin()
}
