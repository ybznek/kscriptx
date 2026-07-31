package io.kscriptx.pack

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProguardOptimizerTest {
    @Test
    fun configKeepsMainAndKotlinMetadata() {
        val inJar = Path("/tmp/in.jar")
        val outJar = Path("/tmp/out.jar")
        val cfg = ProguardOptimizer.buildConfig(
            inputJar = inJar,
            outputJar = outJar,
            entryPoint = "ScriptKt",
            javaHome = Path(System.getProperty("java.home")),
        )
        assertTrue(cfg.contains("-keep public class ScriptKt"))
        assertTrue(cfg.contains("public static void main"))
        assertTrue(cfg.contains("kotlin.Metadata"))
        assertTrue(cfg.contains("META-INF/services"))
        assertTrue(cfg.contains("-optimizations !code/simplification/arithmetic"))
        assertTrue(cfg.contains("!class/merging/*"))
        assertFalse(cfg.contains("-overloadaggressively"))
        assertFalse(cfg.contains("-repackageclasses"))
    }

    @Test
    fun libraryJarDirectivesPreferJmodsWhenPresent() {
        val javaHome = Path(System.getProperty("java.home"))
        val lines = ProguardOptimizer.libraryJarDirectives(javaHome)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.startsWith("-libraryjars ") })
        val jmods = javaHome / "jmods"
        if (Files.isDirectory(jmods)) {
            assertTrue(lines.any { it.contains(".jmod") }, lines.joinToString("\n"))
        }
    }

    @Test
    fun optimizeJarFailsWhenProguardMissing() {
        val dir = Files.createTempDirectory("ksx-pg-opt-")
        val jar = dir.resolve("app.jar")
        jar.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) // minimal zip magic
        val err = assertFailsWith<IllegalStateException> {
            ProguardOptimizer.optimizeJar(
                inputJar = jar,
                entryPoint = "MainKt",
                explicitHome = dir.resolve("no-proguard").toString(),
                workDir = dir,
            )
        }
        assertTrue(
            err.message!!.contains("proguard", ignoreCase = true),
            err.message,
        )
    }
}
