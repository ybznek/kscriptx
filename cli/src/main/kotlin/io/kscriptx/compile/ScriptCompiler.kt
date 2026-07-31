package io.kscriptx.compile

import io.kscriptx.KPaths
import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import io.kscriptx.resolve.DependencyResolver

/**
 * Script compile pipeline (no Gradle, no compile daemon):
 * content cache → deps cache → Coursier resolve → native kotlinc (required).
 */
object ScriptCompiler {
    private val lock = Any()

    fun compile(script: ResolvedScript): CompiledScript {
        val hash = CacheStore.hashOf(script)
        CacheStore.load(hash, script.config.kotlinOptions)?.let { return it }

        require(NativeKotlincCompiler.isAvailable()) {
            "Native kotlinc is required (cache miss). Looked in:\n" +
                NativeKotlincCompiler.candidateRoots().joinToString("\n") { "  - $it" } +
                "\nInstall the Debian package (includes native), unpack a release tarball, " +
                "or run ./scripts/build-native-kotlinc.sh (or set KSCRIPTX_NATIVE_KOTLINC)."
        }

        KPaths.ensureLayout()
        val entry = entryPointOf(script)
        val sources = ScriptWrapper.toCompilableSources(script)
        val depsHash = DepsClasspathStore.hashOf(script, entry)

        fun compileWithCp(classpath: String): CompiledScript {
            NativeKotlincCompiler.compileToCache(
                contentHash = hash,
                entry = entry,
                classpath = classpath,
                compilerOptions = script.config.compilerOptions,
                sources = sources,
            )
            return CacheStore.loadAfterCompile(hash, script.config.kotlinOptions)
        }

        DepsClasspathStore.load(depsHash)?.let { return compileWithCp(it) }

        synchronized(lock) {
            CacheStore.load(hash, script.config.kotlinOptions)?.let { return it }
            DepsClasspathStore.load(depsHash)?.let { return compileWithCp(it) }

            val classpath = DependencyResolver.resolveClasspath(
                dependencies = script.config.dependencies,
                repositories = script.config.repositories,
            )
            DepsClasspathStore.save(depsHash, classpath)
            return compileWithCp(classpath)
        }
    }

    private fun entryPointOf(script: ResolvedScript): String =
        script.config.entryPoint
            ?: if (script.kind == io.kscriptx.model.ScriptKind.KT) guessKtEntry(script) else "ScriptKt"

    private fun guessKtEntry(script: ResolvedScript): String {
        val content = script.sources.firstOrNull()?.content.orEmpty()
        val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)
        val base = script.displayName.removeSuffix(".kt") + "Kt"
        return if (pkg != null) "$pkg.$base" else base
    }
}
