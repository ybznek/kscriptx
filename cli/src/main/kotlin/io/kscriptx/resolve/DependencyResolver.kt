package io.kscriptx.resolve

import coursierapi.Cache
import coursierapi.Credentials
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.MavenRepository
import coursierapi.Repository
import io.kscriptx.KPaths
import io.kscriptx.KscriptVersions
import io.kscriptx.model.Repository as ScriptRepository
import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/**
 * Resolve Maven dependencies without Gradle, preferring jars already present in the
 * Gradle modules-2 cache (mirrored into a local Maven repo) then Coursier's own cache.
 */
object DependencyResolver {
    fun resolveClasspath(
        dependencies: List<String>,
        repositories: List<ScriptRepository>,
    ): String {
        val gavs = (dependencies + "org.jetbrains.kotlin:kotlin-stdlib:${KscriptVersions.KOTLIN}")
            .distinct()
            .map { parseGav(it) }

        val localM2 = KPaths.home / "m2"
        localM2.createDirectories()
        for (gav in gavs) {
            GradleModules2Cache.seedIntoMavenLocal(localM2, gav)
        }

        val cacheDir = (KPaths.home / "coursier-cache").also { it.createDirectories() }.toFile()
        val cache = Cache.create().withLocation(cacheDir)

        val repos = buildList<Repository> {
            add(MavenRepository.of(localM2.toUri().toString()))
            val userM2 = File(System.getProperty("user.home"), ".m2/repository")
            if (userM2.isDirectory) add(MavenRepository.of(userM2.toURI().toString()))
            for (r in repositories) {
                var maven = MavenRepository.of(r.url)
                if (!r.user.isNullOrBlank()) {
                    maven = maven.withCredentials(Credentials.of(r.user, r.password ?: ""))
                }
                add(maven)
            }
            add(MavenRepository.of("https://repo1.maven.org/maven2"))
        }

        val deps = gavs.map { Dependency.of(it.group, it.name, it.version) }
        return Fetch.create()
            .withCache(cache)
            .addRepositories(*repos.toTypedArray())
            .addDependencies(*deps.toTypedArray())
            .fetch()
            .joinToString(File.pathSeparator) { it.absolutePath }
    }

    private fun parseGav(coord: String): GradleModules2Cache.Gav {
        val parts = coord.split(':')
        require(parts.size >= 3) { "Invalid Maven coordinate (want g:a:v): $coord" }
        return GradleModules2Cache.Gav(parts[0], parts[1], parts[2])
    }
}
