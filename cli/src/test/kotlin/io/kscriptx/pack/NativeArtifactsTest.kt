package io.kscriptx.pack

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeArtifactsTest {
    @Test
    fun stemNameStripsExtension() {
        assertEquals("hello", NativeArtifacts.stemName("hello.kts"))
        assertEquals("App", NativeArtifacts.stemName("App.kt"))
    }

    @Test
    fun dualNativePathUsesNativeSuffix() {
        val dir = Files.createTempDirectory("kscriptx-native-art")
        val p = NativeArtifacts.dualNativePath(dir, "hello")
        assertTrue(p.fileName.toString().startsWith("hello.native"))
    }

    @Test
    fun writeSmartLauncherPrefersNative() {
        val dir = Files.createTempDirectory("kscriptx-launcher")
        val launcher = dir.resolve("hello")
        NativeArtifacts.writeSmartLauncher(launcher, "hello", "-Xmx64m")
        val text = launcher.toFile().readText()
        assertTrue(text.contains("hello.native"))
        assertTrue(text.contains("hello.jar"))
        assertTrue(text.contains("java"))
    }

    @Test
    fun sharedLibNameIsPlatformSpecific() {
        val name = NativeArtifacts.sharedLibName("demo")
        assertTrue(
            name == "libdemo.so" || name == "libdemo.dylib" || name == "demo.dll",
            "unexpected lib name: $name",
        )
    }
}

class NativeBridgeGeneratorTest {
    @Test
    fun bridgeMentionsEntryPointAndSymbols() {
        val src = NativeBridgeGenerator.bridgeJavaSource("com.example.MainKt")
        assertTrue(src.contains("com.example.MainKt"))
        assertTrue(src.contains("kscriptx_create_isolate"))
        assertTrue(src.contains("kscriptx_run"))
        assertTrue(src.contains("@CEntryPoint"))
    }

    @Test
    fun runnerCIncludesHeaderAndMain() {
        val c = NativeBridgeGenerator.runnerCSource("hello", "hello.h")
        assertTrue(c.contains("#include \"hello.h\""))
        assertTrue(c.contains("kscriptx_run"))
        assertTrue(c.contains("int main("))
    }
}
