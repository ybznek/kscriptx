package io.kscriptx.resolve

import io.kscriptx.KPaths
import io.kscriptx.KscriptVersions
import io.kscriptx.model.Repository as ScriptRepository
import java.io.File
import java.net.URLClassLoader
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
 * Resolve Maven dependencies without Gradle.
 * Coursier lives in `lib-resolve/` and is loaded only on cache miss (keeps warm starts lean).
 *
 * Artifact sharing with the default Gradle/Maven layout:
 * - **Read** from `~/.gradle/caches/modules-2` (seed) and `~/.m2/repository`
 * - **Write** downloads into `~/.m2/repository` so Gradle `mavenLocal()` / Maven see them
 * - Optional private mirror under `$KSCRIPTX_DIRECTORY/m2` (still seeded / written)
 */
object DependencyResolver {
    @Volatile private var resolveCl: ClassLoader? = null

    /** Shared Maven local used by Maven and Gradle `mavenLocal()`. */
    fun userMavenLocal(): Path {
        System.getenv("KSCRIPTX_M2")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return Path.of(it)
        }
        System.getenv("MAVEN_USER_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return Path.of(it) / "repository"
        }
        return Path.of(System.getProperty("user.home")) / ".m2" / "repository"
    }

    fun resolveClasspath(
        dependencies: List<String>,
        repositories: List<ScriptRepository>,
    ): String {
        val userDeps = dependencies.distinct()
        val gavs = (userDeps + "org.jetbrains.kotlin:kotlin-stdlib:${KscriptVersions.KOTLIN}")
            .distinct()
            .map { parseGav(it) }

        val userM2 = userMavenLocal().also { it.createDirectories() }
        val privateM2 = (KPaths.home / "m2").also { it.createDirectories() }

        // Prefer seeding into the shared ~/.m2 so Gradle can reuse what we find/download.
        for (gav in gavs) {
            GradleModules2Cache.seedIntoMavenLocal(userM2, gav)
            GradleModules2Cache.seedIntoMavenLocal(privateM2, gav)
        }

        // Fast path ONLY when there are no user deps: stdlib has no script-relevant transitives.
        // Returning only direct GAV jars for real deps would omit Coursier’s transitive closure
        // and poison DepsClasspathStore — so non-empty deps always go through Coursier
        // (which still prefers local m2 / modules-2 via repo list, often with zero downloads).
        if (userDeps.isEmpty()) {
            classpathFromLocalM2(userM2, gavs)?.let { return it }
            classpathFromLocalM2(privateM2, gavs)?.let { return it }
        }

        val cacheDir = (KPaths.home / "coursier-cache").also { it.createDirectories() }.toFile()
        val resolved = fetchWithCoursier(gavs, repositories, userM2.toFile(), privateM2.toFile(), cacheDir)
        // Mirror Coursier results into Maven layout for Gradle/Maven sharing.
        installResolvedIntoMavenLocal(userM2, gavs, resolved)
        installResolvedIntoMavenLocal(privateM2, gavs, resolved)
        return resolved
    }

    /** Build classpath from a Maven-layout repo when every listed GAV jar is present. */
    private fun classpathFromLocalM2(localM2: Path, gavs: List<GradleModules2Cache.Gav>): String? {
        val jars = ArrayList<String>(gavs.size)
        for (gav in gavs) {
            val jar = mavenArtifact(localM2, gav, "jar")
            if (!jar.isRegularFile()) return null
            jars.add(jar.toAbsolutePath().normalize().toString())
        }
        return jars.joinToString(File.pathSeparator)
    }

    private fun mavenArtifact(root: Path, gav: GradleModules2Cache.Gav, ext: String): Path =
        root / gav.group.replace('.', '/') / gav.name / gav.version / "${gav.name}-${gav.version}.$ext"

    /**
     * Best-effort: copy jars that match our GAVs into [m2] Maven layout.
     * Coursier may return transitive jars too; we only place the requested GAVs by filename match.
     */
    private fun installResolvedIntoMavenLocal(
        m2: Path,
        gavs: List<GradleModules2Cache.Gav>,
        classpath: String,
    ) {
        val files = classpath.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
        for (gav in gavs) {
            val dest = mavenArtifact(m2, gav, "jar")
            if (dest.isRegularFile()) continue
            val expected = "${gav.name}-${gav.version}.jar"
            val src = files.firstOrNull { it.name == expected && it.isFile } ?: continue
            try {
                dest.parent.createDirectories()
                Files.copy(src.toPath(), dest, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchWithCoursier(
        gavs: List<GradleModules2Cache.Gav>,
        repositories: List<ScriptRepository>,
        userM2: File,
        privateM2: File,
        cacheDir: File,
    ): String {
        val cl = coursierClassLoader()
        val cacheCls = Class.forName("coursierapi.Cache", true, cl)
        val cache = cacheCls.getMethod("create").invoke(null)
        cacheCls.getMethod("withLocation", File::class.java).invoke(cache, cacheDir)

        val mavenRepoCls = Class.forName("coursierapi.MavenRepository", true, cl)
        val credentialsCls = Class.forName("coursierapi.Credentials", true, cl)
        val ofUrl = mavenRepoCls.getMethod("of", String::class.java)
        val withCreds = mavenRepoCls.getMethod("withCredentials", credentialsCls)
        val credsOf = credentialsCls.getMethod("of", String::class.java, String::class.java)

        val repos = ArrayList<Any>()
        // Shared Maven local first (Gradle mavenLocal / mvn), then private mirror.
        if (userM2.isDirectory) repos.add(ofUrl.invoke(null, userM2.toURI().toString()))
        if (privateM2.isDirectory) repos.add(ofUrl.invoke(null, privateM2.toURI().toString()))
        for (r in repositories) {
            var maven = ofUrl.invoke(null, r.url)
            if (!r.user.isNullOrBlank()) {
                val creds = credsOf.invoke(null, r.user, r.password ?: "")
                maven = withCreds.invoke(maven, creds)
            }
            repos.add(maven)
        }
        repos.add(ofUrl.invoke(null, "https://repo1.maven.org/maven2"))

        val depCls = Class.forName("coursierapi.Dependency", true, cl)
        val depOf = depCls.getMethod("of", String::class.java, String::class.java, String::class.java)
        val deps = gavs.map { depOf.invoke(null, it.group, it.name, it.version) }.toTypedArray()

        val fetchCls = Class.forName("coursierapi.Fetch", true, cl)
        var fetch = fetchCls.getMethod("create").invoke(null)
        fetch = fetchCls.getMethod("withCache", cacheCls).invoke(fetch, cache)
        val repoArray = java.lang.reflect.Array.newInstance(
            Class.forName("coursierapi.Repository", true, cl),
            repos.size,
        )
        repos.forEachIndexed { i, r -> java.lang.reflect.Array.set(repoArray, i, r) }
        fetch = fetchCls.methods.first { it.name == "addRepositories" && it.parameterCount == 1 }
            .invoke(fetch, repoArray)
        val depArray = java.lang.reflect.Array.newInstance(depCls, deps.size)
        deps.forEachIndexed { i, d -> java.lang.reflect.Array.set(depArray, i, d) }
        fetch = fetchCls.methods.first { it.name == "addDependencies" && it.parameterCount == 1 }
            .invoke(fetch, depArray)

        @Suppress("UNCHECKED_CAST")
        val files = fetchCls.getMethod("fetch").invoke(fetch) as List<File>
        return files.joinToString(File.pathSeparator) { it.absolutePath }
    }

    private fun coursierClassLoader(): ClassLoader {
        resolveCl?.let { return it }
        synchronized(this) {
            resolveCl?.let { return it }
            val urls = resolveJarUrls()
            val cl = URLClassLoader(urls, DependencyResolver::class.java.classLoader)
            resolveCl = cl
            return cl
        }
    }

    private fun resolveJarUrls(): Array<java.net.URL> {
        val dirs = candidateResolveDirs()
        val jars = dirs.flatMap { dir ->
            if (!dir.exists() || !dir.isDirectory()) emptyList()
            else dir.listDirectoryEntries("*.jar")
        }
        require(jars.isNotEmpty()) {
            "Coursier jars not found under lib-resolve/. Looked in:\n" +
                dirs.joinToString("\n") { "  - $it" } +
                "\nRebuild with: ./gradlew :cli:build"
        }
        return jars.map { it.toUri().toURL() }.toTypedArray()
    }

    private fun candidateResolveDirs(): List<Path> {
        val out = ArrayList<Path>()
        try {
            val loc = DependencyResolver::class.java.protectionDomain?.codeSource?.location
            if (loc != null) {
                val jarDir = Path.of(loc.toURI()).parent
                out.add(jarDir / "lib-resolve")
                out.add(jarDir.parent / "lib-resolve")
            }
        } catch (_: Exception) {
        }
        out.add(Path.of("bin") / "lib-resolve")
        return out
    }

    private fun parseGav(coord: String): GradleModules2Cache.Gav {
        val parts = coord.split(':')
        require(parts.size == 3) {
            "Invalid Maven coordinate (want group:artifact:version): $coord"
        }
        return GradleModules2Cache.Gav(parts[0], parts[1], parts[2])
    }
}
