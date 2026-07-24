package io.kscriptx.resolve

import io.kscriptx.KPaths
import io.kscriptx.KscriptVersions
import io.kscriptx.model.Repository as ScriptRepository
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Resolve Maven dependencies without Gradle.
 * Coursier lives in `lib-resolve/` and is loaded only on cache miss (keeps warm starts lean).
 */
object DependencyResolver {
    @Volatile private var resolveCl: ClassLoader? = null

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
        return fetchWithCoursier(gavs, repositories, localM2.toFile(), cacheDir)
    }

    private fun fetchWithCoursier(
        gavs: List<GradleModules2Cache.Gav>,
        repositories: List<ScriptRepository>,
        localM2: File,
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
        repos.add(ofUrl.invoke(null, localM2.toURI().toString()))
        val userM2 = File(System.getProperty("user.home"), ".m2/repository")
        if (userM2.isDirectory) repos.add(ofUrl.invoke(null, userM2.toURI().toString()))
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
        require(parts.size >= 3) { "Invalid Maven coordinate (want g:a:v): $coord" }
        return GradleModules2Cache.Gav(parts[0], parts[1], parts[2])
    }
}
