package io.kscriptx.cli

import io.kscriptx.model.RunMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgParserTest {
    @Test
    fun emptyArgsIsHelp() {
        val r = ArgParser.parse(emptyArray())
        assertEquals(RunMode.HELP, r.mode)
    }

    @Test
    fun versionAndHelpFlags() {
        assertEquals(RunMode.VERSION, ArgParser.parse(arrayOf("-v")).mode)
        assertEquals(RunMode.HELP, ArgParser.parse(arrayOf("--help")).mode)
    }

    @Test
    fun runScriptWithArgs() {
        val r = ArgParser.parse(arrayOf("hello.kts", "a", "b"))
        assertEquals(RunMode.RUN, r.mode)
        assertEquals("hello.kts", r.scriptSource)
        assertEquals(listOf("a", "b"), r.scriptArgs)
    }

    @Test
    fun textModeAndIdea() {
        val r = ArgParser.parse(arrayOf("-t", "--idea", "x.kts"))
        assertEquals(RunMode.IDEA, r.mode)
        assertTrue(r.textMode)
        assertEquals("x.kts", r.scriptSource)
    }

    @Test
    fun clearCacheHasNoScript() {
        val r = ArgParser.parse(arrayOf("--clear-cache"))
        assertEquals(RunMode.CLEAR_CACHE, r.mode)
        assertNull(r.scriptSource)
    }

    @Test
    fun helpTextMentionsNative() {
        assertTrue(ArgParser.helpText().contains("native kotlinc"))
    }
}
