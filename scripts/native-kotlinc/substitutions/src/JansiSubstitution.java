import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Skip jansi native library extract-to-/tmp on every kotlinc-native process start.
 * Requires rebuilding the native image (./scripts/build-native-kotlinc.sh).
 */
@TargetClass(className = "org.jetbrains.kotlin.org.fusesource.jansi.internal.JansiLoader")
final class Target_org_jetbrains_kotlin_org_fusesource_jansi_internal_JansiLoader {
    @Substitute
    public static boolean initialize() {
        return false;
    }
}
