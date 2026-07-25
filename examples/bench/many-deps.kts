#!/usr/bin/env kscriptx
@file:DependsOn("com.google.code.gson:gson:2.11.0")
@file:DependsOn("org.apache.commons:commons-lang3:3.17.0")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")

// Bench: several Maven deps — cold stresses Coursier resolve + compile classpath.
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.apache.commons.lang3.StringUtils

data class Row(val name: String, val n: Int)

val gson = Gson()
val json = gson.toJson(Row("kscriptx", 3))
val url = "https://example.com/bench".toHttpUrl()
println("many-deps json=$json urlHost=${url.host} caps=${StringUtils.capitalize("bench")}")
