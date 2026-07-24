package io.kscriptx.daemon

import io.kscriptx.KPaths
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Persistent local JVM that answers run requests over loopback TCP.
 *
 * Wire protocol (big-endian):
 *   str: i32 length + UTF-8 bytes
 *   request: cwd:str, argc:i32, args:str*
 *   response chunks: u8 type ('O'|'E'|'X');
 *     O/E → i32 len + bytes; X → i32 exitCode
 */
object Daemon {
    private const val IDLE_MS = 30 * 60 * 1000L
    private val running = AtomicBoolean(false)

    /** Per-process override from `--no-daemon` / `--daemon` CLI flags. Null = env default. */
    @Volatile
    var cliOverride: Boolean? = null

    fun dir() = (KPaths.home / "daemon").also { it.createDirectories() }
    fun portFile() = dir() / "port"
    fun pidFile() = dir() / "pid"

    fun enabled(): Boolean {
        cliOverride?.let { return it }
        val v = System.getenv("KSCRIPTX_DAEMON")?.trim()?.lowercase()
        return v != "0" && v != "false" && v != "off" && v != "no"
    }

    fun isAlive(): Boolean {
        val port = readPort() ?: return false
        return try {
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                DataOutputStream(sock.getOutputStream()).apply {
                    writeStr("__ping__")
                    writeInt(0)
                    flush()
                }
                val code = DataInputStream(sock.getInputStream()).readInt()
                code == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    fun readPort(): Int? = try {
        if (!portFile().exists()) null else portFile().readText().trim().toInt()
    } catch (_: Exception) {
        null
    }

    fun startServer() {
        if (!running.compareAndSet(false, true)) return
        KPaths.ensureRuntimeLayout()
        dir()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        portFile().writeText(server.localPort.toString())
        pidFile().writeText(ProcessHandle.current().pid().toString())
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                portFile().deleteIfExists()
                pidFile().deleteIfExists()
            } catch (_: Exception) {
            }
        })
        var last = System.currentTimeMillis()
        Thread({
            while (true) {
                Thread.sleep(5_000)
                if (System.currentTimeMillis() - last > IDLE_MS) {
                    exitProcess(0)
                }
            }
        }, "kscriptx-daemon-idle").apply { isDaemon = true; start() }

        while (true) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            }
            last = System.currentTimeMillis()
            Thread({
                socket.use { handleClient(it) }
            }, "kscriptx-daemon-worker").start()
        }
    }

    private fun handleClient(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        try {
            val cwd = input.readStr()
            if (cwd == "__ping__") {
                input.readInt() // argc 0
                output.writeInt(0)
                output.flush()
                return
            }
            val argc = input.readInt()
            val args = Array(argc) { input.readStr() }
            if (cwd.isNotBlank()) {
                System.setProperty("user.dir", cwd)
            }
            val oldOut = System.out
            val oldErr = System.err
            val outStream = FramingPrintStream(output, 'O'.code)
            val errStream = FramingPrintStream(output, 'E'.code)
            System.setOut(outStream)
            System.setErr(errStream)
            val code = try {
                System.setProperty("KSCRIPTX_IN_DAEMON", "1")
                io.kscriptx.runMain(args, fromDaemon = true)
            } catch (t: Throwable) {
                t.printStackTrace(errStream)
                1
            } finally {
                System.clearProperty("KSCRIPTX_IN_DAEMON")
                System.setOut(oldOut)
                System.setErr(oldErr)
                outStream.flush()
                errStream.flush()
            }
            output.writeByte('X'.code)
            output.writeInt(code)
            output.flush()
        } catch (_: Exception) {
            // client gone
        }
    }

    /** Returns exit code if the daemon handled the request; null to fall back to local JVM. */
    fun tryClient(args: Array<String>): Int? {
        if (!enabled()) return null
        val port = readPort() ?: return null
        return try {
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                sock.soTimeout = 0
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                out.writeStr(System.getProperty("user.dir") ?: "")
                out.writeInt(args.size)
                for (a in args) out.writeStr(a)
                out.flush()
                while (true) {
                    val type = inp.readByte().toInt().toChar()
                    when (type) {
                        'O' -> {
                            val n = inp.readInt()
                            val buf = inp.readNBytes(n)
                            System.out.write(buf)
                            System.out.flush()
                        }
                        'E' -> {
                            val n = inp.readInt()
                            val buf = inp.readNBytes(n)
                            System.err.write(buf)
                            System.err.flush()
                        }
                        'X' -> return inp.readInt()
                        else -> return 1
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Start a background daemon for subsequent invocations (best-effort). */
    fun spawnBackgroundIfNeeded() {
        if (!enabled() || isAlive()) return
        try {
            val cp = System.getProperty("java.class.path") ?: return
            val javaHome = System.getProperty("java.home")
            val javaBin = if (javaHome != null) {
                val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
                java.io.File(javaHome, "bin/$name").absolutePath
            } else {
                "java"
            }
            dir()
            val log = dir() / "daemon.log"
            ProcessBuilder(
                javaBin,
                "-XX:TieredStopAtLevel=1",
                "-XX:+UseSerialGC",
                "-cp",
                cp,
                "io.kscriptx.MainKt",
                "--daemon-server",
            )
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
        }
    }
}

private fun DataOutputStream.writeStr(s: String) {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readStr(): String {
    val n = readInt()
    if (n < 0) throw IllegalArgumentException("negative string length")
    val bytes = readNBytes(n)
    return String(bytes, StandardCharsets.UTF_8)
}

private class FramingPrintStream(
    private val sink: DataOutputStream,
    private val frameType: Int,
) : java.io.PrintStream(java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8) {
    private val lock = Any()
    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            sink.writeByte(frameType)
            sink.writeInt(len)
            sink.write(buf, off, len)
            sink.flush()
        }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }
}
