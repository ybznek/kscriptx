package io.kscriptx.config

import io.kscriptx.KPaths
import io.kscriptx.model.Repository
import io.kscriptx.model.ScriptConfig
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream

data class UserConfig(
    val preamble: String = "",
    val kotlinOpts: List<String> = emptyList(),
    val repositoryUrl: String? = null,
    val repositoryUser: String? = null,
    val repositoryPassword: String? = null,
    val artifactsDir: String? = null,
) {
    fun asScriptConfig(): ScriptConfig {
        val repos = if (!repositoryUrl.isNullOrBlank()) {
            listOf(Repository(repositoryUrl, user = repositoryUser, password = repositoryPassword))
        } else emptyList()
        return ScriptConfig(
            repositories = repos,
            kotlinOptions = kotlinOpts,
        )
    }

    companion object {
        fun load(): UserConfig {
            val path = KPaths.configFile
            if (!path.exists()) return UserConfig()
            val props = Properties()
            path.inputStream().use { props.load(it) }
            return UserConfig(
                preamble = props.getProperty("scripting.preamble", ""),
                kotlinOpts = props.getProperty("scripting.kotlin.opts", "")
                    .split(Regex("\\s+")).filter { it.isNotBlank() },
                repositoryUrl = props.getProperty("scripting.repository.url"),
                repositoryUser = props.getProperty("scripting.repository.user"),
                repositoryPassword = props.getProperty("scripting.repository.password"),
                artifactsDir = props.getProperty("scripting.directory.artifacts"),
            )
        }
    }
}
