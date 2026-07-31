package io.kscriptx.compile

import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Mirror content-cache compile artifacts beside the script file.
 *
 * Layout:
 * ```
 * <scriptDir>/<stem>.kscriptx/
 *   classes/     .class files (same as ~/.kscriptx/cache/<hash>/classes)
 *   classpath    dependency classpath text
 *   entry        main class name
 *   ok           sentinel ("1")
 * ```
 *
 * The global content-addressed cache remains authoritative; this is an extra
 * predictable sibling tree for inspection, packaging, or IDE tooling.
 */
object CompileBeside {
    fun outputDir(script: ResolvedScript): Path {
        val root = script.rootFile
            ?: error("--compile-beside requires a file script (not stdin/inline/URL-only)")
        val parent = root.parent
            ?: error("--compile-beside: script has no parent directory: $root")
        val stem = script.displayName.substringBeforeLast('.')
        return parent / "$stem.kscriptx"
    }

    /**
     * Copy [compiled] artifacts into [outputDir]. Returns the beside directory.
     */
    fun materialize(script: ResolvedScript, compiled: CompiledScript): Path {
        val out = outputDir(script)
        val classesOut = out / "classes"
        if (out.exists()) {
            out.toFile().deleteRecursively()
        }
        out.createDirectories()
        classesOut.createDirectories()
        copyDirectory(compiled.classesDir, classesOut)
        (out / "classpath").writeText(compiled.classpath)
        (out / "entry").writeText(compiled.entryPoint)
        (out / "ok").writeText("1")
        return out
    }

    fun maybeMaterialize(
        enabled: Boolean,
        script: ResolvedScript,
        compiled: CompiledScript,
        announce: Boolean = true,
    ): Path? {
        if (!enabled) return null
        val out = materialize(script, compiled)
        if (announce) {
            println("Compile artifacts: $out")
        }
        return out
    }

    private fun copyDirectory(from: Path, to: Path) {
        if (!from.exists()) return
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = to.resolve(from.relativize(dir).toString())
                target.createDirectories()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = to.resolve(from.relativize(file).toString())
                target.parent?.createDirectories()
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
