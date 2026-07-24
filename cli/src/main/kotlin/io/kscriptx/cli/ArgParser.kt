package io.kscriptx.cli

import io.kscriptx.model.CliRequest
import io.kscriptx.model.RunMode

object ArgParser {
    /**
     * Strip daemon-related flags that may appear in a shebang
     * (`#!/usr/bin/env -S kscriptx --no-daemon`) and apply [io.kscriptx.daemon.Daemon.cliOverride].
     */
    fun applyDaemonFlags(args: Array<String>): Array<String> {
        var override: Boolean? = null
        val out = ArrayList<String>(args.size)
        for (a in args) {
            when (a) {
                "--no-daemon" -> override = false
                "--daemon" -> override = true
                "--daemon-server" -> out.add(a) // handled in main; keep if present
                else -> out.add(a)
            }
        }
        io.kscriptx.daemon.Daemon.cliOverride = override
        return out.toTypedArray()
    }

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
                // Already stripped by applyDaemonFlags; ignore if still present.
                "--no-daemon", "--daemon" -> { i++; continue }
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
        |  --no-daemon                Do not use / spawn the persistent JVM daemon
        |  --daemon                   Force-enable the daemon for this run
        |  -h, --help                 Show help
        |  -v, --version              Show version
        |
        |Shebang (disable daemon):
        |  #!/usr/bin/env -S kscriptx --no-daemon
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
        |Native kotlinc: bundled in .deb / Linux tarball, or ./scripts/build-native-kotlinc.sh
        |Daemon: auto-started (disable with --no-daemon or KSCRIPTX_DAEMON=0)
    """.trimMargin()
}
