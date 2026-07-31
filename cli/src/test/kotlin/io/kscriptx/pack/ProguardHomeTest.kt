package io.kscriptx.pack

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProguardHomeTest {
    @Test
    fun explicitJarMustExist() {
        val missing = Files.createTempDirectory("ksx-pg-").resolve("nope.jar")
        val err = assertFailsWith<IllegalArgumentException> {
            ProguardHome.resolve(explicitJar = missing.toString())
        }
        assertTrue(err.message!!.contains("proguard-jar"), err.message)
    }

    @Test
    fun explicitHomeMustContainJar() {
        val home = Files.createTempDirectory("ksx-pg-home-")
        val err = assertFailsWith<IllegalStateException> {
            ProguardHome.resolve(explicitHome = home.toString())
        }
        assertTrue(err.message!!.contains("proguard-home"), err.message)
    }

    @Test
    fun findJarUnderHomePrefersLib() {
        val home = Files.createTempDirectory("ksx-pg-ok-")
        val lib = (home / "lib").createDirectories()
        val jar = lib / "proguard.jar"
        jar.writeText("fake")
        assertEquals(jar, ProguardHome.findJarUnderHome(home))
    }

    @Test
    fun resolveExplicitJar() {
        val home = Files.createTempDirectory("ksx-pg-jar-")
        val jar = home / "proguard.jar"
        jar.writeText("fake")
        val r = ProguardHome.resolve(explicitJar = jar.toString())
        assertEquals(jar, r.jar)
    }

    @Test
    fun findProguardOnPathBesideScript() {
        val root = Files.createTempDirectory("ksx-pg-path-")
        val bin = (root / "bin").createDirectories()
        val lib = (root / "lib").createDirectories()
        val jar = lib / "proguard.jar"
        jar.writeText("fake")
        val script = bin / "proguard.sh"
        script.writeText("#!/bin/sh\n")
        script.toFile().setExecutable(true)

        val found = ProguardHome.findProguardOnPath(bin.toString())
        assertNotNull(found)
        assertEquals(jar, found.jar)
    }

    @Test
    fun findProguardOnPathEmpty() {
        assertNull(ProguardHome.findProguardOnPath(""))
    }
}
