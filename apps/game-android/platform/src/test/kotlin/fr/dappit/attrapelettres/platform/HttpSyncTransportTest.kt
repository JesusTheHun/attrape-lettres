package fr.dappit.attrapelettres.platform

import fr.dappit.attrapelettres.core.sync.WireRoster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/* -------------------------------------------------------------------------- */
/* HttpSyncTransport is a SKETCH (A7) and these tests pin exactly that: the    */
/* stable halves behave — addressing, the falsy guards, the if-match rule, the */
/* TS-shaped errors — and the unfinished half fails SAFELY, as an Exception    */
/* ProfileStore.syncNow's swallow clause catches, never as a crash.            */
/* -------------------------------------------------------------------------- */

class HttpSyncTransportTest {

    private val payload = WireRoster(children = emptyList(), removed = emptyMap())

    private fun neverOpens(): (String) -> RecordingConnection = {
        fail("a disabled exchange must not open a connection")
    }

    @Test
    fun `no endpoint means disabled, silently — the payload comes straight back`() {
        val transport =
            HttpSyncTransport(endpoint = { null }, household = { "fam-1" }, open = neverOpens())
        assertSame(payload, runBlocking { transport.exchange(payload) })
    }

    @Test
    fun `an empty endpoint is falsy, exactly as in the TS`() {
        val transport =
            HttpSyncTransport(endpoint = { "" }, household = { "fam-1" }, open = neverOpens())
        assertSame(payload, runBlocking { transport.exchange(payload) })
    }

    @Test
    fun `no household means not joined — same silent no-op`() {
        val transport = HttpSyncTransport(
            endpoint = { "https://s.test" },
            household = { null },
            open = neverOpens(),
        )
        assertSame(payload, runBlocking { transport.exchange(payload) })
    }

    @Test
    fun `the document url is plain concatenation`() {
        // A URL builder would percent-escape the separator and change the path
        // the server sees; the TS template and the Swift port both concatenate.
        assertEquals(
            "https://s.test/household/fam-1",
            HttpSyncTransport.urlFor("https://s.test", "fam-1"),
        )
    }

    @Test
    fun `if-match rides the push only when the pull produced an etag`() {
        val bare = RecordingConnection("https://s.test/household/fam-1")
        HttpSyncTransport.applyPushHeaders(bare, etag = null)
        assertEquals(
            mapOf("content-type" to listOf("application/json")),
            bare.requestProperties,
        )

        // TS spreads the header conditionally and an empty string is falsy
        // there — mirrored, so a document created fresh pushes unconditionally.
        val empty = RecordingConnection("https://s.test/household/fam-1")
        HttpSyncTransport.applyPushHeaders(empty, etag = "")
        assertNull(empty.requestProperties["if-match"])

        val stamped = RecordingConnection("https://s.test/household/fam-1")
        HttpSyncTransport.applyPushHeaders(stamped, etag = "\"7\"")
        assertEquals(listOf("\"7\""), stamped.requestProperties["if-match"])
        assertEquals(listOf("application/json"), stamped.requestProperties["content-type"])
    }

    @Test
    fun `the pending codec fails as an exception the profile store swallows`() {
        val opened = mutableListOf<String>()
        val transport = HttpSyncTransport(
            endpoint = { "https://s.test" },
            household = { "fam-1" },
        ) { url ->
            opened.add(url)
            RecordingConnection(
                url,
                status = 200,
                responseBody = "{}".encodeToByteArray(),
                responseHeaders = mapOf("ETag" to "\"1\""),
            )
        }

        val error: Throwable = assertFailsWith<SyncContractNotLanded> {
            runBlocking { transport.exchange(payload) }
        }
        // An Exception (IOException), NOT an Error: ProfileStore.syncNow
        // catches Exception and carries on with the local roster, so wiring
        // this too early degrades to "device plays alone", never to a crash.
        assertTrue(error is Exception)
        // The GET went to the right address, and nothing else was attempted.
        assertEquals(listOf("https://s.test/household/fam-1"), opened)
    }

    @Test
    fun `a 404 means no document yet — and the encode gate holds before any push`() {
        val opened = mutableListOf<String>()
        val transport = HttpSyncTransport(
            endpoint = { "https://s.test" },
            household = { "fam-1" },
        ) { url ->
            opened.add(url)
            RecordingConnection(url, status = 404)
        }

        assertFailsWith<SyncContractNotLanded> {
            runBlocking { transport.exchange(payload) }
        }
        // Encoding happens before a push connection is opened: a payload that
        // cannot be serialised costs no socket, so the pull's connection is
        // the only one on record.
        assertEquals(1, opened.size)
    }

    @Test
    fun `a pull failure carries the TS error shape`() {
        val transport = HttpSyncTransport(
            endpoint = { "https://s.test" },
            household = { "fam-1" },
        ) { url -> RecordingConnection(url, status = 500) }

        val error = assertFailsWith<SyncHttpError> {
            runBlocking { transport.exchange(payload) }
        }
        assertEquals(500, error.status)
        assertEquals("sync pull 500", error.message)
    }
}
