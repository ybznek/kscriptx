package io.kscriptx

import io.kscriptx.bootstrap.BootstrapHeader
import io.kscriptx.cli.ArgParser
import io.kscriptx.compile.CacheStore
import io.kscriptx.compile.ScriptCompiler
import io.kscriptx.config.UserConfig
import io.kscriptx.idea.IdeaProjectGenerator
import io.kscriptx.model.RunMode
import io.kscriptx.pack.PackageBuilder
import io.kscriptx.repl.InteractiveRepl
import io.kscriptx.resolve.ScriptResolver
import io.kscriptx.exec.ScriptRunner
import kotlin.io.path.Path
import kotlin.system.exitProcess

const val VERSION = "0.1.2"

fun main(args: Array<String>) {
    val request = ArgParser.parse(args)
    when (request.mode) {
        RunMode.HELP -> {
            println(ArgParser.helpText())
            return
        }
        RunMode.VERSION -> {
            println("kscriptx $VERSION")
            return
        }
        RunMode.CLEAR_CACHE -> {
            KPaths.ensureLayout()
            CacheStore.clear()
            println("Cleared kscriptx cache at ${KPaths.home}")
            return
        }
        else -> Unit
    }

    val scriptSource = request.scriptSource
        ?: run {
            System.err.println("Missing script argument")
            println(ArgParser.helpText())
            exitProcess(1)
        }

    if (request.mode == RunMode.ADD_BOOTSTRAP) {
        BootstrapHeader.add(Path(scriptSource).toAbsolutePath().normalize())
        return
    }

    KPaths.ensureRuntimeLayout()
    val userConfig = UserConfig.load()
    val resolved = ScriptResolver.resolve(
        source = scriptSource,
        scriptArgs = request.scriptArgs,
        textMode = request.textMode,
        userConfig = userConfig,
    )

    when (request.mode) {
        RunMode.IDEA -> {
            KPaths.ensureLayout()
            IdeaProjectGenerator.generateAndOpen(resolved)
        }
        RunMode.INTERACTIVE -> {
            val compiled = ScriptCompiler.compile(resolved)
            InteractiveRepl.start(resolved, compiled)
        }
        RunMode.PACKAGE -> {
            val compiled = ScriptCompiler.compile(resolved)
            PackageBuilder.packageBinary(resolved, compiled)
        }
        RunMode.RUN -> {
            val compiled = ScriptCompiler.compile(resolved)
            ScriptRunner.runOrExit(compiled, resolved.scriptArgs, workingDir = null)
        }
        RunMode.HELP, RunMode.VERSION, RunMode.CLEAR_CACHE, RunMode.ADD_BOOTSTRAP ->
            error("Unhandled mode ${request.mode}")
    }
}
