package io.kscriptx.compile

import io.kscriptx.KPaths
import io.kscriptx.daemon.Daemon
import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import io.kscriptx.util.Hasher
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText

object CacheStore {
    fun hashOf(script: ResolvedScript): String = Hasher.md5(script.rawHashMaterial)

    fun load(hash: String, kotlinOptions: List<String>): CompiledScript? {
        val dir = KPaths.cache / hash
        val ok = dir / "ok"
        val classes = dir / "classes"
        // Hot path: ok + classes dir is enough to trust the entry; read sidecar files once.
        if (!ok.exists() || !classes.exists()) return null
        val cp = dir / "classpath"
        val entry = dir / "entry"
        if (!cp.exists() || !entry.exists()) return null
        return CompiledScript(
            hash = hash,
            classesDir = classes,
            classpath = cp.readText(),
            entryPoint = entry.readText().trim(),
            kotlinOptions = kotlinOptions,
        )
    }

    fun loadAfterCompile(hash: String, kotlinOptions: List<String>): CompiledScript =
        load(hash, kotlinOptions) ?: error("Compile did not produce cache entry $hash")

    fun clear() {
        FastCache.clearMemory()
        Daemon.shutdownRunning()
        if (KPaths.cache.exists()) KPaths.cache.toFile().deleteRecursively()
        if (KPaths.urlCache.exists()) KPaths.urlCache.toFile().deleteRecursively()
        val fast = KPaths.home / "fast-cache"
        if (fast.exists()) fast.toFile().deleteRecursively()
        DepsClasspathStore.clear()
        val staleDaemon = KPaths.home / "daemon"
        if (staleDaemon.exists()) staleDaemon.toFile().deleteRecursively()
        KPaths.ensureLayout()
    }
}
