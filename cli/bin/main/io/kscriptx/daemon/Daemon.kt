package io.kscriptx.daemon

import io.kscriptx.KPaths
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Persistent local JVM that answers run requests over a local socket.
 *
 * Transport (prefer Unix domain socket; TCP loopback on Windows / fallback):
 *   - `$home/daemon/sock` — AF_UNIX socket path when present
 *   - `$home/daemon/port` — TCP port on 127.0.0.1 when using loopback
 *
 * Wire protocol (big-endian), identical on both transports:
 *   str: i32 length + UTF-8 bytes
 *   request: cwd:str, argc:i32, args:str*
 *   response chunks: u8 type ('O'|'E'|'X');
 *     O/E → i32 len + bytes; X → i32 exitCode
 *
 * Script execution is single-flight: [System.out]/[System.err]/`user.dir` are
 * process-global, so concurrent runs would cross-talk. Pings stay concurrent.
 */
object Daemon {
    /** Default idle timeout before the daemon exits (overridable via env). */
    private val idleMs: Long = run {
        val raw = System.getenv("KSCRIPTX_DAEMON_IDLE_MINUTES")?.trim()
        val minutes = raw?.toLongOrNull()?.takeIf { it > 0 } ?: 30L
        minutes * 60_000L
    }
    private val running = AtomicBoolean(false)
    private val lastActivityMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    /** Exclusive lock for script runs (stdout/stderr/`user.dir` isolation). */
    private val runLock = ReentrantLock()

    /** Per-process override from `--no-daemon` / `--daemon` CLI flags. Null = env default. */
    @Volatile
    var cliOverride: Boolean? = null

    fun dir() = (KPaths.home / "daemon").also { it.createDirectories() }
    fun portFile() = dir() / "port"
    fun sockFile() = dir() / "sock"
    fun pidFile() = dir() / "pid"

    fun touchActivity() {
        lastActivityMs.set(System.currentTimeMillis())
    }

    fun enabled(): Boolean {
        cliOverride?.let { return it }
        val v = System.getenv("KSCRIPTX_DAEMON")?.trim()?.lowercase()
        return v != "0" && v != "false" && v != "off" && v != "no"
    }

    /** Prefer AF_UNIX on non-Windows; TCP loopback otherwise (or when forced). */
    fun preferUnixDomain(): Boolean {
        val force = System.getenv("KSCRIPTX_DAEMON_TRANSPORT")?.trim()?.lowercase()
        when (force) {
            "unix", "uds", "socket" -> return true
            "tcp", "loopback" -> return false
        }
        val os = System.getProperty("os.name").lowercase()
        return !os.contains("win")
    }

    fun isAlive(): Boolean {
        try {
            if (pidFile().exists()) {
                val pid = pidFile().readText().trim().toLong()
                val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                if (alive && (sockFile().exists() || readPort() != null)) return true
            }
        } catch (_: Exception) {
        }
        return pingServer()
    }

    private fun pingServer(): Boolean = try {
        openClientStreams()?.use { (inp, out) ->
            out.writeStr("__ping__")
            out.writeInt(0)
            out.flush()
            inp.readInt() == 0
        } ?: false
    } catch (_: Exception) {
        false
    }

    fun readPort(): Int? = try {
        if (!portFile().exists()) null else portFile().readText().trim().toInt()
    } catch (_: Exception) {
        null
    }

    fun startServer() {
        if (!running.compareAndSet(false, true)) return
        KPaths.ensureRuntimeLayout()
        io.kscriptx.compile.NativeKotlincCompiler.prewarmAsync()
        dir()
        cleanupEndpointFiles()

        val useUnix = preferUnixDomain()
        var unixServer: ServerSocketChannel? = null
        var tcpServer: ServerSocket? = null
        if (useUnix) {
            try {
                Files.deleteIfExists(sockFile())
                val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                ch.bind(UnixDomainSocketAddress.of(sockFile()))
                unixServer = ch
                // Marker so clients know UDS is active (path is sockFile itself).
                portFile().deleteIfExists()
            } catch (e: Exception) {
                System.err.println("kscriptx-daemon: UDS bind failed (${e.message}); falling back to TCP")
                tcpServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
                portFile().writeText(tcpServer.localPort.toString())
                sockFile().deleteIfExists()
            }
        } else {
            tcpServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            portFile().writeText(tcpServer.localPort.toString())
            sockFile().deleteIfExists()
        }

        pidFile().writeText(ProcessHandle.current().pid().toString())
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                cleanupEndpointFiles()
                unixServer?.close()
                tcpServer?.close()
            } catch (_: Exception) {
            }
        })
        lastActivityMs.set(System.currentTimeMillis())
        Thread({
            while (true) {
                Thread.sleep(5_000)
                val idleFor = System.currentTimeMillis() - lastActivityMs.get()
                if (idleFor > idleMs) {
                    try {
                        cleanupEndpointFiles()
                    } catch (_: Exception) {
                    }
                    exitProcess(0)
                }
            }
        }, "kscriptx-daemon-idle").apply { isDaemon = true; start() }

        if (unixServer != null) {
            while (true) {
                val channel = try {
                    unixServer.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({
                    channel.use { handleClient(Channels.newInputStream(it), Channels.newOutputStream(it)) }
                }, "kscriptx-daemon-worker").start()
            }
        } else {
            val server = tcpServer!!
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                Thread({
                    socket.use { handleClient(it.getInputStream(), it.getOutputStream()) }
                }, "kscriptx-daemon-worker").start()
            }
        }
    }

    private fun cleanupEndpointFiles() {
        portFile().deleteIfExists()
        pidFile().deleteIfExists()
        try {
            Files.deleteIfExists(sockFile())
        } catch (_: Exception) {
        }
    }

    private fun handleClient(rawIn: InputStream, rawOut: OutputStream) {
        val input = DataInputStream(rawIn)
        val output = DataOutputStream(rawOut)
        try {
            val cwd = input.readStr()
            if (cwd == "__ping__") {
                input.readInt() // argc 0
                output.writeInt(0)
                output.flush()
                return
            }
            touchActivity()
            val argc = input.readInt()
            val args = Array(argc) { input.readStr() }

            // Single-flight: System.out/err and user.dir are process-global.
            runLock.withLock {
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
            }
        } catch (_: Exception) {
            // client gone
        }
    }

    /** Returns exit code if the daemon handled the request; null to fall back to local JVM. */
    fun tryClient(args: Array<String>): Int? {
        if (!enabled()) return null
        return try {
            openClientStreams()?.use { (inp, out) ->
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
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class ClientStreams(val input: DataInputStream, val output: DataOutputStream) : AutoCloseable {
        override fun close() {
            try {
                input.close()
            } catch (_: Exception) {
            }
            try {
                output.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun openClientStreams(): ClientStreams? {
        if (sockFile().exists()) {
            try {
                val ch = SocketChannel.open(UnixDomainSocketAddress.of(sockFile()))
                return ClientStreams(
                    DataInputStream(Channels.newInputStream(ch)),
                    DataOutputStream(Channels.newOutputStream(ch)),
                )
            } catch (_: Exception) {
                // fall through to TCP
            }
        }
        val port = readPort() ?: return null
        val sock = Socket(InetAddress.getLoopbackAddress(), port)
        sock.soTimeout = 0
        return ClientStreams(DataInputStream(sock.getInputStream()), DataOutputStream(sock.getOutputStream()))
    }

    /** Start a background daemon for subsequent invocations (best-effort). */
    fun spawnBackgroundIfNeeded() {
        if (!enabled()) return
        if (isAlive()) return
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

/** Buffers frames to cut syscall/flush overhead on chatty scripts. */
private class FramingPrintStream(
    private val sink: DataOutputStream,
    private val frameType: Int,
) : java.io.PrintStream(ByteArrayOutputStream(), false, StandardCharsets.UTF_8) {
    private val lock = Any()
    private val buf = ByteArrayOutputStream(4096)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            buf.write(b, off, len)
            if (buf.size() >= 4096) flushBuffer()
        }
    }

    override fun write(b: Int) {
        synchronized(lock) {
            buf.write(b)
            if (buf.size() >= 4096) flushBuffer()
        }
    }

    override fun flush() {
        synchronized(lock) {
            flushBuffer()
            sink.flush()
        }
    }

    private fun flushBuffer() {
        if (buf.size() == 0) return
        val data = buf.toByteArray()
        buf.reset()
        sink.writeByte(frameType)
        sink.writeInt(data.size)
        sink.write(data)
    }
}
