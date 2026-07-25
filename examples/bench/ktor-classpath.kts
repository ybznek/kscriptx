#!/usr/bin/env kscriptx
@file:DependsOn("io.ktor:ktor-client-core-jvm:2.3.12")
@file:DependsOn("io.ktor:ktor-http-jvm:2.3.12")

// Bench: Ktor 2.3 jars (Kotlin 1.9-friendly) without starting a server.
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder

val url = URLBuilder("https://example.com/bench").buildString()
println("ktor-classpath method=${HttpMethod.Get.value} status=${HttpStatusCode.OK.value} url=$url")
