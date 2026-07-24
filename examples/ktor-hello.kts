#!/usr/bin/env kscriptx
@file:DependsOn("io.ktor:ktor-server-core-jvm:3.1.3")
@file:DependsOn("io.ktor:ktor-server-netty-jvm:3.1.3")
@file:DependsOn("org.slf4j:slf4j-simple:2.0.16")
@file:KotlinOptions("-J--enable-native-access=ALL-UNNAMED")

// Ktor Netty hello-server (deps via Coursier).
// Run:  kscriptx examples/ktor-hello.kts
// Then: curl http://127.0.0.1:8080/
// Stop with Ctrl+C (or: kscriptx examples/ktor-hello.kts 9090)

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

val port = args.firstOrNull()?.toIntOrNull() ?: 8080

println("ktor-hello listening on http://127.0.0.1:$port/  (Ctrl+C to stop)")

embeddedServer(Netty, port = port, host = "127.0.0.1") {
    routing {
        get("/") {
            call.respondText("hello from kscriptx + ktor\n")
        }
        get("/health") {
            call.respondText("ok\n")
        }
    }
}.start(wait = true)
