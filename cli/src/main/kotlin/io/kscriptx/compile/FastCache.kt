package io.kscriptx.compile

import io.kscriptx.KPaths
import io.kscriptx.config.UserConfig
import io.kscriptx.model.CompiledScript
import io.kscriptx.util.Hasher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Skip full parse/hash when file mtimes/sizes still match a previous successful run.
 * In-daemon, an in-memory hot entry avoids re-reading meta + CacheStore on every hit.
 */
object FastCache {
    private val importRe = Regex("""@file\s*:\s*Import\s*\(\s*"([^"]+)"\s*\)""")

    private data class FileStamp(val path: Path, val size: Long, val mtime: Long)
    private data class HotEntry(
        val textMode: Boolean,
        val configStamp: String,
        val files: List<FileStamp>,
        val compiled: CompiledScript,
    )

    private val hot = ConcurrentHashMap<String, HotEntry>()

    /**
     * Returns a ready [CompiledScript] on hit (single [CacheStore.load] on first hit,
     * then in-process reuse while mtimes match).
     */
    fun probeFileScript(
        scriptPath: Path,
        textMode: Boolean,
    ): CompiledScript? {
        if (!scriptPath.isRegularFile()) return null
        val abs = scriptPath.toAbsolutePath().normalize()
        val key = abs.toString()
        val cfg = configStamp()

        hot[key]?.let { entry ->
            if (entry.textMode == textMode && entry.configStamp == cfg && filesMatch(entry.files)) {
                return entry.compiled
            }
            hot.remove(key)
        }

        val meta = KPaths.home / "fast-cache" / Hasher.md5(key)
        if (!meta.exists()) return null
        return try {
            val lines = meta.readText().lineSequence().filter { it.isNotBlank() }.toList()
            // format:
            // contentHash
            // kotlinOpts joined by \u0001
            // textMode
            // configStamp
            // N
            // path|size|mtime   (N lines; first is root script)
            if (lines.size < 5) return null
            val contentHash = lines[0]
            val kotlinOptions = lines[1].split('\u0001').filter { it.isNotBlank() }
            val savedTextMode = lines[2].toBooleanStrict()
            val savedCfg = lines[3]
            if (savedTextMode != textMode) return null
            if (savedCfg != cfg) return null
            val n = lines[4].toInt()
            if (lines.size < 5 + n) return null
            val files = ArrayList<FileStamp>(n)
            for (i in 0 until n) {
                val parts = lines[5 + i].split('|', limit = 3)
                if (parts.size != 3) return null
                val p = Path.of(parts[0])
                val size = parts[1].toLong()
                val mtime = parts[2].toLong()
                val attrs = attrsOrNull(p) ?: return null
                if (attrs.size() != size) return null
                if (attrs.lastModifiedTime().toMillis() != mtime) return null
                files.add(FileStamp(p, size, mtime))
            }
            val compiled = CacheStore.load(contentHash, kotlinOptions) ?: return null
            hot[key] = HotEntry(textMode, cfg, files, compiled)
            compiled
        } catch (_: Exception) {
            null
        }
    }

    fun remember(
        scriptPath: Path,
        textMode: Boolean,
        @Suppress("UNUSED_PARAMETER") userConfig: UserConfig,
        contentHash: String,
        kotlinOptions: List<String>,
        importOrigins: List<Path>,
    ) {
        val abs = scriptPath.toAbsolutePath().normalize()
        val files = LinkedHashSet<Path>()
        files.add(abs)
        for (o in importOrigins) {
            files.add(o.toAbsolutePath().normalize())
        }
        try {
            val text = abs.readText()
            val base = abs.parent
            for (m in importRe.findAll(text)) {
                val spec = m.groupValues[1]
                if (spec.startsWith("http://", true) || spec.startsWith("https://", true)) continue
                val p = if (Path.of(spec).isAbsolute) Path.of(spec) else (base.resolve(spec)).normalize()
                if (p.isRegularFile()) files.add(p.toAbsolutePath().normalize())
            }
        } catch (_: Exception) {
        }

        val dir = KPaths.home / "fast-cache"
        dir.createDirectories()
        val meta = dir / Hasher.md5(abs.toString())
        val cfg = configStamp()
        val stamps = ArrayList<FileStamp>(files.size)
        val body = buildString {
            appendLine(contentHash)
            appendLine(kotlinOptions.joinToString("\u0001"))
            appendLine(textMode)
            appendLine(cfg)
            appendLine(files.size)
            for (f in files) {
                val attrs = Files.readAttributes(f, BasicFileAttributes::class.java)
                val size = attrs.size()
                val mtime = attrs.lastModifiedTime().toMillis()
                stamps.add(FileStamp(f, size, mtime))
                append(f)
                append('|')
                append(size)
                append('|')
                append(mtime)
                append('\n')
            }
        }
        meta.writeText(body)
        CacheStore.load(contentHash, kotlinOptions)?.let { compiled ->
            hot[abs.toString()] = HotEntry(textMode, cfg, stamps, compiled)
        }
    }

    /** Cheap stamp: missing config → "-", else size+mtime (avoids reading/parsing properties). */
    fun configStamp(): String {
        val path = KPaths.configFile
        if (!path.exists()) return "-"
        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            "${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
        } catch (_: Exception) {
            "-"
        }
    }

    private fun filesMatch(files: List<FileStamp>): Boolean {
        for (f in files) {
            val attrs = attrsOrNull(f.path) ?: return false
            if (attrs.size() != f.size) return false
            if (attrs.lastModifiedTime().toMillis() != f.mtime) return false
        }
        return true
    }

    private fun attrsOrNull(p: Path): BasicFileAttributes? = try {
        if (!p.isRegularFile()) null
        else Files.readAttributes(p, BasicFileAttributes::class.java)
    } catch (_: Exception) {
        null
    }
}
