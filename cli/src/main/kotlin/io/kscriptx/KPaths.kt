package io.kscriptx

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

object KPaths {
    val home: Path get() = resolveHome()
    val cache: Path get() = home / "cache"
    val depsCache: Path get() = home / "deps-cache"
    val urlCache: Path get() = home / "url-cache"
    val compiler: Path get() = home / "compiler"
    val idea: Path get() = home / "idea"
    val configFile: Path get() = resolveConfigFile()

    /** Hot-path dirs only (cache hits should not mkdir the whole tree). */
    fun ensureRuntimeLayout() {
        home.createDirectories()
        cache.createDirectories()
    }

    fun ensureLayout() {
        listOf(
            home, cache, depsCache, urlCache, compiler, idea,
            home / "m2", home / "coursier-cache",
        ).forEach { it.createDirectories() }
    }

    private fun resolveHome(): Path {
        overrideDirectory()?.let { return it }
        return when (osFamily()) {
            OsFamily.WINDOWS -> {
                val local = ExecutionContext.getenv("LOCALAPPDATA")
                if (!local.isNullOrBlank()) Path(local) / "kscriptx"
                else Path(System.getProperty("user.home")) / ".kscriptx"
            }
            OsFamily.MAC -> Path(System.getProperty("user.home")) / "Library" / "Application Support" / "kscriptx"
            OsFamily.LINUX -> {
                val xdg = ExecutionContext.getenv("XDG_CACHE_HOME")
                if (!xdg.isNullOrBlank()) Path(xdg) / "kscriptx"
                else Path(System.getProperty("user.home")) / ".kscriptx"
            }
        }
    }

    private fun resolveConfigFile(): Path {
        overrideDirectory()?.let { return it / "kscript.properties" }
        return when (osFamily()) {
            OsFamily.WINDOWS -> {
                val local = ExecutionContext.getenv("LOCALAPPDATA")
                if (!local.isNullOrBlank()) Path(local) / "kscriptx" / "kscript.properties"
                else Path(System.getProperty("user.home")) / ".config" / "kscriptx" / "kscript.properties"
            }
            OsFamily.MAC -> Path(System.getProperty("user.home")) /
                "Library" / "Application Support" / "kscriptx" / "kscript.properties"
            OsFamily.LINUX -> {
                val xdg = ExecutionContext.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) Path(xdg) / "kscriptx" / "kscript.properties"
                else Path(System.getProperty("user.home")) / ".config" / "kscriptx" / "kscript.properties"
            }
        }
    }

    private fun overrideDirectory(): Path? {
        ExecutionContext.getenv("KSCRIPTX_DIRECTORY")?.let { return Path(it) }
        ExecutionContext.getenv("KSCRIPT_DIRECTORY")?.let { return Path(it) }
        return null
    }

    private enum class OsFamily { WINDOWS, MAC, LINUX }

    private fun osFamily(): OsFamily {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> OsFamily.WINDOWS
            os.contains("mac") -> OsFamily.MAC
            else -> OsFamily.LINUX
        }
    }
}
