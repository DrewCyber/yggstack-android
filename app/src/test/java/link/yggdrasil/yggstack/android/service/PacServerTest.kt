package link.yggdrasil.yggstack.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class PacServerTest {

    private fun get(port: Int, path: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        return try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    @Test fun servesPacScriptAtConfiguredPath() {
        val server = PacServer(0).apply { /* port 0: OS picks */ }
        // Port 0 lets the OS choose; PacServer exposes the bound port.
        assertTrue(server.start("// pac content v1"))
        val port = server.boundPort!!
        try {
            val (code, body) = get(port, "/proxy.pac")
            assertEquals(200, code)
            assertEquals("// pac content v1", body)
        } finally {
            server.stop()
        }
    }

    @Test fun queryStringStillServesAndUnknownPathIs404() {
        val server = PacServer(0)
        assertTrue(server.start("body"))
        val port = server.boundPort!!
        try {
            assertEquals(200, get(port, "/proxy.pac?ts=123").first)
            assertEquals(404, get(port, "/other").first)
        } finally {
            server.stop()
        }
    }

    @Test fun updateSwapsServedContent() {
        val server = PacServer(0)
        assertTrue(server.start("v1"))
        val port = server.boundPort!!
        try {
            assertEquals("v1", get(port, "/proxy.pac").second)
            server.update("v2")
            assertEquals("v2", get(port, "/proxy.pac").second)
        } finally {
            server.stop()
        }
    }

    @Test fun stopClosesTheListener() {
        val server = PacServer(0)
        assertTrue(server.start("v1"))
        val port = server.boundPort!!
        server.stop()
        assertFalse(server.isRunning())
        var refused = false
        try {
            get(port, "/proxy.pac")
        } catch (e: Exception) {
            refused = true
        }
        assertTrue(refused)
    }
}
