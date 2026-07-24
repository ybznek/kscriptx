// Minimal GTK4 + GLib / GObject bindings via Panama FFM (Linux).

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

object Gtk4 {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofAuto()

    private val lookup: SymbolLookup = SymbolLookup.libraryLookup("libgtk-4.so.1", arena)
        .or(SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena))
        .or(SymbolLookup.libraryLookup("libglib-2.0.so.0", arena))
        .or(linker.defaultLookup())

    private fun sym(name: String): MemorySegment =
        lookup.find(name).orElseThrow { UnsatisfiedLinkError("missing native symbol: $name") }

    private fun downcall(name: String, fd: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(sym(name), fd)

    private val I = ValueLayout.JAVA_INT
    private val J = ValueLayout.JAVA_LONG
    private val A = ValueLayout.ADDRESS

    private val gtkInit = downcall("gtk_init", FunctionDescriptor.ofVoid())
    private val gtkWindowNew = downcall("gtk_window_new", FunctionDescriptor.of(A))
    private val gtkWindowSetTitle = downcall("gtk_window_set_title", FunctionDescriptor.ofVoid(A, A))
    private val gtkWindowSetDefaultSize = downcall("gtk_window_set_default_size", FunctionDescriptor.ofVoid(A, I, I))
    private val gtkWindowSetChild = downcall("gtk_window_set_child", FunctionDescriptor.ofVoid(A, A))
    private val gtkWindowPresent = downcall("gtk_window_present", FunctionDescriptor.ofVoid(A))
    private val gtkLabelNew = downcall("gtk_label_new", FunctionDescriptor.of(A, A))
    private val gMainLoopNew = downcall("g_main_loop_new", FunctionDescriptor.of(A, A, I))
    private val gMainLoopRun = downcall("g_main_loop_run", FunctionDescriptor.ofVoid(A))
    private val gMainLoopQuit = downcall("g_main_loop_quit", FunctionDescriptor.ofVoid(A))
    private val gSignalConnectData = downcall(
        "g_signal_connect_data",
        FunctionDescriptor.of(J, A, A, A, A, A, I),
    )
    private val gTimeoutAdd = downcall("g_timeout_add", FunctionDescriptor.of(I, I, A, A))

    // Prefer invokeWithArguments from Kotlin — invokeExact's void/primitive
    // signatures don't match Kotlin's Object-based call sites.
    fun init() {
        gtkInit.invokeWithArguments()
    }

    fun windowNew(): MemorySegment = gtkWindowNew.invokeWithArguments() as MemorySegment

    fun windowSetTitle(window: MemorySegment, title: String) {
        Arena.ofConfined().use { a ->
            gtkWindowSetTitle.invokeWithArguments(window, a.allocateFrom(title))
        }
    }

    fun windowSetDefaultSize(window: MemorySegment, width: Int, height: Int) {
        gtkWindowSetDefaultSize.invokeWithArguments(window, width, height)
    }

    fun windowSetChild(window: MemorySegment, child: MemorySegment) {
        gtkWindowSetChild.invokeWithArguments(window, child)
    }

    fun windowPresent(window: MemorySegment) {
        gtkWindowPresent.invokeWithArguments(window)
    }

    fun labelNew(text: String): MemorySegment =
        Arena.ofConfined().use { a ->
            gtkLabelNew.invokeWithArguments(a.allocateFrom(text)) as MemorySegment
        }

    fun mainLoopNew(): MemorySegment =
        gMainLoopNew.invokeWithArguments(MemorySegment.NULL, 0) as MemorySegment

    fun mainLoopRun(loop: MemorySegment) {
        gMainLoopRun.invokeWithArguments(loop)
    }

    fun mainLoopQuit(loop: MemorySegment) {
        gMainLoopQuit.invokeWithArguments(loop)
    }

    /** Connect GObject signal; [callback] must stay reachable for the connection lifetime. */
    fun signalConnect(instance: MemorySegment, signal: String, callback: MemorySegment, data: MemorySegment) {
        Arena.ofConfined().use { a ->
            gSignalConnectData.invokeWithArguments(
                instance,
                a.allocateFrom(signal),
                callback,
                data,
                MemorySegment.NULL,
                0,
            )
        }
    }

    fun timeoutAdd(intervalMs: Int, callback: MemorySegment, data: MemorySegment): Int =
        gTimeoutAdd.invokeWithArguments(intervalMs, callback, data) as Int
    /**
     * Upcall: void (*)(void* widget, void* data) — used for "destroy".
     * Quits the GMainLoop passed as [data].
     */
    fun destroyQuitUpcall(): MemorySegment {
        val target = MethodHandles.lookup().findStatic(
            Gtk4::class.java,
            "onDestroyQuit",
            java.lang.invoke.MethodType.methodType(
                Void.TYPE,
                MemorySegment::class.java,
                MemorySegment::class.java,
            ),
        )
        return linker.upcallStub(
            target,
            FunctionDescriptor.ofVoid(A, A),
            arena,
        )
    }

    /**
     * Upcall: int (*)(void* data) — used with g_timeout_add; return 0 to remove.
     */
    fun timeoutQuitUpcall(): MemorySegment {
        val target = MethodHandles.lookup().findStatic(
            Gtk4::class.java,
            "onTimeoutQuit",
            java.lang.invoke.MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
        )
        return linker.upcallStub(
            target,
            FunctionDescriptor.of(I, A),
            arena,
        )
    }

    @JvmStatic
    fun onDestroyQuit(widget: MemorySegment, data: MemorySegment) {
        mainLoopQuit(data)
    }

    @JvmStatic
    fun onTimeoutQuit(data: MemorySegment): Int {
        mainLoopQuit(data)
        return 0
    }
}
