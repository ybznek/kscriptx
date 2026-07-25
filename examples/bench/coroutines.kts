#!/usr/bin/env kscriptx
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")

// Bench: coroutines (1.7.x keeps classic kscript / Kotlin 1.9 comparable).
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

runBlocking {
    delay(1)
    println("coroutines ok")
}
