package io.kscriptx.compile

import io.kscriptx.model.CompiledScript
import io.kscriptx.model.ResolvedScript
import io.kscriptx.model.ScriptConfig
import io.kscriptx.model.ScriptKind
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompileBesideTest {
    @Test
    fun outputDirIsSiblingOfScript() {
        val dir = Files.createTempDirectory("ksx-beside-")
        val script = dir.resolve("hello.kts")
        script.writeText("println(1)")
        val resolved = ResolvedScript(
            displayName = "hello.kts",
            kind = ScriptKind.KTS,
            rootFile = script,
            sources = emptyList(),
            config = ScriptConfig(),
            scriptArgs = emptyList(),
            rawHashMaterial = "x",
        )
        assertEquals(dir.resolve("hello.kscriptx"), CompileBeside.outputDir(resolved))
    }

    @Test
    fun materializeCopiesClassesAndSidecars() {
        val dir = Files.createTempDirectory("ksx-beside-mat-")
        val script = dir.resolve("demo.kts")
        script.writeText("println(1)")
        val cacheClasses = dir.resolve("cache-classes").createDirectories()
        (cacheClasses / "Demo.class").writeText("fake-class")

        val compiled = CompiledScript(
            hash = "abc",
            classesDir = cacheClasses,
            classpath = "/deps/a.jar",
            entryPoint = "ScriptKt",
            kotlinOptions = emptyList(),
        )
        val resolved = ResolvedScript(
            displayName = "demo.kts",
            kind = ScriptKind.KTS,
            rootFile = script,
            sources = emptyList(),
            config = ScriptConfig(),
            scriptArgs = emptyList(),
            rawHashMaterial = "x",
        )

        val out = CompileBeside.materialize(resolved, compiled)
        assertEquals(dir.resolve("demo.kscriptx"), out)
        assertTrue((out / "ok").exists())
        assertEquals("ScriptKt", (out / "entry").readText())
        assertEquals("/deps/a.jar", (out / "classpath").readText())
        assertEquals("fake-class", (out / "classes" / "Demo.class").readText())
    }

    @Test
    fun requiresFileScript() {
        val resolved = ResolvedScript(
            displayName = "inline",
            kind = ScriptKind.INLINE,
            rootFile = null,
            sources = emptyList(),
            config = ScriptConfig(),
            scriptArgs = emptyList(),
            rawHashMaterial = "x",
        )
        assertFailsWith<IllegalStateException> {
            CompileBeside.outputDir(resolved)
        }
    }

    @Test
    fun maybeMaterializeNoopWhenDisabled() {
        val dir = Files.createTempDirectory("ksx-beside-off-")
        val script = dir.resolve("x.kts")
        script.writeText("1")
        val classes = dir.resolve("c").createDirectories()
        val compiled = CompiledScript("h", classes, "", "ScriptKt", emptyList())
        val resolved = ResolvedScript(
            displayName = "x.kts",
            kind = ScriptKind.KTS,
            rootFile = script,
            sources = emptyList(),
            config = ScriptConfig(),
            scriptArgs = emptyList(),
            rawHashMaterial = "x",
        )
        assertEquals(null, CompileBeside.maybeMaterialize(false, resolved, compiled, announce = false))
        assertTrue(!dir.resolve("x.kscriptx").exists())
    }
}
