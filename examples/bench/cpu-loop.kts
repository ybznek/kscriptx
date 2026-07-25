#!/usr/bin/env kscriptx
// Bench: pure Kotlin CPU work (no deps). Warm times isolate runtime vs launcher.
var n = 0L
for (i in 1..200_000) {
    n += (i * 31L) xor (i.toLong() shr 3)
}
println("cpu-loop checksum=$n")
