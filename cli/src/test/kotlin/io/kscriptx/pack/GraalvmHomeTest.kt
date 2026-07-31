package io.kscriptx.pack

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraalvmHomeTest {
    @Test
    fun versionKeyStripsGraalSuffix() {
        assertEquals("25.0.2", GraalvmHome.versionKey("25.0.2-graalce"))
        assertEquals("21.0.2", GraalvmHome.versionKey("21.0.2-graal"))
    }

    @Test
    fun newestSdkmanGraalPicksHighestVersion() {
        val root = Files.createTempDirectory("ksx-sdkman-java-")
        fun fakeGraal(name: String) {
            val home = root.resolve(name).createDirectories()
            val bin = (home / "bin").createDirectories()
            val ni = bin / "native-image"
            ni.writeText("#!/bin/sh\n")
            ni.toFile().setExecutable(true)
        }
        fakeGraal("21.0.2-graalce")
        fakeGraal("25.0.2-graalce")
        fakeGraal("17.0.9-tem") // non-graal ignored
        val newest = GraalvmHome.newestSdkmanGraal(root)
        assertEquals(root.resolve("25.0.2-graalce"), newest)
    }

    @Test
    fun explicitHomeMustContainNativeImage() {
        val home = Files.createTempDirectory("ksx-not-graal-")
        val err = assertFailsWith<IllegalArgumentException> {
            GraalvmHome.resolve(home.toString())
        }
        assertTrue(err.message!!.contains("native-image"))
    }

    @Test
    fun hasNativeImageDetectsUnixBinary() {
        val home = Files.createTempDirectory("ksx-graal-ok-")
        val bin = (home / "bin").createDirectories()
        val ni = bin / "native-image"
        ni.writeText("#!/bin/sh\n")
        ni.toFile().setExecutable(true)
        assertTrue(GraalvmHome.hasNativeImage(home))
        assertNull(GraalvmHome.newestSdkmanGraal(Files.createTempDirectory("ksx-empty-sdk-")))
    }
}
