package io.kscriptx.compile

import io.kscriptx.KPaths
import io.kscriptx.config.UserConfig
import io.kscriptx.util.Hasher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Skip full parse/hash when file mtimes/sizes still match a previous successful run.
 */
object FastCache {
    private val importRe = Regex("""@file\s*:\s*Import\s*\(\s*"([^"]+)"\s*\)""")

    data class Probe(
        val contentHash: String,
        val kotlinOptions: List<String>,
    )

    fun probeFileScript(
        scriptPath: Path,
        textMode: Boolean,
        userConfig: UserConfig,
    ): Probe? {
        if (!scriptPath.isRegularFile()) return null
        val abs = scriptPath.toAbsolutePath().normalize()
        val meta = KPaths.home / "fast-cache" / Hasher.md5(abs.toString())
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
            val configStamp = lines[3]
            if (savedTextMode != textMode) return null
            if (configStamp != configStampOf(userConfig)) return null
            val n = lines[4].toInt()
            if (lines.size < 5 + n) return null
            for (i in 0 until n) {
                val parts = lines[5 + i].split('|', limit = 3)
                if (parts.size != 3) return null
                val p = Path.of(parts[0])
                val size = parts[1].toLong()
                val mtime = parts[2].toLong()
                if (!p.isRegularFile()) return null
                if (Files.size(p) != size) return null
                if (p.getLastModifiedTime().toMillis() != mtime) return null
            }
            if (CacheStore.load(contentHash, kotlinOptions) == null) return null
            Probe(contentHash, kotlinOptions)
        } catch (_: Exception) {
            null
        }
    }

    fun remember(
        scriptPath: Path,
        textMode: Boolean,
        userConfig: UserConfig,
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
        val body = buildString {
            appendLine(contentHash)
            appendLine(kotlinOptions.joinToString("\u0001"))
            appendLine(textMode)
            appendLine(configStampOf(userConfig))
            appendLine(files.size)
            for (f in files) {
                append(f)
                append('|')
                append(Files.size(f))
                append('|')
                append(f.getLastModifiedTime().toMillis())
                append('\n')
            }
        }
        meta.writeText(body)
    }

    private fun configStampOf(cfg: UserConfig): String = Hasher.md5(
        buildString {
            append(cfg.preamble)
            append('\n')
            append(cfg.kotlinOpts.joinToString(" "))
            append('\n')
            append(cfg.repositoryUrl.orEmpty())
            append(cfg.repositoryUser.orEmpty())
            append(cfg.repositoryPassword.orEmpty())
        }
    )
}
