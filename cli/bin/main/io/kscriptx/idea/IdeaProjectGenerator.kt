package io.kscriptx.idea

import io.kscriptx.KPaths
import io.kscriptx.compile.ScriptWrapper
import io.kscriptx.model.ResolvedScript
import io.kscriptx.util.Hasher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

object IdeaProjectGenerator {
    fun generateAndOpen(script: ResolvedScript) {
        val hash = Hasher.md5(script.rawHashMaterial).take(12)
        val projectDir = KPaths.idea / "project_$hash"
        if (projectDir.exists()) projectDir.toFile().deleteRecursively()
        projectDir.createDirectories()

        val srcDir = projectDir / "src" / "main" / "kotlin"
        srcDir.createDirectories()

        // Prefer linking original script when possible for editing
        val root = script.rootFile
        if (root != null && root.exists()) {
            val link = srcDir / root.fileName.toString()
            try {
                Files.createSymbolicLink(link, root)
            } catch (_: Exception) {
                // Windows may require privileges — fall back to copy
                Files.copy(root, link)
            }
        }

        val compilable = ScriptWrapper.toCompilableSources(script)
        for (unit in compilable) {
            val target = srcDir / unit.fileName
            if (!target.exists()) target.writeText(unit.content)
        }

        writeBuildGradle(projectDir, script)
        (projectDir / "settings.gradle.kts").writeText("rootProject.name = \"kscriptx-${script.displayName.substringBefore('.')}\"\n")
        (projectDir / "gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx1g
            org.gradle.daemon=true
            """.trimIndent() + "\n"
        )

        // Copy wrapper from compiler home if available, else from classpath later
        copyWrapper(projectDir)

        val ideaCmd = System.getenv("KSCRIPT_COMMAND_IDEA")
            ?: System.getenv("KSCRIPTX_COMMAND_IDEA")
            ?: "idea"
        val gradleCmd = System.getenv("KSCRIPT_COMMAND_GRADLE")
            ?: System.getenv("KSCRIPTX_COMMAND_GRADLE")

        if (!gradleCmd.isNullOrBlank()) {
            runQuiet(listOf(gradleCmd, "classes"), projectDir)
        } else if ((projectDir / "gradlew").exists() || (projectDir / "gradlew.bat").exists()) {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val gw = if (isWin) "gradlew.bat" else "./gradlew"
            runQuiet(listOf(gw, "classes"), projectDir)
        }

        println("IDEA project: $projectDir")
        try {
            ProcessBuilder(ideaCmd, projectDir.absolutePathString())
                .inheritIO()
                .start()
        } catch (e: Exception) {
            println("Could not launch '$ideaCmd'. Open the project manually:")
            println("  $projectDir")
        }
    }

    private fun writeBuildGradle(projectDir: Path, script: ResolvedScript) {
        val deps = script.config.dependencies.joinToString("\n") { """    implementation("$it")""" }
        val repos = script.config.repositories.joinToString("\n") { repo ->
            when {
                repo.user != null -> """
                    |    maven {
                    |        name = "${repo.id ?: "custom"}"
                    |        url = uri("${repo.url}")
                    |        credentials {
                    |            username = "${repo.user}"
                    |            password = "${repo.password ?: ""}"
                    |        }
                    |    }
                """.trimMargin()
                repo.id != null -> """
                    |    maven {
                    |        name = "${repo.id}"
                    |        url = uri("${repo.url}")
                    |    }
                """.trimMargin()
                else -> """    maven { url = uri("${repo.url}") }"""
            }
        }
        (projectDir / "build.gradle.kts").writeText(
            """
            |plugins {
            |    kotlin("jvm") version "${io.kscriptx.KscriptVersions.KOTLIN}"
            |}
            |
            |repositories {
            |    mavenCentral()
            |$repos
            |}
            |
            |dependencies {
            |    implementation(kotlin("stdlib"))
            |    implementation("io.github.kscripting:kscript-annotations:1.5.0")
            |$deps
            |}
            |
            |kotlin {
            |    compilerOptions {
            |        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            |    }
            |}
            |
            |java {
            |    sourceCompatibility = JavaVersion.VERSION_17
            |    targetCompatibility = JavaVersion.VERSION_17
            |}
            """.trimMargin() + "\n"
        )
    }

    private fun copyWrapper(projectDir: Path) {
        val fromCompiler = KPaths.compiler
        val hasLocal = (fromCompiler / "gradlew").exists() || (fromCompiler / "gradlew.bat").exists()
        if (hasLocal) {
            copyWrapperFiles(fromCompiler, projectDir)
            return
        }
        extractWrapperFromResources(projectDir)
    }

    private fun copyWrapperFiles(from: Path, projectDir: Path) {
        for (rel in listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val src = from / rel
            if (src.exists()) {
                val dst = projectDir / rel
                dst.parent?.createDirectories()
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        try {
            (projectDir / "gradlew").toFile().setExecutable(true)
        } catch (_: Exception) {
        }
    }

    private fun extractWrapperFromResources(projectDir: Path) {
        val cl = IdeaProjectGenerator::class.java.classLoader
        val root = "compiler-template"
        for (rel in listOf(
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
        )) {
            val url = cl.getResource("$root/$rel") ?: continue
            val target = projectDir / rel
            target.parent?.createDirectories()
            url.openStream().use { input ->
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        try {
            (projectDir / "gradlew").toFile().setExecutable(true)
        } catch (_: Exception) {
        }
    }

    private fun runQuiet(cmd: List<String>, dir: Path) {
        try {
            ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {
        }
    }
}
