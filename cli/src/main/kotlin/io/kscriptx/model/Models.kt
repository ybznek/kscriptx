package io.kscriptx.model

import java.nio.file.Path

data class Repository(
    val url: String,
    val id: String? = null,
    val user: String? = null,
    val password: String? = null,
) {
    fun encode(): String = listOfNotNull(id, url, user, password).joinToString("::")
}

data class ScriptConfig(
    val dependencies: List<String> = emptyList(),
    val repositories: List<Repository> = emptyList(),
    val imports: List<String> = emptyList(),
    val compilerOptions: List<String> = emptyList(),
    val kotlinOptions: List<String> = emptyList(),
    val entryPoint: String? = null,
) {
    operator fun plus(other: ScriptConfig): ScriptConfig = ScriptConfig(
        dependencies = (dependencies + other.dependencies).distinct(),
        repositories = (repositories + other.repositories).distinctBy { it.encode() },
        imports = (imports + other.imports).distinct(),
        compilerOptions = (compilerOptions + other.compilerOptions).distinct(),
        kotlinOptions = (kotlinOptions + other.kotlinOptions).distinct(),
        entryPoint = other.entryPoint ?: entryPoint,
    )
}

enum class ScriptKind { KTS, KT, INLINE }

data class ResolvedScript(
    val displayName: String,
    val kind: ScriptKind,
    val rootFile: Path?,
    val sources: List<SourceUnit>,
    val config: ScriptConfig,
    val scriptArgs: List<String>,
    val rawHashMaterial: String,
)

data class SourceUnit(
    val fileName: String,
    val content: String,
    val origin: Path?,
)

data class CompiledScript(
    val hash: String,
    val classesDir: Path,
    val classpath: String,
    val entryPoint: String,
    val kotlinOptions: List<String>,
)

enum class RunMode {
    RUN,
    IDEA,
    PACKAGE,
    INTERACTIVE,
    CLEAR_CACHE,
    ADD_BOOTSTRAP,
    HELP,
    VERSION,
}

data class CliRequest(
    val mode: RunMode,
    val scriptSource: String?,
    val scriptArgs: List<String>,
    val textMode: Boolean,
    /** Mirror compile artifacts next to the script (`<stem>.kscriptx/`). */
    val compileBeside: Boolean = false,
    /** With [RunMode.PACKAGE], build a GraalVM native-image executable. */
    val nativeImage: Boolean = false,
    /** Build a GraalVM `--shared` library (+ bridge) for the script. */
    val nativeShared: Boolean = false,
    /** Build a thin C runner that loads the shared library (implies [nativeShared]). */
    val nativeRunner: Boolean = false,
    /** Reachability metadata dir for native-image (`-H:ConfigurationFileDirectories`). */
    val nativeConfigDir: String? = null,
    /** Extra `native-image` arguments. */
    val nativeImageArgs: List<String> = emptyList(),
    /** Explicit GraalVM home (`--graalvm-home`); else `GRAALVM_HOME` / PATH. */
    val graalvmHome: String? = null,
    /** With [RunMode.PACKAGE], run ProGuard on the fat jar (before native-image). */
    val proguard: Boolean = false,
    /** Explicit ProGuard home (`--proguard-home`); else `PROGUARD_HOME` / PATH. */
    val proguardHome: String? = null,
    /** Explicit ProGuard jar (`--proguard-jar`); else `PROGUARD_JAR`. */
    val proguardJar: String? = null,
    /**
     * `--jar` / `--standalone-jar` was requested (fat jar without requiring a launcher).
     * [RunMode.PACKAGE] always builds a fat jar; this flag selects jar-only when
     * `--package` is not also set.
     */
    val standaloneJar: Boolean = false,
    /** Write the shell/cmd launcher beside the script (typical `--package`). */
    val writeLauncher: Boolean = false,
)
