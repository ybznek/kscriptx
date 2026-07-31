package io.kscriptx.daemon

import io.kscriptx.ExecutionContext
import io.kscriptx.KPaths
import io.kscriptx.cli.ArgParser
import io.kscriptx.exec.ScriptRunPlan
import io.kscriptx.exec.ScriptRunner
import io.kscriptx.model.RunMode
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
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Persistent local JVM that **compiles** scripts for clients over a local socket.
 * Script JVMs are always started by the **original client** (or `kscriptx-dclient`),
 * never inside this process — so killing the client PID tears down the script.
 *
 * Transport (prefer Unix domain socket; TCP loopback on Windows / fallback):
 *   - `$home/daemon/sock` — AF_UNIX socket path when present
 *   - `$home/daemon/port` — TCP port on 127.0.0.1 when using loopback
 *
 * Wire protocol (big-endian), identical on both transports:
 *   str: i32 length + UTF-8 bytes
 *   request: cwd:str, envCount:i32, (key:str, value:str)*, argc:i32, args:str*
 *   response chunks:
 *     'O'|'E' → i32 len + bytes (compile diagnostics)
 *     'R' → run plan: javaBin:str, cwd:str, argc:i32, args:str*
 *     'X' → i32 exitCode (compile / request failure; no 'R')
 *
 * Compiles may run concurrently (no shared run lock). Pings stay concurrent.
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

    /** Per-process override from `--no-daemon` / `--daemon` CLI flags. Null = env default. */
    @Volatile
    var cliOverride: Boolean? = null

    fun dir() = (KPaths.home / "daemon").also { it.createDirectories() }
    fun portFile() = dir() / "port"
    fun sockFile() = dir() / "sock"
    fun pidFile() = dir() / "pid"

    /** Stop a background daemon before wiping `$home/daemon/` (e.g. `--clear-cache`). */
    fun shutdownRunning() {
        try {
            if (!pidFile().exists()) return
            val pid = pidFile().readText().trim().toLongOrNull() ?: return
            ProcessHandle.of(pid).ifPresent { handle ->
                if (handle.isAlive) handle.destroy()
            }
        } catch (_: Exception) {
        }
    }

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
            writeEnvMap(out, emptyMap())
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
                restrictSocketToOwner(sockFile())
                unixServer = ch
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
        DaemonIo.ensureInstalled()
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

    private fun restrictSocketToOwner(path: java.nio.file.Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                java.util.EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
        } catch (_: Exception) {
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
            val env = input.readEnvMap()
            if (cwd == "__ping__") {
                input.readInt() // argc 0
                output.writeInt(0)
                output.flush()
                return
            }
            touchActivity()
            val argc = input.readInt()
            val args = Array(argc) { input.readStr() }

            // Compile only — never start the script JVM here.
            ExecutionContext.withContext(env, cwd, mutateProcessUserDir = false) {
                val outStream = FramingPrintStream(output, 'O'.code)
                val errStream = FramingPrintStream(output, 'E'.code)
                DaemonIo.bind(outStream, errStream)
                try {
                    val plan = compileRunPlan(args, env, cwd)
                    if (plan == null) {
                        output.writeByte('X'.code)
                        output.writeInt(1)
                        output.flush()
                    } else {
                        writeRunPlan(output, plan)
                    }
                    0
                } catch (t: Throwable) {
                    t.printStackTrace(errStream)
                    outStream.flush()
                    errStream.flush()
                    output.writeByte('X'.code)
                    output.writeInt(1)
                    output.flush()
                    1
                } finally {
                    outStream.flush()
                    errStream.flush()
                    DaemonIo.unbind()
                }
            }
        } catch (_: Exception) {
            // client gone
        }
    }

    /**
     * Ask the daemon to compile; on success run the script JVM in **this** process tree.
     * Returns exit code, or null to fall back to a full local compile+run.
     */
    fun tryClient(args: Array<String>): Int? {
        if (!enabled()) return null
        val parsed = ArgParser.parse(args)
        // Daemon is compile-only for RUN; other modes stay local.
        if (parsed.mode != RunMode.RUN) return null
        return when (val outcome = requestCompile(args)) {
            is CompileOutcome.Ready -> ScriptRunner.executePlan(outcome.plan, execEnv = null)
            is CompileOutcome.Failed -> outcome.exitCode
            CompileOutcome.Unreachable -> null
        }
    }

    private sealed class CompileOutcome {
        data class Ready(val plan: ScriptRunPlan) : CompileOutcome()
        data class Failed(val exitCode: Int) : CompileOutcome()
        data object Unreachable : CompileOutcome()
    }

    private fun requestCompile(args: Array<String>): CompileOutcome {
        return try {
            openClientStreams()?.use { (inp, out) ->
                out.writeStr(System.getProperty("user.dir") ?: "")
                writeEnvMap(out, System.getenv())
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
                        // Return plan; `use` closes the socket *before* the script JVM starts.
                        'R' -> return@use CompileOutcome.Ready(readRunPlan(inp))
                        'X' -> return@use CompileOutcome.Failed(inp.readInt())
                        else -> return@use CompileOutcome.Failed(1)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                CompileOutcome.Unreachable
            } ?: CompileOutcome.Unreachable
        } catch (_: Exception) {
            CompileOutcome.Unreachable
        }
    }

    private fun compileRunPlan(
        args: Array<String>,
        env: Map<String, String>,
        cwd: String,
    ): ScriptRunPlan? {
        val compiled = io.kscriptx.compileOnlyForRun(args) ?: return null
        val request = ArgParser.parse(args)
        return ScriptRunner.buildRunPlan(
            compiled = compiled,
            scriptArgs = request.scriptArgs,
            workingDir = Path(cwd),
            execEnv = env,
        )
    }

    private fun writeRunPlan(output: DataOutputStream, plan: ScriptRunPlan) {
        output.writeByte('R'.code)
        output.writeStr(plan.javaBinary)
        output.writeStr(plan.workingDir)
        output.writeInt(plan.javaArgs.size)
        for (a in plan.javaArgs) output.writeStr(a)
        output.flush()
    }

    private fun readRunPlan(input: DataInputStream): ScriptRunPlan {
        val javaBin = input.readStr()
        val cwd = input.readStr()
        val n = input.readInt()
        val args = List(n) { input.readStr() }
        return ScriptRunPlan(javaBinary = javaBin, javaArgs = args, workingDir = cwd)
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

private fun DataInputStream.readEnvMap(): Map<String, String> {
    val n = readInt()
    if (n < 0) throw IllegalArgumentException("negative env count")
    if (n == 0) return emptyMap()
    val map = LinkedHashMap<String, String>(n)
    repeat(n) {
        map[readStr()] = readStr()
    }
    return map
}

private fun writeEnvMap(out: DataOutputStream, env: Map<String, String>) {
    out.writeInt(env.size)
    for ((k, v) in env) {
        out.writeStr(k)
        out.writeStr(v)
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

/** Buffers frames to cut syscall/flush overhead on chatty compile logs. */
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
