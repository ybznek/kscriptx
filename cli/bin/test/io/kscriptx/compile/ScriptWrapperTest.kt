package io.kscriptx.compile

import io.kscriptx.model.ResolvedScript
import io.kscriptx.model.ScriptConfig
import io.kscriptx.model.ScriptKind
import io.kscriptx.model.SourceUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class ScriptWrapperTest {
    @Test
    fun wrapsTopLevelKtsIntoMain() {
        val script = ResolvedScript(
            displayName = "x.kts",
            kind = ScriptKind.KTS,
            rootFile = null,
            sources = listOf(SourceUnit("x.kts", "println(1)", null)),
            config = ScriptConfig(),
            scriptArgs = emptyList(),
            rawHashMaterial = "x",
        )
        val out = ScriptWrapper.toCompilableSources(script).single()
        assertTrue(out.content.contains("fun main(args: Array<String>)"))
        assertTrue(out.content.contains("println(1)"))
    }
}
