package link.yggdrasil.yggstack.android.service

import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal loopback HTTP server for the PAC script — no dependencies.
 *
 * Serves [path] with `application/x-ns-proxy-autoconfig` and answers anything
 * else with 404. Runs on its own thread and stays up for the whole service
 * lifetime (including Power Save idle) so Android's periodic PAC re-fetches
 * never fail and never need to wake the node.
 */
class PacServer(
    private val port: Int,
    private val path: String = "/proxy.pac",
) {
    @Volatile
    private var content: String = ""

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    /** The last bound listen address, for logs; null until started. */
    @Volatile
    var boundPort: Int? = null
        private set

    fun isRunning(): Boolean = running.get()

    /**
     * Start serving. Returns false (and cleans up) when the port cannot be
     * bound — the caller surfaces the error and the app still works without
     * a PAC.
     */
    fun start(initialContent: String): Boolean {
        content = initialContent
        if (!running.compareAndSet(false, true)) return true
        val ss = try {
            ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            running.set(false)
            return false
        }
        serverSocket = ss
        boundPort = ss.localPort
        thread = Thread({ serveLoop(ss) }, "pac-server").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Swap the served script; visible to in-flight requests immediately. */
    fun update(newContent: String) {
        content = newContent
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        thread?.let { t ->
            // The accept loop exits on socket close; give it a moment.
            t.join(1000)
        }
        serverSocket = null
        thread = null
        boundPort = null
    }

    private fun serveLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break // closed
            }
            try {
                handle(client)
            } catch (_: Exception) {
                // Best effort per request; keep serving.
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5000
            sock.getInputStream().bufferedReader(Charsets.ISO_8859_1).use { reader ->
                val requestLine = reader.readLine() ?: return
                val method = requestLine.substringBefore(' ')
                val target = requestLine.substringAfter(' ').substringBefore(' ')
                // Drain request headers so pipelined bytes don't confuse anyone.
                while (reader.readLine()?.isNotEmpty() == true) { /* skip */ }

                val body: ByteArray
                val status: String
                if (method == "GET" && (target == path || target.substringBefore('?') == path)) {
                    status = "200 OK"
                    body = content.toByteArray(Charsets.UTF_8)
                } else {
                    status = "404 Not Found"
                    body = ByteArray(0)
                }
                BufferedOutputStream(sock.getOutputStream()).use { out ->
                    out.write(
                        ("HTTP/1.1 $status\r\n" +
                            "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                    )
                    out.write(body)
                    out.flush()
                }
            }
        }
    }
}
