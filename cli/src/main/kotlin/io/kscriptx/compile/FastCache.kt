package io.kscriptx.compile

import io.kscriptx.ExecutionContext
import io.kscriptx.KPaths
import io.kscriptx.KscriptVersions
import io.kscriptx.VERSION
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
 *
 * Disk meta format (v3):
 * ```
 * v3
 * contentHash
 * kotlinOpts joined by \u0001
 * textMode
 * configStamp
 * envStamp      (values of {{VAR}} placeholders in script/import files)
 * classpath
 * entryPoint
 * N
 * path|size|mtime   (N lines; first is root script)
 * ```
 */
object FastCache {
    private const val META_VERSION = "v3"

    private data class FileStamp(val path: Path, val size: Long, val mtime: Long)
    private data class HotEntry(
        val textMode: Boolean,
        val configStamp: String,
        val envStamp: String,
        val files: List<FileStamp>,
        val compiled: CompiledScript,
    )

    private val hot = ConcurrentHashMap<String, HotEntry>()

    fun clearMemory() {
        hot.clear()
    }

    fun probeFileScript(
        scriptPath: Path,
        textMode: Boolean,
    ): CompiledScript? {
        if (!scriptPath.isRegularFile()) return null
        val abs = scriptPath.toAbsolutePath().normalize()
        val key = abs.toString()
        val cfg = configStamp()
        val env = envStampFor(abs)

        hot[key]?.let { entry ->
            if (entry.textMode == textMode && entry.configStamp == cfg &&
                entry.envStamp == env && filesMatch(entry.files)
            ) {
                return entry.compiled
            }
            hot.remove(key)
        }

        val meta = KPaths.home / "fast-cache" / Hasher.md5(key)
        if (!meta.exists()) return null
        return try {
            val lines = meta.readText().lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty() || lines[0] != META_VERSION) return null
            if (lines.size < 9) return null
            val contentHash = lines[1]
            val kotlinOptions = lines[2].split('\u0001').filter { it.isNotBlank() }
            val savedTextMode = lines[3].toBooleanStrict()
            val savedCfg = lines[4]
            val savedEnv = lines[5]
            if (savedTextMode != textMode) return null
            if (savedCfg != cfg) return null
            if (savedEnv != env) return null
            val classpath = lines[6]
            val entryPoint = lines[7]
            val n = lines[8].toInt()
            if (lines.size < 9 + n) return null
            val files = ArrayList<FileStamp>(n)
            for (i in 0 until n) {
                val parts = lines[9 + i].split('|', limit = 3)
                if (parts.size != 3) return null
                val p = Path.of(parts[0])
                val size = parts[1].toLong()
                val mtime = parts[2].toLong()
                val attrs = attrsOrNull(p) ?: return null
                if (attrs.size() != size) return null
                if (attrs.lastModifiedTime().toMillis() != mtime) return null
                files.add(FileStamp(p, size, mtime))
            }
            val compiled = CompiledScript(
                hash = contentHash,
                classesDir = KPaths.cache / contentHash / "classes",
                classpath = classpath,
                entryPoint = entryPoint,
                kotlinOptions = kotlinOptions,
            )
            if (!compiled.classesDir.exists()) return null
            if (!(KPaths.cache / contentHash / "ok").exists()) return null
            hot[key] = HotEntry(textMode, cfg, env, files, compiled)
            compiled
        } catch (_: Exception) {
            null
        }
    }

    fun remember(
        scriptPath: Path,
        textMode: Boolean,
        compiled: CompiledScript,
        importOrigins: List<Path>,
    ) {
        val abs = scriptPath.toAbsolutePath().normalize()
        val files = LinkedHashSet<Path>()
        files.add(abs)
        for (o in importOrigins) {
            files.add(o.toAbsolutePath().normalize())
        }

        val dir = KPaths.home / "fast-cache"
        dir.createDirectories()
        val meta = dir / Hasher.md5(abs.toString())
        val cfg = configStamp()
        val env = ExecutionContext.envStampForFiles(files)
        val stamps = ArrayList<FileStamp>(files.size)
        val body = buildString {
            appendLine(META_VERSION)
            appendLine(compiled.hash)
            appendLine(compiled.kotlinOptions.joinToString("\u0001"))
            appendLine(textMode)
            appendLine(cfg)
            appendLine(env)
            appendLine(compiled.classpath)
            appendLine(compiled.entryPoint)
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
        hot[abs.toString()] = HotEntry(textMode, cfg, env, stamps, compiled)
    }

    fun configStamp(): String {
        val ver = "$VERSION:${KscriptVersions.KOTLIN}"
        val path = KPaths.configFile
        if (!path.exists()) return "$ver:-"
        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            "$ver:${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
        } catch (_: Exception) {
            "$ver:-"
        }
    }

    private fun envStampFor(root: Path): String {
        val meta = KPaths.home / "fast-cache" / Hasher.md5(root.toString())
        if (meta.exists()) {
            try {
                val lines = meta.readText().lineSequence().filter { it.isNotBlank() }.toList()
                if (lines.size >= 9 && lines[0] == META_VERSION) {
                    val n = lines[8].toInt()
                    if (lines.size >= 9 + n) {
                        val paths = (0 until n).map { i ->
                            Path.of(lines[9 + i].substringBefore('|'))
                        }
                        return ExecutionContext.envStampForFiles(paths)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ExecutionContext.envStampForFiles(listOf(root))
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
