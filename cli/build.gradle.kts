plugins {
    kotlin("jvm") version "2.4.10"
    application
    jacoco
}

group = "io.kscriptx"
version = "0.1.5"

repositories {
    mavenCentral()
}

val compilerClasspath = configurations.create("compilerClasspath")
val resolveClasspath = configurations.create("resolveClasspath")

dependencies {
    implementation(kotlin("stdlib"))
    // Coursier only on resolve classpath — loaded lazily on dependency cache miss.
    resolveClasspath("io.get-coursier:interface:1.0.28")
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
        attributes["Implementation-Version"] = version.toString()
    }
}

tasks.processResources {
    val ver = project.version.toString()
    inputs.property("version", ver)
    filesMatching("kscriptx-version.txt") {
        filter { line -> line.replace("@version@", ver) }
    }
}

val nativeProjectDir = rootProject.layout.projectDirectory.dir("native")

tasks.register<Exec>("compileNativeHelpers") {
    group = "build"
    description = "Build Rust helpers (kscriptx-dclient, kscriptx-coverage) into bin/"
    // Windows portable packages use the JVM launcher; skip Rust helpers there
    // (Git Bash often hits a broken cargo→WSL stub on Actions runners).
    onlyIf {
        !org.gradle.internal.os.OperatingSystem.current().isWindows
    }
    commandLine("bash", rootProject.file("scripts/build-native-helpers.sh").absolutePath)
    inputs.files(
        nativeProjectDir.file("Cargo.toml"),
        nativeProjectDir.dir("src").asFileTree,
        rootProject.file("scripts/build-native-helpers.sh"),
    )
    outputs.files(
        rootProject.layout.projectDirectory.file("bin/kscriptx-dclient"),
        rootProject.layout.projectDirectory.file("bin/kscriptx-coverage"),
    )
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
    into("lib-resolve") {
        from(resolveClasspath)
    }
    into("lib-compiler") {
        from(compilerClasspath)
    }
    preserve {
        include("kscriptx")
        include("kscriptx.bat")
        include("kscriptx.ps1")
        include("kscriptx-dclient")
        include("kscriptx-coverage")
    }
}

tasks.build {
    finalizedBy("compileNativeHelpers", "installDistLocal")
}

tasks.named("installDistLocal") {
    // Ensure jars land before/with native bins; native install is independent of Sync.
    finalizedBy("compileNativeHelpers")
}
