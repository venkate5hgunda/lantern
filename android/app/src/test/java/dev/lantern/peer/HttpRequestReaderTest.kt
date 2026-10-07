package dev.lantern.peer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class HttpRequestReaderTest {
    private fun request(headers: String, body: String = ""): Request =
        HttpRequestReader.read(ByteArrayInputStream((headers + body).toByteArray(StandardCharsets.UTF_8)))

    @Test fun `reads bounded JSON body and retains bearer header`() {
        val payload = """{"code":"123456"}"""
        val message = "POST /api/v1/pair HTTP/1.1\r\nHost: camera.mesh:8443\r\n" +
            "Content-Length: ${payload.toByteArray().size}\r\nAuthorization: Bearer example\r\n\r\n"
        val parsed = request(message, payload)
        assertEquals(payload, String(parsed.body))
        assertEquals("Bearer example", parsed.header("authorization"))
    }
    @Test fun `refuses oversized body before consuming it`() {
        val header = "POST /api/v1/pair HTTP/1.1\r\nHost: camera.mesh:8443\r\nContent-Length: 98305\r\n\r\n"
        val input = ByteArrayInputStream((header + "never-read").toByteArray())
        assertThrows(TooLarge::class.java) { HttpRequestReader.read(input) }
        assertEquals("never-read".length, input.available())
        assertThrows(TooLarge::class.java) {
            request("POST /api/v1/pair HTTP/1.1\r\nHost: camera.mesh\r\nContent-Length: 999999999999999999999999\r\n\r\n")
        }
    }
    @Test fun `accepts exact body limit`() {
        val body = "x".repeat(96 * 1024)
        val parsed = request("POST /api/v1/pair HTTP/1.1\r\nHost: camera.mesh\r\nContent-Length: ${body.length}\r\n\r\n", body)
        assertEquals(96 * 1024, parsed.body.size)
    }
    @Test fun `rejects ambiguous framing and duplicate sensitive headers`() {
        listOf(
            "Transfer-Encoding: chunked\r\n",
            "Content-Length: 0\r\ncontent-length: 1\r\n",
            "Authorization: Bearer a\r\nauthorization: Bearer b\r\n",
            "Content-Length: nope\r\n",
        ).forEach { fields ->
            assertThrows(fields, IllegalArgumentException::class.java) {
                request("POST /api/v1/events HTTP/1.1\r\nHost: camera.mesh\r\n${fields}\r\n")
            }
        }
    }
    @Test fun `rejects paths and request methods outside wire protocol`() {
        listOf(
            "POST /api/v1/pair?code=123456 HTTP/1.1",
            "GET http://evil.example/api/v1/info HTTP/1.1",
            "TRACE /api/v1/info HTTP/1.1",
            "POST /api/v1/pair HTTP/1.0",
        ).forEach { start ->
            assertThrows(IllegalArgumentException::class.java) { request("$start\r\nHost: camera.mesh\r\n\r\n") }
        }
    }
    @Test fun `rejects too large headers and incomplete body`() {
        val header = "GET /api/v1/info HTTP/1.1\r\nHost: camera.mesh\r\nX-Long: ${"a".repeat(13_000)}\r\n\r\n"
        assertThrows(TooLarge::class.java) { request(header) }
        assertThrows(IllegalArgumentException::class.java) {
            request("POST /api/v1/pair HTTP/1.1\r\nHost: camera.mesh\r\nContent-Length: 5\r\n\r\n", "abc")
        }
    }
    @Test fun `allows bodyless manual snapshot POST and Meshnet host`() {
        val parsed = request("POST /api/v1/events HTTP/1.1\r\nHost: 100.82.3.4:8443\r\n\r\n")
        assertEquals(0, parsed.body.size)
        assertEquals("100.82.3.4:8443", parsed.header("host"))
    }
}
