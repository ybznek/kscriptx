package io.kscriptx

import io.kscriptx.bootstrap.BootstrapHeader
import io.kscriptx.cli.ArgParser
import io.kscriptx.compile.CacheStore
import io.kscriptx.compile.CompileBeside
import io.kscriptx.compile.FastCache
import io.kscriptx.compile.ScriptCompiler
import io.kscriptx.config.UserConfig
import io.kscriptx.daemon.Daemon
import io.kscriptx.exec.ScriptRunner
import io.kscriptx.idea.IdeaProjectGenerator
import io.kscriptx.model.CliRequest
import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
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

    // Prefer persistent daemon for compile (script JVM starts in this process tree).
    if (Daemon.enabled() &&
        filtered.isNotEmpty() &&
        filtered[0] != "--daemon-server" &&
        System.getenv("KSCRIPTX_IN_DAEMON") != "1"
    ) {
        Daemon.tryClient(filtered)?.let { exitProcess(it) }
    }

    val code = runMain(filtered)
    if (System.getenv("KSCRIPTX_IN_DAEMON") != "1") {
        Daemon.spawnBackgroundIfNeeded()
    }
    exitProcess(code)
}

/**
 * Compile a RUN request to a [CompiledScript], or null if the request is not a runnable script.
 * Used by the daemon (compile-only); the client starts the script JVM.
 */
fun compileOnlyForRun(args: Array<String>): CompiledScript? {
    val request = ArgParser.parse(args)
    if (request.mode != RunMode.RUN) return null
    val scriptSource = request.scriptSource ?: return null

    if (!request.textMode) {
        val asFile = resolveScriptPath(scriptSource)
        if (asFile.isRegularFile()) {
            FastCache.probeFileScript(asFile, textMode = false)?.let { compiled ->
                maybeCompileBesideAfterFastHit(request, asFile, compiled)
                return compiled
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
    val compiled = ScriptCompiler.compile(resolved)
    resolved.rootFile?.let { root ->
        FastCache.remember(
            scriptPath = root,
            textMode = request.textMode,
            compiled = compiled,
            importOrigins = resolved.sources.mapNotNull { it.origin },
        )
    }
    CompileBeside.maybeMaterialize(request.compileBeside, resolved, compiled)
    return compiled
}

/** Resolve script path against [ExecutionContext.userDir] (daemon-safe, no process user.dir). */
internal fun resolveScriptPath(scriptSource: String): java.nio.file.Path {
    val raw = Path(scriptSource)
    val abs = if (raw.isAbsolute) {
        raw
    } else {
        val base = ExecutionContext.userDir().ifBlank { System.getProperty("user.dir") ?: "." }
        Path(base).resolve(raw)
    }
    return abs.toAbsolutePath().normalize()
}

/**
 * Fast-cache hits skip [ScriptResolver]; rebuild a minimal [ResolvedScript] so
 * `--compile-beside` can still mirror artifacts next to the file.
 */
private fun maybeCompileBesideAfterFastHit(
    request: CliRequest,
    scriptPath: java.nio.file.Path,
    compiled: CompiledScript,
) {
    if (!request.compileBeside) return
    val stub = ResolvedScript(
        displayName = scriptPath.fileName.toString(),
        kind = if (scriptPath.fileName.toString().endsWith(".kt")) {
            io.kscriptx.model.ScriptKind.KT
        } else {
            io.kscriptx.model.ScriptKind.KTS
        },
        rootFile = scriptPath,
        sources = emptyList(),
        config = io.kscriptx.model.ScriptConfig(),
        scriptArgs = request.scriptArgs,
        rawHashMaterial = "",
    )
    CompileBeside.maybeMaterialize(true, stub, compiled)
}

/** Local entry when the daemon is unavailable or disabled. */
fun runMain(args: Array<String>): Int {
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
                RunMode.PACKAGE ->
                    when {
                        request.nativeRunner -> "Missing script path for --native-runner"
                        request.nativeShared -> "Missing script path for --native-shared"
                        request.nativeImage -> "Missing script path for --native"
                        request.standaloneJar && !request.writeLauncher && !request.proguard ->
                            "Missing script path for --jar"
                        request.proguard -> "Missing script path for --proguard"
                        else -> "Missing script path for --package"
                    }
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
        val asFile = Path(request.scriptSource).toAbsolutePath().normalize()
        if (asFile.isRegularFile()) {
            FastCache.probeFileScript(asFile, textMode = false)?.let { compiled ->
                maybeCompileBesideAfterFastHit(request, asFile, compiled)
                return ScriptRunner.run(compiled, request.scriptArgs)
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
            CompileBeside.maybeMaterialize(request.compileBeside, resolved, compiled)
            InteractiveRepl.start(resolved, compiled)
        }
        RunMode.PACKAGE -> {
            val compiled = ScriptCompiler.compile(resolved)
            CompileBeside.maybeMaterialize(request.compileBeside, resolved, compiled)
            PackageBuilder.packageBinary(
                resolved,
                compiled,
                PackageBuilder.Options(
                    nativeImage = request.nativeImage,
                    nativeShared = request.nativeShared,
                    nativeRunner = request.nativeRunner,
                    nativeConfigDir = request.nativeConfigDir,
                    nativeImageArgs = request.nativeImageArgs,
                    graalvmHome = request.graalvmHome,
                    proguard = request.proguard,
                    proguardHome = request.proguardHome,
                    proguardJar = request.proguardJar,
                    writeLauncher = request.writeLauncher,
                    compileBeside = request.compileBeside,
                ),
            )
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
            CompileBeside.maybeMaterialize(request.compileBeside, resolved, compiled)
            ScriptRunner.run(compiled, resolved.scriptArgs)
        }
        RunMode.HELP, RunMode.VERSION, RunMode.CLEAR_CACHE, RunMode.ADD_BOOTSTRAP ->
            error("Unhandled mode ${request.mode}")
    }
}
