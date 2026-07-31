package io.kscriptx.pack

import io.kscriptx.ExecutionContext
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Locate a GraalVM install that provides `native-image`.
 *
 * Preference (mirrors `scripts/resolve-graalvm.sh`, without SDKMAN auto-install):
 * 1. Explicit path (`--graalvm-home` / argument)
 * 2. `GRAALVM_HOME`
 * 3. Newest SDKMAN `*-graalce` / `*-graal` with `bin/native-image`
 * 4. `native-image` already on `PATH`
 */
object GraalvmHome {
    fun resolve(explicitHome: String? = null): Path {
        explicitHome?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            val p = Path(home)
            require(hasNativeImage(p)) {
                "No native-image under --graalvm-home=$home (expected $home/bin/native-image)"
            }
            return p
        }

        ExecutionContext.getenv("GRAALVM_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            val p = Path(home)
            require(hasNativeImage(p)) {
                "GRAALVM_HOME=$home does not contain bin/native-image"
            }
            return p
        }

        newestSdkmanGraal()?.let { return it }

        findNativeImageOnPath()?.let { ni ->
            return ni.parent?.parent
                ?: error("Cannot derive GraalVM home from native-image at $ni")
        }

        error(
            """
            |native-image not found (required for --native).
            |Install GraalVM CE with native-image, then either:
            |  export GRAALVM_HOME=/path/to/graalvm
            |  kscriptx --native --graalvm-home=/path/to/graalvm <script>
            |  # or ensure native-image is on PATH
            |See scripts/resolve-graalvm.sh / docs/sdkman.md.
            """.trimMargin()
        )
    }

    fun nativeImageBinary(home: Path): Path {
        val bin = if (isWindows()) home / "bin" / "native-image.cmd" else home / "bin" / "native-image"
        require(bin.exists() && (bin.isRegularFile() || isWindows())) {
            "native-image binary missing: $bin"
        }
        return bin
    }

    fun hasNativeImage(home: Path): Boolean {
        val unix = home / "bin" / "native-image"
        val win = home / "bin" / "native-image.cmd"
        return (unix.exists() && unix.isExecutable()) || win.exists()
    }

    internal fun newestSdkmanGraal(
        sdkmanJavaRoot: Path? = defaultSdkmanJavaRoot(),
    ): Path? {
        val root = sdkmanJavaRoot ?: return null
        if (!root.exists()) return null
        val candidates = root.toFile().listFiles()
            ?.filter { f ->
                f.isDirectory &&
                    (f.name.endsWith("-graalce") || f.name.endsWith("-graal")) &&
                    hasNativeImage(f.toPath())
            }
            .orEmpty()
        if (candidates.isEmpty()) return null
        return candidates
            .sortedWith(compareBy({ versionKey(it.name) }, { it.name }))
            .last()
            .toPath()
    }

    private fun defaultSdkmanJavaRoot(): Path? {
        val sdkmanDir = ExecutionContext.getenv("SDKMAN_DIR")
            ?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home")?.let { "$it/.sdkman" })
            ?: return null
        return Path(sdkmanDir) / "candidates" / "java"
    }

    private fun findNativeImageOnPath(): Path? {
        val path = ExecutionContext.getenv("PATH") ?: System.getenv("PATH") ?: return null
        val sep = if (isWindows()) ';' else ':'
        val names = if (isWindows()) listOf("native-image.cmd", "native-image.exe", "native-image")
        else listOf("native-image")
        for (dir in path.split(sep)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val cand = Path(dir) / name
                if (cand.exists() && cand.isRegularFile()) return cand
            }
        }
        return null
    }

    /** Sortable key from SDKMAN id (`25.0.2-graalce` → `25.0.2`). */
    internal fun versionKey(id: String): String {
        var base = id.substringAfterLast('/')
        if (base.endsWith("-graalce")) base = base.removeSuffix("-graalce")
        else if (base.endsWith("-graal")) base = base.removeSuffix("-graal")
        return base
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
}
