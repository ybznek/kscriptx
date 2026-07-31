package io.kscriptx.pack

import io.kscriptx.ExecutionContext
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Locate a ProGuard install / jar for `--proguard`.
 *
 * Preference:
 * 1. Explicit jar (`--proguard-jar`)
 * 2. Explicit home (`--proguard-home` → `lib/proguard.jar` or `proguard.jar`)
 * 3. `PROGUARD_JAR`
 * 4. `PROGUARD_HOME`
 * 5. `proguard` / `proguard.sh` on `PATH` (derive jar beside the script)
 */
object ProguardHome {
    data class Resolved(
        /** Directory used for messaging / cwd hints; may equal jar parent. */
        val home: Path?,
        val jar: Path,
    )

    fun resolve(
        explicitJar: String? = null,
        explicitHome: String? = null,
    ): Resolved {
        explicitJar?.trim()?.takeIf { it.isNotEmpty() }?.let { jarPath ->
            val jar = Path(jarPath)
            require(isProguardJar(jar)) {
                "No ProGuard jar at --proguard-jar=$jarPath"
            }
            return Resolved(home = jar.parent, jar = jar)
        }

        explicitHome?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            val p = Path(home)
            val jar = findJarUnderHome(p)
                ?: error("No proguard.jar under --proguard-home=$home (expected $home/lib/proguard.jar)")
            return Resolved(home = p, jar = jar)
        }

        ExecutionContext.getenv("PROGUARD_JAR")?.trim()?.takeIf { it.isNotEmpty() }?.let { jarPath ->
            val jar = Path(jarPath)
            require(isProguardJar(jar)) {
                "PROGUARD_JAR=$jarPath is not a readable ProGuard jar"
            }
            return Resolved(home = jar.parent, jar = jar)
        }

        ExecutionContext.getenv("PROGUARD_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            val p = Path(home)
            val jar = findJarUnderHome(p)
                ?: error("PROGUARD_HOME=$home does not contain lib/proguard.jar")
            return Resolved(home = p, jar = jar)
        }

        findProguardOnPath()?.let { return it }

        error(
            """
            |proguard not found (required for --proguard).
            |Install ProGuard, then either:
            |  export PROGUARD_HOME=/path/to/proguard
            |  kscriptx --proguard --proguard-home=/path/to/proguard <script>
            |  kscriptx --proguard --proguard-jar=/path/to/proguard.jar <script>
            |  # or ensure proguard / proguard.sh is on PATH next to its jar
            """.trimMargin()
        )
    }

    fun findJarUnderHome(home: Path): Path? {
        if (!home.exists()) return null
        val candidates = listOf(
            home / "lib" / "proguard.jar",
            home / "lib" / "proguard-base.jar",
            home / "proguard.jar",
        )
        return candidates.firstOrNull { isProguardJar(it) }
    }

    fun isProguardJar(path: Path): Boolean =
        path.exists() && path.isRegularFile() && path.fileName.toString().endsWith(".jar", ignoreCase = true)

    internal fun findProguardOnPath(
        pathEnv: String? = ExecutionContext.getenv("PATH") ?: System.getenv("PATH"),
    ): Resolved? {
        if (pathEnv.isNullOrBlank()) return null
        val sep = if (isWindows()) ';' else ':'
        val names = if (isWindows()) {
            listOf("proguard.bat", "proguard.cmd", "proguard.sh", "proguard")
        } else {
            listOf("proguard.sh", "proguard")
        }
        for (dir in pathEnv.split(sep)) {
            if (dir.isBlank()) continue
            val dirPath = Path(dir)
            for (name in names) {
                val cand = dirPath / name
                if (!cand.exists() || (!cand.isRegularFile() && !cand.isExecutable())) continue
                // Official layouts: bin/proguard.sh with ../lib/proguard.jar
                findJarUnderHome(dirPath)?.let { return Resolved(home = dirPath, jar = it) }
                findJarUnderHome(dirPath.parent ?: continue)?.let {
                    return Resolved(home = dirPath.parent, jar = it)
                }
            }
            // Bare jar on PATH directory
            findJarUnderHome(dirPath)?.let { return Resolved(home = dirPath, jar = it) }
        }
        return null
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
}
