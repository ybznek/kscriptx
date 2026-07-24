#!/usr/bin/env kscriptx
@file:Import("stats.kt")
@file:Import("render.kt")

// Multi-file script: main + helpers in the same directory.
// Run: kscriptx examples/multi/main.kts 3 1 4 1 5 9

val nums = args.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(3, 1, 4, 1, 5, 9) }
val summary = summarize(nums)
println(renderReport("kscriptx multi-file demo", summary))
