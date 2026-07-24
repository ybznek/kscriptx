package io.kscriptx.resolve

import io.kscriptx.KPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Looks up artifacts already downloaded by Gradle under
 * ~/.gradle/caches/modules-2/files-2.1/...
 */
object GradleModules2Cache {
    private val modules2: Path
        get() {
            System.getenv("GRADLE_USER_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return Path.of(it) / "caches" / "modules-2" / "files-2.1"
            }
            return Path.of(System.getProperty("user.home")) / ".gradle" / "caches" / "modules-2" / "files-2.1"
        }

    data class Gav(val group: String, val name: String, val version: String)

    fun find(gav: Gav, extension: String): Path? {
        val dir = modules2 / gav.group / gav.name / gav.version
        if (!dir.exists() || !dir.isDirectory()) return null
        val expected = "${gav.name}-${gav.version}.$extension"
        return try {
            Files.walk(dir).use { stream ->
                stream.filter { it.isRegularFile() && it.fileName.toString() == expected }
                    .findFirst()
                    .orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Copy jar/pom from Gradle cache into a Maven-layout local repo if present. */
    fun seedIntoMavenLocal(localM2: Path, gav: Gav) {
        localM2.createDirectories()
        for (ext in listOf("pom", "jar")) {
            val src = find(gav, ext) ?: continue
            val destDir = localM2 / gav.group.replace('.', '/') / gav.name / gav.version
            destDir.createDirectories()
            val dest = destDir / "${gav.name}-${gav.version}.$ext"
            if (!dest.exists()) {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
