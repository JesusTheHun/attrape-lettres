package fr.dappit.attrapelettres.platform

import java.io.IOException
import java.net.CookieHandler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/* -------------------------------------------------------------------------- */
/* The wire-facing half of invariant 10, asserted on the request that would    */
/* have left the device. No socket anywhere in this file — RecordingConnection */
/* is what the injectable `open` seam exists for.                              */
/* -------------------------------------------------------------------------- */

class HttpTelemetryTransportTest {

    private val body = """{"v":"1.0.0","events":[]}""".encodeToByteArray()

    @Test
    fun `posts the exact bytes with POST and the single content-type header`() {
        val connection = RecordingConnection("https://t.test/events")
        val opened = mutableListOf<String>()
        val transport = HttpTelemetryTransport { url ->
            opened.add(url)
            connection
        }

        runBlocking { transport.send(body, "https://t.test/events") }

        // The URL passes through untouched — Telemetry built it, the adapter
        // has no business rewriting it.
        assertEquals(listOf("https://t.test/events"), opened)
        assertEquals("POST", connection.requestMethod)
        assertContentEquals(body, connection.requestBody.toByteArray())
        // THE privacy assertion: content-type is the only header. No
        // Authorization, no cookie, no user-agent we compose, no identifier —
        // an entry appearing here is a privacy review, not a formatting nit.
        assertEquals(
            mapOf("content-type" to listOf("application/json")),
            connection.requestProperties,
        )
        assertTrue(connection.disconnected)
    }

    @Test
    fun `an http error status does not throw — fetch parity`() {
        // `fetch` resolves on a 500 and the web ignores the response entirely;
        // what the server thinks of a telemetry payload is never this app's
        // problem, let alone a child's.
        val connection = RecordingConnection(
            "https://t.test/events",
            status = 500,
            responseBody = "boom".encodeToByteArray(),
        )
        val transport = HttpTelemetryTransport { connection }

        runBlocking { transport.send(body, "https://t.test/events") }

        assertTrue(connection.disconnected)
    }

    @Test
    fun `a transport failure throws, so Telemetry can be the one to swallow it`() {
        val connection = RecordingConnection(
            "https://t.test/events",
            failResponse = IOException("no route to host"),
        )
        val transport = HttpTelemetryTransport { connection }

        assertFailsWith<IOException> {
            runBlocking { transport.send(body, "https://t.test/events") }
        }
        // Even a failed exchange releases its connection.
        assertTrue(connection.disconnected)
    }

    @Test
    fun `no process-wide cookie handler is installed`() {
        // HttpURLConnection only attaches cookies when a process-wide
        // CookieHandler exists, and this app never installs one — that absence
        // IS the `credentials, omit` posture, and it cannot be revoked
        // per-connection. This can only assert the test process, not the app
        // process on a device, but it documents the promise where a future
        // `CookieHandler.setDefault` in test scaffolding would trip it.
        assertNull(CookieHandler.getDefault())
    }
}
