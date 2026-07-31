package io.kscriptx.daemon

import java.io.OutputStream
import java.io.PrintStream

/**
 * Per-worker [System.out]/[System.err] routing so concurrent compiles do not
 * cross-talk, without a global run lock around script execution.
 */
internal object DaemonIo {
    private val outTl = ThreadLocal<PrintStream>()
    private val errTl = ThreadLocal<PrintStream>()
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun ensureInstalled() {
        if (!installed.compareAndSet(false, true)) return
        val realOut = System.out
        val realErr = System.err
        System.setOut(PrintStream(DelegatingStream { outTl.get() ?: realOut }, true))
        System.setErr(PrintStream(DelegatingStream { errTl.get() ?: realErr }, true))
    }

    fun bind(out: PrintStream, err: PrintStream) {
        outTl.set(out)
        errTl.set(err)
    }

    fun unbind() {
        outTl.remove()
        errTl.remove()
    }

    private class DelegatingStream(
        private val target: () -> PrintStream,
    ) : OutputStream() {
        override fun write(b: Int) = target().write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = target().write(b, off, len)
        override fun flush() = target().flush()
    }
}
