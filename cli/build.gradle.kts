plugins {
    kotlin("jvm") version "2.4.10"
    application
    jacoco
}

group = "io.kscriptx"
version = "0.1.1"

repositories {
    mavenCentral()
}

val compilerClasspath = configurations.create("compilerClasspath")

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
    reports.junitXml.required.set(true)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("**/MainKt.class")
            }
        })
    )
}

tasks.register("coverage") {
    group = "verification"
    description = "Run tests and generate JaCoCo XML/HTML reports"
    dependsOn(tasks.test, tasks.jacocoTestReport)
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
