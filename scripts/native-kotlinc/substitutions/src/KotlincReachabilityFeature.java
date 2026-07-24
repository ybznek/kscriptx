import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Keep IntelliJ-mock / UI classes that GraalVM 25 drops under {@code AllowIncompleteClasspath}
 * but that K2JVMCompiler needs at runtime (MockApplication → PicoContainer + Swing).
 *
 * Registered explicitly via {@code --features=...} (not {@code @AutomaticFeature}).
 */
public final class KotlincReachabilityFeature implements Feature {

    private static final String[] ROOTS = {
            // Pico / mock application (CoreApplicationEnvironment$1 extends MockApplication)
            "org.jetbrains.kotlin.org.picocontainer.PicoContainer",
            "org.jetbrains.kotlin.org.picocontainer.MutablePicoContainer",
            "org.jetbrains.kotlin.org.picocontainer.ComponentAdapter",
            "org.jetbrains.kotlin.com.intellij.util.pico.DefaultPicoContainer",
            "org.jetbrains.kotlin.com.intellij.util.pico.DefaultPicoContainer$InstanceComponentAdapter",
            "org.jetbrains.kotlin.com.intellij.mock.MockApplication",
            "org.jetbrains.kotlin.com.intellij.mock.MockComponentManager",
            "org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment$1",
            "org.jetbrains.kotlin.com.intellij.util.messages.impl.MessageBusFactoryImpl",
            "org.jetbrains.kotlin.com.intellij.util.concurrency.AppExecutorUtil",
            "org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionsAreaImpl",
            // Coroutines referenced from MockApplication (must be on NI classpath)
            "kotlinx.coroutines.GlobalScope",
            "kotlinx.coroutines.CoroutineScope",
            // Headless AWT / Swing touched by MockApplication
            "java.awt.GraphicsEnvironment",
            "java.awt.Toolkit",
            "sun.awt.HeadlessToolkit",
            "javax.swing.SwingUtilities",
            "javax.swing.UIManager",
    };

    @Override
    public String getDescription() {
        return "kscriptx: force PicoContainer + headless AWT/Swing into native kotlinc";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (String name : ROOTS) {
            Class<?> c = access.findClassByName(name);
            if (c == null) {
                continue;
            }
            access.registerAsUsed(c);
            try {
                RuntimeReflection.register(c);
                RuntimeReflection.register(c.getDeclaredConstructors());
                RuntimeReflection.register(c.getDeclaredMethods());
                RuntimeReflection.register(c.getDeclaredFields());
            } catch (Throwable ignored) {
                // Best-effort; reachability via registerAsUsed is the critical part.
            }
        }
    }
}
