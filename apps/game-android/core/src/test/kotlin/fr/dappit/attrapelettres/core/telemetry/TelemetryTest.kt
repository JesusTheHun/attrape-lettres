package fr.dappit.attrapelettres.core.telemetry

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.platform.FixedAppVersion
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* 1:1 port of apps/game-web/src/telemetry.test.ts, via the Swift              */
/* TelemetryTests.swift.                                                       */
/*                                                                             */
/* Two of the 13 TS cases are DROPPED as unrepresentable in Kotlin, not as     */
/* unimportant:                                                                */
/*   • "silently drops any property not on the allowlist" — it passed          */
/*     `childName: "Léa"` through an `as unknown as TelemetryProps` cast, and  */
/*     there is no Kotlin cast that would compile against a data class of      */
/*     eight named fields. Replaced by TelemetryClosureTest.                   */
/*   • "drops non-finite numbers rather than sending null" — there is no `Int` */
/*     NaN. Replaced by the same file's reflection test.                       */
/* -------------------------------------------------------------------------- */

private fun telemetry(
    scope: CoroutineScope,
    transport: TelemetryTransport = NullTelemetryTransport,
    kv: InMemoryKVStore = InMemoryKVStore(),
    endpoint: String? = "https://t.test",
) = Telemetry(
    endpoint = endpoint,
    transport = transport,
    kv = kv,
    appVersion = FixedAppVersion("0.1.0"),
    scope = scope,
)

private val RecordingTelemetryTransport.Sent.envelope: JsonObject
    get() = Json.parseToJsonElement(json).jsonObject

private val JsonObject.events: List<JsonObject>
    get() = getValue("events").jsonArray.map { it.jsonObject }

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

private fun JsonObject.number(key: String): Int? = get(key)?.jsonPrimitive?.int

class TelemetryConsentTest {

    @Test
    fun `starts unanswered, and unanswered is not consent`() = runTest {
        val t = telemetry(backgroundScope)
        assertFalse(t.consentAnswered)
        assertFalse(t.hasConsent)
    }

    @Test
    fun `records a refusal distinctly from never having asked`() = runTest {
        val t = telemetry(backgroundScope)
        t.setConsent(false)
        assertTrue(t.consentAnswered)
        assertFalse(t.hasConsent)
    }

    @Test
    fun `consent is opt-in — the stored value is what says yes, never its absence`() = runTest {
        // CJEU Planet49: a pre-ticked box is not consent. The only string that
        // reads as "yes" is the one this app writes when a parent ticks it.
        val kv = InMemoryKVStore()
        val t = telemetry(backgroundScope, kv = kv)
        assertNull(kv.string(TelemetryConsent.KEY))
        assertFalse(t.hasConsent)
        t.setConsent(true)
        assertEquals("1", kv.string(TelemetryConsent.KEY))
        assertTrue(t.hasConsent)
        t.setConsent(false)
        assertEquals("0", kv.string(TelemetryConsent.KEY))
        assertFalse(t.hasConsent)
    }

    @Test
    fun `sends nothing at all without consent`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.track(
            TelemetryEvent.EXERCISE_STARTED,
            TelemetryProps(exercise = ExerciseId.READ_IMAGE, level = 1),
        )
        t.flush()
        t.awaitPendingSends()
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `drops anything already queued the moment consent is withdrawn`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.setConsent(true)
        t.track(
            TelemetryEvent.EXERCISE_STARTED,
            TelemetryProps(exercise = ExerciseId.READ_IMAGE, level = 1),
        )
        t.setConsent(false)
        t.flush()
        t.awaitPendingSends()
        assertTrue(transport.sent.isEmpty())
    }
}

class TelemetryPayloadTest {

    @Test
    fun `sends the event name, allowlisted numbers and the app version — nothing else`() =
        runTest {
            val transport = RecordingTelemetryTransport()
            val t = telemetry(backgroundScope, transport)
            t.setConsent(true)
            t.track(
                TelemetryEvent.SESSION_COMPLETED,
                TelemetryProps(
                    exercise = ExerciseId.READ_IMAGE,
                    level = 2,
                    rounds = 8,
                    perfect = 6,
                    points = 12,
                ),
            )
            t.flush()
            t.awaitPendingSends()

            val call = transport.sent.single()
            assertEquals("https://t.test/events", call.url)
            val envelope = call.envelope
            assertEquals("0.1.0", envelope.text("v"))
            val event = envelope.events.single()
            assertEquals("session_completed", event.text("event"))
            val props = event.getValue("props").jsonObject
            assertEquals(5, props.size)
            assertEquals(2, props.number("level"))
            assertEquals(8, props.number("rounds"))
            assertEquals(6, props.number("perfect"))
            assertEquals(12, props.number("points"))
            assertEquals("read-image", props.text("exercise"))

            // The wire ORDER is load-bearing: NUMERIC_KEYS first, then
            // `exercise`, exactly what `sanitize` + `JSON.stringify` produced.
            // Three clients feed one events table; a reordered encoder here
            // would be invisible until somebody diffed two days of logs.
            assertEquals(
                """{"v":"0.1.0","events":[{"event":"session_completed","props":{"level":2,"rounds":8,"perfect":6,"points":12,"exercise":"read-image"}}]}""",
                call.json,
            )
        }

    @Test
    fun `batches, and a flush with an empty queue is a no-op`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.setConsent(true)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.flush()
        t.flush()
        t.awaitPendingSends()
        assertEquals(1, transport.sent.size)
        assertEquals(2, transport.sent.single().envelope.events.size)
    }

    @Test
    fun `flushes on its own once the batch fills`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.setConsent(true)
        repeat(Telemetry.BATCH_SIZE) { t.track(TelemetryEvent.SHOP_OPENED) }
        t.awaitPendingSends()
        assertEquals(1, transport.sent.size)
        assertEquals(Telemetry.BATCH_SIZE, transport.sent.single().envelope.events.size)
    }

    @Test
    fun `carries no cookies, no credentials and nothing in the URL`() = runTest {
        // The Kotlin analogue of `credentials: "omit"`. The transport signature
        // is (ByteArray, String) — there is no header bag and no credential to
        // omit — so what is left to assert is that nothing is smuggled through
        // the URL itself.
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.setConsent(true)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.flush()
        t.awaitPendingSends()

        val url = URI(transport.sent.single().url)
        assertNull(url.query)
        assertNull(url.userInfo)
        assertNull(url.fragment)
        assertEquals("/events", url.path)
    }

    @Test
    fun `a transport failure is swallowed, not surfaced`() = runTest {
        val transport = RecordingTelemetryTransport(failing = true)
        val t = telemetry(backgroundScope, transport)
        t.setConsent(true)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.flush()
        t.awaitPendingSends()
        // Attempted, threw, nobody noticed — and the scope is still alive, which
        // is the half that matters: a telemetry error must not take the app's
        // coroutine scope down with it.
        assertEquals(1, transport.sent.size)
    }
}

class TelemetryErrorReportTest {

    @Test
    fun `are sent WITHOUT consent — they carry no identifier, so they are not personal data`() =
        runTest {
            val transport = RecordingTelemetryTransport()
            val t = telemetry(backgroundScope, transport)
            assertFalse(t.hasConsent)
            t.reportError(IllegalStateException("boom"), site = "test")
            t.awaitPendingSends()

            val call = transport.sent.single()
            assertEquals("https://t.test/errors", call.url)
            assertEquals("boom", call.envelope.text("message"))
            assertEquals("test", call.envelope.text("where"))
            assertEquals("0.1.0", call.envelope.text("v"))
        }

    @Test
    fun `a throwable brings the stack the web reads off e-dot-stack`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.reportError(IllegalStateException("boom"))
        t.awaitPendingSends()
        val stack = transport.sent.single().envelope.text("stack").orEmpty()
        assertTrue(stack.contains("IllegalStateException"), stack)
        assertTrue(stack.length <= 2000)
    }

    @Test
    fun `truncate a runaway message instead of shipping it whole`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.reportError(IllegalStateException("x".repeat(5000)))
        t.awaitPendingSends()
        assertEquals(300, transport.sent.single().envelope.text("message")?.length)
    }

    @Test
    fun `truncate the site and the stack too`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.reportError("short", site = "w".repeat(500), stack = "s".repeat(9000))
        t.awaitPendingSends()
        val envelope = transport.sent.single().envelope
        assertEquals(60, envelope.text("where")?.length)
        assertEquals(2000, envelope.text("stack")?.length)
    }

    @Test
    fun `accept a non-Error throw without crashing the game`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport)
        t.reportError("plain string")
        t.awaitPendingSends()
        val envelope = transport.sent.single().envelope
        assertEquals("plain string", envelope.text("message"))
        assertEquals("unknown", envelope.text("where"))
        assertEquals("", envelope.text("stack"))
    }
}

class TelemetryInertTest {

    @Test
    fun `is inert — a dev build posts nowhere`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport, endpoint = null)
        t.setConsent(true)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.flush()
        t.reportError("boom")
        t.awaitPendingSends()
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `an empty endpoint string is as inert as no endpoint`() = runTest {
        // The TS guard is `if (!endpoint()) return`, and the test stubs
        // VITE_TELEMETRY_URL to "" — which is falsy. An empty string must be as
        // inert as an absent one, or a mis-set build config posts to "/events".
        val transport = RecordingTelemetryTransport()
        val t = telemetry(backgroundScope, transport, endpoint = "")
        t.setConsent(true)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.flush()
        t.reportError("boom")
        t.awaitPendingSends()
        assertTrue(transport.sent.isEmpty())
    }
}
