package dev.netpilot.service.RootSupervisor

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.netpilot.core.model.IpcRequest
import dev.netpilot.core.model.IpcResponse
import dev.netpilot.core.model.NetPilotJson
import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val CONTROL_SOCKET = "dev.netpilot.control.v1"
const val MAX_CONTROL_BYTES = 1024 * 1024

fun readControlLine(input: InputStream): String {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) throw java.io.EOFException("控制通道提前关闭")
        if (next == 10) return bytes.toString(Charsets.UTF_8.name())
        if (bytes.size() >= MAX_CONTROL_BYTES) throw java.io.IOException("控制消息超过大小限制")
        bytes.write(next)
    }
}

class LocalControlClient(private val socketName: String = CONTROL_SOCKET) {
    fun request(command: String, payload: String = "", timeoutMillis: Int = 45_000): IpcResponse {
        val request = NetPilotJson.encodeToString(IpcRequest(command, payload)) + "\n"
        require(request.toByteArray().size < MAX_CONTROL_BYTES)
        return LocalSocket().use { socket ->
            // LocalSocketImpl has no file descriptor until connect/bind creates it.
            // Android 11 throws "socket not created" if an option is set beforehand.
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = timeoutMillis
            check(socket.peerCredentials.uid == 0) { "控制通道对端不是 Root 进程，拒绝通信" }
            socket.outputStream.write(request.toByteArray()); socket.outputStream.flush()
            NetPilotJson.decodeFromString<IpcResponse>(readControlLine(socket.inputStream)).also { require(it.protocolVersion == 1) }
        }
    }
}

/** An abstract socket is reachable globally; peer UID validation is mandatory on every connection. */
class LocalControlServer(private val appUid: Int, private val authorizeApp: () -> Boolean, private val handle: (IpcRequest) -> IpcResponse) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = LocalServerSocket(CONTROL_SOCKET)
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val listener = Thread({
        while (running.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            try { workers.execute { serve(socket) } } catch (_: Exception) { socket.close() }
        }
    }, "netpilot-control").apply { isDaemon = true; start() }
    private fun serve(socket: LocalSocket) {
        socket.use {
            try {
                socket.soTimeout = 45_000
                val uid = socket.peerCredentials.uid
                if (uid != 0 && uid != appUid) return
                if (uid != 0 && !authorizeApp()) return
                val request = NetPilotJson.decodeFromString<IpcRequest>(readControlLine(socket.inputStream))
                val response = if (request.protocolVersion != 1) IpcResponse(false, error = "控制协议版本不兼容") else handle(request)
                val encoded = NetPilotJson.encodeToString(response) + "\n"
                require(encoded.toByteArray().size < MAX_CONTROL_BYTES)
                socket.outputStream.write(encoded.toByteArray()); socket.outputStream.flush()
            } catch (_: Exception) { /* Disconnected or invalid clients cannot mutate daemon state. */ }
        }
    }
    override fun close() { running.set(false); server.close(); workers.shutdownNow(); listener.interrupt() }
}
