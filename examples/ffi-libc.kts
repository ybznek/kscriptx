#!/usr/bin/env kscriptx
@file:KotlinOptions("-J--enable-native-access=ALL-UNNAMED")

// Panama FFM calling glibc / libm on Linux (JDK 22+; tested on 25).
// Run: kscriptx examples/ffi-libc.kts

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

val linker = Linker.nativeLinker()
val lookup = linker.defaultLookup()

fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle {
    val symbol = lookup.find(name).orElseThrow {
        IllegalStateException("native symbol not found: $name (Linux glibc expected)")
    }
    return linker.downcallHandle(symbol, descriptor)
}

val getpid = downcall("getpid", FunctionDescriptor.of(ValueLayout.JAVA_INT))
val strlen = downcall(
    "strlen",
    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
)
val cos = downcall(
    "cos",
    FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE),
)

val pid = getpid.invokeExact() as Int
println("libc getpid()  → $pid")

Arena.ofConfined().use { arena ->
    val cstr = arena.allocateFrom("kscriptx")
    val len = strlen.invokeExact(cstr) as Long
    println("libc strlen() → $len  (\"kscriptx\")")
}

val angle = Math.PI / 3
val cosVal = cos.invokeExact(angle) as Double
println("libm  cos(π/3) → $cosVal  (expected 0.5)")
