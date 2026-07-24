package io.kscriptx

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

object KPaths {
    val home: Path = resolveHome()
    val cache: Path = home / "cache"
    val depsCache: Path = home / "deps-cache"
    val urlCache: Path = home / "url-cache"
    val compiler: Path = home / "compiler"
    val idea: Path = home / "idea"
    val configFile: Path = resolveConfigFile()

    fun ensureLayout() {
        listOf(
            home, cache, depsCache, urlCache, compiler, idea,
            home / "m2", home / "coursier-cache",
        ).forEach { it.createDirectories() }
    }

    private fun resolveHome(): Path {
        System.getenv("KSCRIPTX_DIRECTORY")?.let { return Path(it) }
        System.getenv("KSCRIPT_DIRECTORY")?.let { return Path(it) }
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val local = System.getenv("LOCALAPPDATA")
                if (!local.isNullOrBlank()) Path(local) / "kscriptx"
                else Path(System.getProperty("user.home")) / ".kscriptx"
            }
            os.contains("mac") -> Path(System.getProperty("user.home")) / "Library" / "Application Support" / "kscriptx"
            else -> {
                val xdg = System.getenv("XDG_CACHE_HOME")
                if (!xdg.isNullOrBlank()) Path(xdg) / "kscriptx"
                else Path(System.getProperty("user.home")) / ".kscriptx"
            }
        }
    }

    private fun resolveConfigFile(): Path {
        System.getenv("KSCRIPTX_DIRECTORY")?.let { return Path(it) / "kscript.properties" }
        System.getenv("KSCRIPT_DIRECTORY")?.let { return Path(it) / "kscript.properties" }
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val local = System.getenv("LOCALAPPDATA")
                if (!local.isNullOrBlank()) Path(local) / "kscriptx" / "kscript.properties"
                else Path(System.getProperty("user.home")) / ".config" / "kscriptx" / "kscript.properties"
            }
            os.contains("mac") -> Path(System.getProperty("user.home")) / "Library" / "Application Support" / "kscriptx" / "kscript.properties"
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) Path(xdg) / "kscriptx" / "kscript.properties"
                else Path(System.getProperty("user.home")) / ".config" / "kscriptx" / "kscript.properties"
            }
        }
    }
}
