#!/usr/bin/env kscriptx
// Bench: chatty stdout (daemon framing / IPC throughput).
repeat(500) { i ->
    println("line-$i-${i * i}")
}
