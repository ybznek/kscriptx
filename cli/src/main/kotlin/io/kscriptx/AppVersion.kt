package io.kscriptx

/**
 * CLI version — sourced from Gradle [project.version] at build time
 * (`kscriptx-version.txt` + jar manifest). Do not hardcode here.
 */
object AppVersion {
    val VERSION: String = readVersion()

    private fun readVersion(): String {
        AppVersion::class.java.getResourceAsStream("/kscriptx-version.txt")?.use { stream ->
            stream.bufferedReader().readLine()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        Package.getPackage("io.kscriptx")?.implementationVersion?.takeIf { it.isNotEmpty() }?.let { return it }
        return "dev"
    }
}

/** @see AppVersion.VERSION */
val VERSION: String get() = AppVersion.VERSION
