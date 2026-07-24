import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.10"
}

val scriptProps = Properties().apply {
    val f = file("script.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(name: String, default: String = ""): String =
    scriptProps.getProperty(name) ?: System.getenv(name) ?: default

val deps = prop("KS_DEPS").split('|').map { it.trim() }.filter { it.isNotEmpty() }
val repos = prop("KS_REPOS").split('|').map { it.trim() }.filter { it.isNotEmpty() }
val entryPoint = prop("KS_ENTRY", "ScriptKt")
val compilerOpts = prop("KS_COMPILER_OPTS")
val ksJvmTarget = prop("KS_JVM_TARGET", "17").ifBlank { JavaVersion.current().majorVersion }

repositories {
    mavenCentral()
    for (repo in repos) {
        val parts = repo.split("::")
        when (parts.size) {
            1 -> maven { url = uri(parts[0]) }
            2 -> maven {
                name = parts[0]
                url = uri(parts[1])
            }
            else -> maven {
                name = parts[0]
                url = uri(parts[1])
                credentials {
                    username = parts.getOrNull(2) ?: ""
                    password = parts.getOrNull(3) ?: ""
                }
            }
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    for (dep in deps) {
        implementation(dep)
    }
}

kotlin {
    // Use the JVM that runs Gradle (no toolchain download / exact JDK match required).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(ksJvmTarget))
        if (compilerOpts.isNotBlank()) {
            freeCompilerArgs.addAll(compilerOpts.split(Regex("\\s+")).filter { it.isNotEmpty() })
        }
    }
}

java {
    val ver = JavaVersion.toVersion(ksJvmTarget)
    sourceCompatibility = ver
    targetCompatibility = ver
}

// Stable output dir — kscriptx copies into the content-addressed cache after the build.
val ksOutDir = layout.buildDirectory.dir("kscriptx/out")
val runtimeCp = sourceSets.named("main").map { it.runtimeClasspath }
val kotlinClasses = layout.buildDirectory.dir("classes/kotlin/main")

tasks.register("ksResolveClasspath") {
    group = "kscriptx"
    description = "Resolve dependency classpath only (no Kotlin compile) for native kotlinc"
    val outDir = ksOutDir
    val classpathProvider = runtimeCp
    val entry = entryPoint
    val buildDirProvider = layout.buildDirectory
    inputs.property("entryPoint", entry)
    inputs.files(classpathProvider)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.mkdirs()
        val buildRoot = buildDirProvider.get().asFile.absolutePath
        val cp = classpathProvider.get()
            .filter { it.exists() }
            .map { it.absolutePath }
            .filter { !it.startsWith(buildRoot) }
            .joinToString(File.pathSeparator)
        File(out, "classpath").writeText(cp)
        File(out, "entry").writeText(entry)
        File(out, "ok").writeText("1")
    }
}

tasks.register("ksCompile") {
    group = "kscriptx"
    description = "Compile script and write classpath for kscriptx"
    dependsOn(tasks.named("classes"))

    val outDir = ksOutDir
    val classesDirProvider = kotlinClasses
    val classpathProvider = runtimeCp
    val entry = entryPoint
    val buildDirProvider = layout.buildDirectory

    inputs.property("entryPoint", entry)
    inputs.files(classpathProvider)
    inputs.dir(classesDirProvider)
    outputs.dir(outDir)

    doLast {
        val out = outDir.get().asFile
        out.mkdirs()

        val classesDir = classesDirProvider.get().asFile
        val classesOut = File(out, "classes")
        classesOut.deleteRecursively()
        if (classesDir.exists()) {
            classesDir.copyRecursively(classesOut, overwrite = true)
        } else {
            classesOut.mkdirs()
        }

        val buildRoot = buildDirProvider.get().asFile.absolutePath
        val cp = classpathProvider.get()
            .filter { it.exists() }
            .map { it.absolutePath }
            .filter { !it.startsWith(buildRoot) }
            .joinToString(File.pathSeparator)

        File(out, "classpath").writeText(cp)
        File(out, "entry").writeText(entry)
        File(out, "ok").writeText("1")
    }
}
