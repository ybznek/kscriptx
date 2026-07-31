package io.kscriptx.pack

import io.kscriptx.model.ResolvedScript
import io.kscriptx.model.ScriptConfig
import io.kscriptx.model.ScriptKind
import java.nio.file.Files
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageBuilderPathsTest {
    @Test
    fun primaryJarPathBesideScript() {
        val dir = Files.createTempDirectory("ksx-jar-")
        val script = dir.resolve("hello.kts")
        Files.writeString(script, "println(1)")
        val resolved = ResolvedScript(
            displayName = "hello.kts",
            kind = ScriptKind.KTS,
            rootFile = script,
            sources = emptyList(),
            config = ScriptConfig(),
            scriptArgs = emptyList(),
            rawHashMaterial = "",
        )
        assertEquals(dir / "hello.jar", PackageBuilder.primaryJarPath(resolved))
        assertEquals(dir / "hello.kscriptx" / "hello.jar", PackageBuilder.compileBesideJarPath(resolved))
    }
}
