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
        var compileBeside = false
        var nativeImage = false
        var nativeShared = false
        var nativeRunner = false
        var nativeConfigDir: String? = null
        val nativeImageArgs = mutableListOf<String>()
        var graalvmHome: String? = null
        var proguard = false
        var proguardHome: String? = null
        var proguardJar: String? = null
        var packageFlag = false
        var jarFlag = false
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help" -> return CliRequest(RunMode.HELP, null, emptyList(), false)
                "-v", "--version" -> return CliRequest(RunMode.VERSION, null, emptyList(), false)
                "--clear-cache" -> return CliRequest(RunMode.CLEAR_CACHE, null, emptyList(), false)
                "--idea" -> { mode = RunMode.IDEA; i++; continue }
                "--package" -> {
                    mode = RunMode.PACKAGE
                    packageFlag = true
                    i++
                    continue
                }
                "--jar", "--standalone-jar" -> {
                    mode = RunMode.PACKAGE
                    jarFlag = true
                    i++
                    continue
                }
                "--native" -> {
                    mode = RunMode.PACKAGE
                    nativeImage = true
                    i++
                    continue
                }
                "--native-shared" -> {
                    mode = RunMode.PACKAGE
                    nativeShared = true
                    i++
                    continue
                }
                "--native-runner" -> {
                    mode = RunMode.PACKAGE
                    nativeShared = true
                    nativeRunner = true
                    i++
                    continue
                }
                "--proguard" -> {
                    mode = RunMode.PACKAGE
                    proguard = true
                    i++
                    continue
                }
                "--interactive" -> { mode = RunMode.INTERACTIVE; i++; continue }
                "--add-bootstrap-header" -> { mode = RunMode.ADD_BOOTSTRAP; i++; continue }
                "-t", "--text" -> { textMode = true; i++; continue }
                "--compile-beside" -> { compileBeside = true; i++; continue }
                "--graalvm-home" -> {
                    require(i + 1 < args.size) { "Missing value for --graalvm-home" }
                    graalvmHome = args[i + 1]
                    i += 2
                    continue
                }
                "--native-config-dir" -> {
                    require(i + 1 < args.size) { "Missing value for --native-config-dir" }
                    nativeConfigDir = args[i + 1]
                    i += 2
                    continue
                }
                "--native-image-arg" -> {
                    require(i + 1 < args.size) { "Missing value for --native-image-arg" }
                    nativeImageArgs += args[i + 1]
                    i += 2
                    continue
                }
                "--proguard-home" -> {
                    require(i + 1 < args.size) { "Missing value for --proguard-home" }
                    proguardHome = args[i + 1]
                    i += 2
                    continue
                }
                "--proguard-jar" -> {
                    require(i + 1 < args.size) { "Missing value for --proguard-jar" }
                    proguardJar = args[i + 1]
                    i += 2
                    continue
                }
                "--no-daemon", "--daemon" -> { i++; continue }
                else -> {
                    when {
                        a.startsWith("--graalvm-home=") -> {
                            graalvmHome = a.removePrefix("--graalvm-home=").ifBlank { null }
                            i++
                            continue
                        }
                        a.startsWith("--native-config-dir=") -> {
                            nativeConfigDir = a.removePrefix("--native-config-dir=").ifBlank { null }
                            i++
                            continue
                        }
                        a.startsWith("--native-image-arg=") -> {
                            val v = a.removePrefix("--native-image-arg=")
                            require(v.isNotBlank()) { "Missing value for --native-image-arg=" }
                            nativeImageArgs += v
                            i++
                            continue
                        }
                        a.startsWith("--proguard-home=") -> {
                            proguardHome = a.removePrefix("--proguard-home=").ifBlank { null }
                            i++
                            continue
                        }
                        a.startsWith("--proguard-jar=") -> {
                            proguardJar = a.removePrefix("--proguard-jar=").ifBlank { null }
                            i++
                            continue
                        }
                        else -> break
                    }
                }
            }
        }

        // Launcher for --package; bare --proguard (pre-jar UX); dual jar+native via --package --native.
        // Bare --native / --jar alone skip the smart wrapper.
        val writeLauncher = packageFlag || (proguard && !jarFlag && !nativeImage && !nativeShared)

        fun request(
            scriptSource: String?,
            scriptArgs: List<String>,
        ) = CliRequest(
            mode = mode,
            scriptSource = scriptSource,
            scriptArgs = scriptArgs,
            textMode = textMode,
            compileBeside = compileBeside,
            nativeImage = nativeImage,
            nativeShared = nativeShared,
            nativeRunner = nativeRunner,
            nativeConfigDir = nativeConfigDir,
            nativeImageArgs = nativeImageArgs.toList(),
            graalvmHome = graalvmHome,
            proguard = proguard,
            proguardHome = proguardHome,
            proguardJar = proguardJar,
            standaloneJar = jarFlag,
            writeLauncher = writeLauncher,
        )

        if (i >= args.size) {
            return if (mode == RunMode.RUN) CliRequest(RunMode.HELP, null, emptyList(), textMode)
            else request(null, emptyList())
        }

        val scriptSource = args[i]
        val scriptArgs = args.drop(i + 1)
        return request(scriptSource, scriptArgs)
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
        |  --jar, --standalone-jar    Build a standalone fat jar (java -jar; no launcher)
        |  --package                  Fat jar + shell/cmd launcher next to the script
        |  --native                   GraalVM native-image executable (implies fat jar)
        |  --native-shared            GraalVM native shared library (+ bridge, helper)
        |  --native-runner            Shared lib + thin C runner (implies --native-shared)
        |  --native-config-dir <dir>  Reachability metadata for native-image
        |  --native-image-arg <arg>   Extra native-image flag (repeatable)
        |  --graalvm-home <path>      GraalVM home (else GRAALVM_HOME / PATH)
        |  --proguard                 Shrink/optimize the fat jar with ProGuard
        |  --proguard-home <path>     ProGuard home for --proguard (else PROGUARD_HOME / PATH)
        |  --proguard-jar <path>      Explicit proguard.jar for --proguard (else PROGUARD_JAR)
        |  --compile-beside           Mirror compile artifacts (+ jar copy) next to the script
        |  --interactive              Start a REPL with script dependencies
        |  --clear-cache              Wipe compiled and URL caches
        |  --add-bootstrap-header     Embed self-install shebang helper into a script
        |  --no-daemon                Do not use / spawn the persistent JVM daemon
        |  --daemon                   Force-enable the daemon for this run
        |  -h, --help                 Show help
        |  -v, --version              Show version
        |
        |Mode matrix (packaging):
        |  --jar                      <stem>.jar only
        |  --package                  <stem>.jar + JVM launcher <stem>
        |  --native                   <stem>.jar + native binary <stem>
        |  --package --native         <stem>.jar + <stem>.native + smart <stem>
        |                             (smart launcher prefers native, else java -jar)
        |  --native-shared            lib<stem>.so/.dylib/.dll + header + helper
        |  --native-shared --native-runner
        |                             shared lib + <stem>-runner (C) + helper
        |
        |Package pipeline:
        |  compile → fat jar → [--proguard] → [--native-shared] → [--native]
        |         → launcher / smart wrapper when --package
        |
        |Native config:
        |  --native-config-dir passes -H:ConfigurationFileDirectories=<dir>
        |  --native-image-arg may be repeated (e.g. --native-image-arg=-O3)
        |
        |Shebang (disable daemon):
        |  #!/usr/bin/env -S kscriptx --no-daemon
        |
        |Cache home: KSCRIPTX_DIRECTORY (default ~/.kscriptx)
        |Native kotlinc: bundled in .deb / Linux tarball, or ./scripts/build-native-kotlinc.sh
        |Daemon: auto-started; exits after KSCRIPTX_DAEMON_IDLE_MINUTES (default 30).
        |         Disable with --no-daemon or KSCRIPTX_DAEMON=0
    """.trimMargin()
}
