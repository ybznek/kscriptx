import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.io.File;

@TargetClass(className = "org.jetbrains.kotlin.utils.PathUtil")
final class Target_org_jetbrains_kotlin_utils_PathUtil {
    @Substitute
    public static File getResourcePathForClass(Class<?> aClass) {
        String prop = System.getProperty("kscriptx.kotlin.compiler.jar");
        if (prop == null || prop.isEmpty()) {
            prop = System.getenv("KSCRIPTX_KOTLIN_COMPILER_JAR");
        }
        if (prop == null || prop.isEmpty()) {
            // default: sibling of the native binary
            try {
                File self = new File(PathUtilSubstitutionBootstrap.executableDir(), "kotlin-compiler-embeddable.jar");
                if (self.isFile()) return self;
            } catch (Throwable ignored) {}
            throw new IllegalStateException(
                "Native kotlinc: set kscriptx.kotlin.compiler.jar to kotlin-compiler-embeddable.jar");
        }
        File f = new File(prop);
        if (!f.isFile()) {
            throw new IllegalStateException("Native kotlinc: compiler jar not found: " + prop);
        }
        return f;
    }
}

/** Helper kept outside @TargetClass. */
final class PathUtilSubstitutionBootstrap {
    static String executableDir() {
        // ProcessHandle gives the native image path on Graal.
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd == null) return ".";
        File f = new File(cmd).getAbsoluteFile();
        File parent = f.getParentFile();
        return parent != null ? parent.getPath() : ".";
    }
}
