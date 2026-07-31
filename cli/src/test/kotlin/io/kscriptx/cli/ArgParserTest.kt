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

    @Test
    fun noDaemonFlagIsStripped() {
        ArgParser.applyDaemonFlags(arrayOf("--no-daemon", "hello.kts"))
        assertEquals(false, io.kscriptx.daemon.Daemon.cliOverride)
        val r = ArgParser.parse(ArgParser.applyDaemonFlags(arrayOf("--no-daemon", "hello.kts")))
        assertEquals(RunMode.RUN, r.mode)
        assertEquals("hello.kts", r.scriptSource)
    }

    @Test
    fun daemonFlagForcesEnable() {
        ArgParser.applyDaemonFlags(arrayOf("--daemon", "hello.kts"))
        assertEquals(true, io.kscriptx.daemon.Daemon.cliOverride)
    }

    @Test
    fun compileBesideFlag() {
        val r = ArgParser.parse(arrayOf("--compile-beside", "hello.kts", "x"))
        assertEquals(RunMode.RUN, r.mode)
        assertEquals(true, r.compileBeside)
        assertEquals("hello.kts", r.scriptSource)
        assertEquals(listOf("x"), r.scriptArgs)
    }

    @Test
    fun nativeImpliesPackageMode() {
        val r = ArgParser.parse(arrayOf("--native", "hello.kts"))
        assertEquals(RunMode.PACKAGE, r.mode)
        assertEquals(true, r.nativeImage)
        assertEquals("hello.kts", r.scriptSource)
    }

    @Test
    fun packageAndNativeTogether() {
        val r = ArgParser.parse(arrayOf("--package", "--native", "--graalvm-home=/opt/graal", "a.kts"))
        assertEquals(RunMode.PACKAGE, r.mode)
        assertEquals(true, r.nativeImage)
        assertEquals(true, r.writeLauncher) // dual: smart launcher + .native
        assertEquals("/opt/graal", r.graalvmHome)
    }

    @Test
    fun graalvmHomeSeparateArg() {
        val r = ArgParser.parse(arrayOf("--native", "--graalvm-home", "/opt/g", "s.kts"))
        assertEquals("/opt/g", r.graalvmHome)
        assertEquals(true, r.nativeImage)
    }

    @Test
    fun helpTextMentionsCompileBesideAndNative() {
        val help = ArgParser.helpText()
        assertTrue(help.contains("--compile-beside"))
        assertTrue(help.contains("--native"))
        assertTrue(help.contains("--graalvm-home"))
        assertTrue(help.contains("--proguard"))
        assertTrue(help.contains("--proguard-home"))
        assertTrue(help.contains("--proguard-jar"))
        assertTrue(help.contains("--jar"))
        assertTrue(help.contains("--standalone-jar"))
    }

    @Test
    fun proguardImpliesPackageMode() {
        val r = ArgParser.parse(arrayOf("--proguard", "hello.kts"))
        assertEquals(RunMode.PACKAGE, r.mode)
        assertEquals(true, r.proguard)
        assertEquals(true, r.writeLauncher) // bare --proguard keeps launcher UX
        assertEquals("hello.kts", r.scriptSource)
    }

    @Test
    fun packageProguardAndNativeTogether() {
        val r = ArgParser.parse(
            arrayOf(
                "--package", "--proguard", "--native",
                "--proguard-home=/opt/pg",
                "--proguard-jar=/opt/pg/lib/proguard.jar",
                "--graalvm-home=/opt/graal",
                "a.kts",
            )
        )
        assertEquals(RunMode.PACKAGE, r.mode)
        assertEquals(true, r.proguard)
        assertEquals(true, r.nativeImage)
        assertEquals(true, r.writeLauncher) // dual smart launcher
        assertEquals("/opt/pg", r.proguardHome)
        assertEquals("/opt/pg/lib/proguard.jar", r.proguardJar)
        assertEquals("/opt/graal", r.graalvmHome)
    }

    @Test
    fun nativeSharedAndRunnerFlags() {
        val r = ArgParser.parse(
            arrayOf(
                "--native-shared",
                "--native-runner",
                "--native-config-dir=/tmp/cfg",
                "--native-image-arg=-O2",
                "--native-image-arg=--verbose",
                "a.kts",
            )
        )
        assertEquals(RunMode.PACKAGE, r.mode)
        assertEquals(true, r.nativeShared)
        assertEquals(true, r.nativeRunner)
        assertEquals("/tmp/cfg", r.nativeConfigDir)
        assertEquals(listOf("-O2", "--verbose"), r.nativeImageArgs)
    }

    @Test
    fun nativeRunnerImpliesShared() {
        val r = ArgParser.parse(arrayOf("--native-runner", "a.kts"))
        assertEquals(true, r.nativeShared)
        assertEquals(true, r.nativeRunner)
    }

    @Test
    fun helpMentionsSharedAndConfig() {
        val help = ArgParser.helpText()
        assertTrue(help.contains("--native-shared"))
        assertTrue(help.contains("--native-runner"))
        assertTrue(help.contains("--native-config-dir"))
        assertTrue(help.contains("--native-image-arg"))
        assertTrue(help.contains("Mode matrix"))
    }

    @Test
    fun proguardHomeSeparateArg() {
        val r = ArgParser.parse(arrayOf("--proguard", "--proguard-home", "/opt/pg", "s.kts"))
        assertEquals("/opt/pg", r.proguardHome)
        assertEquals(true, r.proguard)
    }

    @Test
    fun jarImpliesPackageModeWithoutLauncher() {
        val r = ArgParser.parse(arrayOf("--jar", "hello.kts"))
        assertEquals(RunMode.PACKAGE, r.mode)
        assertEquals(true, r.standaloneJar)
        assertEquals(false, r.writeLauncher)
    }

    @Test
    fun standaloneJarAlias() {
        val r = ArgParser.parse(arrayOf("--standalone-jar", "hello.kts"))
        assertEquals(true, r.standaloneJar)
        assertEquals(false, r.writeLauncher)
    }

    @Test
    fun packageWritesLauncher() {
        val r = ArgParser.parse(arrayOf("--package", "hello.kts"))
        assertEquals(true, r.writeLauncher)
        assertEquals(false, r.standaloneJar)
    }

    @Test
    fun jarWithPackageWritesLauncher() {
        val r = ArgParser.parse(arrayOf("--jar", "--package", "hello.kts"))
        assertEquals(true, r.standaloneJar)
        assertEquals(true, r.writeLauncher)
    }

    @Test
    fun jarWithProguardSkipsLauncher() {
        val r = ArgParser.parse(arrayOf("--jar", "--proguard", "hello.kts"))
        assertEquals(true, r.proguard)
        assertEquals(true, r.standaloneJar)
        assertEquals(false, r.writeLauncher)
    }
}
