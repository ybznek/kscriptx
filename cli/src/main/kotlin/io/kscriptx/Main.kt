package io.kscriptx

import io.kscriptx.bootstrap.BootstrapHeader
import io.kscriptx.cli.ArgParser
import io.kscriptx.compile.CacheStore
import io.kscriptx.compile.FastCache
import io.kscriptx.compile.ScriptCompiler
import io.kscriptx.config.UserConfig
import io.kscriptx.daemon.Daemon
import io.kscriptx.exec.ScriptRunner
import io.kscriptx.idea.IdeaProjectGenerator
import io.kscriptx.model.RunMode
import io.kscriptx.pack.PackageBuilder
import io.kscriptx.repl.InteractiveRepl
import io.kscriptx.resolve.ScriptResolver
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size == 1 && (args[0] == "--daemon-server" || args[0] == "--daemon")) {
        // Bare `--daemon` kept as alias for spawning the server process.
        Daemon.startServer()
        return
    }

    val filtered = ArgParser.applyDaemonFlags(args)

    // Prefer persistent daemon for normal script runs (skips JVM startup).
    if (Daemon.enabled() &&
        filtered.isNotEmpty() &&
        filtered[0] != "--daemon-server" &&
        System.getenv("KSCRIPTX_IN_DAEMON") != "1"
    ) {
        Daemon.tryClient(filtered)?.let { exitProcess(it) }
    }

    val code = runMain(filtered, fromDaemon = System.getenv("KSCRIPTX_IN_DAEMON") == "1")
    if (System.getenv("KSCRIPTX_IN_DAEMON") != "1") {
        Daemon.spawnBackgroundIfNeeded()
    }
    exitProcess(code)
}

/**
 * Shared entry for direct JVM runs and the daemon worker.
 * @return process exit code (does not call exitProcess when [fromDaemon] is true for RUN)
 */
fun runMain(args: Array<String>, fromDaemon: Boolean): Int {
    val request = ArgParser.parse(args)
    when (request.mode) {
        RunMode.HELP -> {
            println(ArgParser.helpText())
            return 0
        }
        RunMode.VERSION -> {
            println("kscriptx $VERSION")
            return 0
        }
        RunMode.CLEAR_CACHE -> {
            KPaths.ensureLayout()
            CacheStore.clear()
            println("Cleared kscriptx cache at ${KPaths.home}")
            return 0
        }
        else -> Unit
    }

    val scriptSource = request.scriptSource
        ?: run {
            val msg = when (request.mode) {
                RunMode.IDEA -> "Missing script path for --idea"
                RunMode.PACKAGE -> "Missing script path for --package"
                RunMode.INTERACTIVE -> "Missing script path for --interactive"
                RunMode.ADD_BOOTSTRAP -> "Missing script path for --add-bootstrap-header"
                else -> "Missing script argument"
            }
            System.err.println(msg)
            println(ArgParser.helpText())
            return 1
        }

    if (request.mode == RunMode.ADD_BOOTSTRAP) {
        BootstrapHeader.add(Path(scriptSource).toAbsolutePath().normalize())
        return 0
    }

    // Fast path: unchanged file scripts skip parse/hash/config/mkdirs entirely.
    if (request.mode == RunMode.RUN && !request.textMode) {
        val asFile = Path(scriptSource)
        if (asFile.isRegularFile()) {
            FastCache.probeFileScript(asFile, textMode = false)?.let { compiled ->
                return ScriptRunner.run(compiled, request.scriptArgs, workingDir = null)
            }
        }
    }

    KPaths.ensureRuntimeLayout()
    val userConfig = UserConfig.load()

    val resolved = ScriptResolver.resolve(
        source = scriptSource,
        scriptArgs = request.scriptArgs,
        textMode = request.textMode,
        userConfig = userConfig,
    )

    return when (request.mode) {
        RunMode.IDEA -> {
            KPaths.ensureLayout()
            IdeaProjectGenerator.generateAndOpen(resolved)
            0
        }
        RunMode.INTERACTIVE -> {
            val compiled = ScriptCompiler.compile(resolved)
            InteractiveRepl.start(resolved, compiled)
        }
        RunMode.PACKAGE -> {
            val compiled = ScriptCompiler.compile(resolved)
            PackageBuilder.packageBinary(resolved, compiled)
            0
        }
        RunMode.RUN -> {
            val compiled = ScriptCompiler.compile(resolved)
            resolved.rootFile?.let { root ->
                FastCache.remember(
                    scriptPath = root,
                    textMode = request.textMode,
                    compiled = compiled,
                    importOrigins = resolved.sources.mapNotNull { it.origin },
                )
            }
            ScriptRunner.run(compiled, resolved.scriptArgs, workingDir = null)
        }
        RunMode.HELP, RunMode.VERSION, RunMode.CLEAR_CACHE, RunMode.ADD_BOOTSTRAP ->
            error("Unhandled mode ${request.mode}")
    }
}
