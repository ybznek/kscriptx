#!/usr/bin/env kscriptx
@file:Import("gtk4_ffi.kt")
@file:KotlinOptions("-J--enable-native-access=ALL-UNNAMED")

// Simple GTK4 window via Panama FFI (Linux, libgtk-4.so.1, JDK 22+).
// Run:        kscriptx examples/gtk4-hello.kts
// Headless:   xvfb-run -a kscriptx examples/gtk4-hello.kts --self-test

if (System.getenv("DISPLAY").isNullOrBlank() && System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) {
    System.err.println("No DISPLAY/WAYLAND_DISPLAY — set a graphical session or use: xvfb-run -a kscriptx examples/gtk4-hello.kts --self-test")
    kotlin.system.exitProcess(2)
}

val selfTest = "--self-test" in args.toList()

Gtk4.init()

val window = Gtk4.windowNew()
Gtk4.windowSetTitle(window, "kscriptx + GTK4")
Gtk4.windowSetDefaultSize(window, 420, 160)
val label = Gtk4.labelNew("Hello from kscriptx\nPanama FFM → libgtk-4")
Gtk4.windowSetChild(window, label)

val loop = Gtk4.mainLoopNew()
val onDestroy = Gtk4.destroyQuitUpcall()
Gtk4.signalConnect(window, "destroy", onDestroy, loop)

if (selfTest) {
    val onTimeout = Gtk4.timeoutQuitUpcall()
    Gtk4.timeoutAdd(400, onTimeout, loop)
    println("gtk4-hello self-test: showing window briefly…")
} else {
    println("gtk4-hello: close the window to exit")
}

Gtk4.windowPresent(window)
Gtk4.mainLoopRun(loop)
println("gtk4-hello: done")
