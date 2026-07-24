plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "io.kscriptx"
version = "0.1.0"

repositories {
    mavenCentral()
}

val compilerClasspath by configurations.creating

dependencies {
    implementation(kotlin("stdlib"))
    // Maven resolve without Gradle (prefers ~/.gradle/caches/modules-2 when present).
    implementation("io.get-coursier:interface:1.0.28")
    // Input jars for scripts/build-native-kotlinc.sh (not on the CLI runtime classpath).
    compilerClasspath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.kscriptx.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.kscriptx.MainKt"
    }
}

tasks.register<Sync>("installDistLocal") {
    dependsOn(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("bin"))
    from(tasks.jar) {
        rename { "kscriptx.jar" }
    }
    into("lib") {
        from(configurations.runtimeClasspath)
    }
    into("lib-compiler") {
        from(compilerClasspath)
    }
    preserve {
        include("kscriptx")
        include("kscriptx.bat")
        include("kscriptx.ps1")
    }
}

tasks.build {
    finalizedBy("installDistLocal")
}
