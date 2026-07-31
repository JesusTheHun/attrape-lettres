package fr.dappit.attrapelettres.core.telemetry

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.persistence.DeviceIdentity
import fr.dappit.attrapelettres.core.persistence.ProfileStorage
import fr.dappit.attrapelettres.core.platform.FixedAppVersion
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Invariant 10 as STRUCTURE, not as discipline (D12).                          */
/*                                                                             */
/* The TS maintained its allowlist with a `sanitize()` pass and a test that     */
/* cast `{ childName: "Léa" }` through `as unknown as TelemetryProps`. In       */
/* Kotlin that cast does not exist, so the guarantee moves into the type — and  */
/* these tests document the guarantee and fail the moment somebody widens it.   */
/* -------------------------------------------------------------------------- */

private val allowlist =
    setOf("exercise", "level", "rounds", "perfect", "points", "cost", "stage", "daysLeft")

/** A fully-populated props bag: every field set, so nothing can hide behind a null. */
private val fullProps = TelemetryProps(
    exercise = ExerciseId.SPELL_TWO_SYLLABLES_MIXED,
    level = 3,
    rounds = 9,
    perfect = 7,
    points = 21,
    cost = 40,
    stage = 6,
    daysLeft = 11,
)

/** The eight declared properties, minus the companion's statics and any synthetics. */
private fun propFields(): List<Field> =
    TelemetryProps::class.java.declaredFields.filterNot {
        Modifier.isStatic(it.modifiers) || it.isSynthetic
    }

private suspend fun emitEveryEvent(
    scope: CoroutineScope,
    kv: InMemoryKVStore = InMemoryKVStore(),
): RecordingTelemetryTransport {
    val transport = RecordingTelemetryTransport()
    val t = Telemetry(
        endpoint = "https://t.test",
        transport = transport,
        kv = kv,
        appVersion = FixedAppVersion("0.1.0"),
        scope = scope,
    )
    t.setConsent(true)
    for (event in TelemetryEvent.entries) {
        t.track(event, fullProps)
        t.flush()
    }
    t.awaitPendingSends()
    return transport
}

private fun RecordingTelemetryTransport.eventObjects() =
    sent.flatMap { record ->
        Json.parseToJsonElement(record.json).jsonObject.getValue("events").jsonArray
            .map { it.jsonObject }
    }

class TelemetryClosureTest {

    /**
     * The Kotlin replacement for the TS `childName: "Léa"` test. Adding a field
     * to [TelemetryProps] without touching this test and the encoder fails the
     * build's test run.
     */
    @Test
    fun `the reflected field set IS the allowlist`() {
        val names = propFields().map { it.name }.toSet()
        assertEquals(allowlist, names)
        assertEquals(8, names.size)
        assertEquals(allowlist, TelemetryProps.allowedKeys.toSet())
    }

    /**
     * The escape hatch invariant 10 exists to prevent is a `String` field or a
     * `Map<String, …>` — either one is how `{ child: profile.name }` reaches a
     * server. Neither is representable here, and this is the test that says so.
     */
    @Test
    fun `no property is free text and none is a string-keyed bag`() {
        for (field in propFields()) {
            assertFalse(
                field.type == String::class.java,
                "${field.name} is a String — a free-text escape hatch has opened",
            )
            assertFalse(
                Map::class.java.isAssignableFrom(field.type),
                "${field.name} is a Map — a string-keyed bag has opened",
            )
            assertTrue(
                field.type == Int::class.javaObjectType || field.type == ExerciseId::class.java,
                "${field.name} is a ${field.type.simpleName}: props are counts and one closed enum",
            )
        }
    }

    @Test
    fun `the encoded key set is a subset of the allowlist, for every event`() = runTest {
        val transport = emitEveryEvent(backgroundScope)
        assertEquals(TelemetryEvent.entries.size, transport.sent.size)

        val wireNames = TelemetryEvent.entries.map { it.wire }.toSet()
        for (record in transport.sent) {
            val envelope = Json.parseToJsonElement(record.json).jsonObject
            assertEquals(setOf("v", "events"), envelope.keys)
            for (event in envelope.getValue("events").jsonArray.map { it.jsonObject }) {
                assertEquals(setOf("event", "props"), event.keys)
                val props = event.getValue("props").jsonObject
                assertTrue(props.keys.all { it in allowlist }, props.keys.toString())
                // The event name is itself from the closed enum.
                assertTrue(event.getValue("event").jsonPrimitive.content in wireNames)
            }
        }
    }

    /**
     * D12's byte-level test. The roster holds six-year-olds' first names; a
     * per-install identifier would make the payload pseudonymous rather than
     * anonymous. Neither may appear in a single byte that leaves the device.
     *
     * This is structurally guaranteed — `Telemetry` never reads the roster key
     * and never names `DeviceIdentity` — and the test documents the guarantee
     * by handing telemetry the very store both of them live in.
     *
     * "Léa" and "Tom" are both asserted absent on purpose: one accented, one
     * pure ASCII, so an encoder that escaped non-ASCII could not sneak a name
     * past the test.
     */
    @Test
    fun `no child's name and no per-install id can appear in any payload`() = runTest {
        val kv = InMemoryKVStore(
            mapOf(
                // A roster shaped exactly like the one on disk, sitting in the
                // very store telemetry was handed.
                ProfileStorage.ROSTER_KEY to
                    """{"children":[{"id":"c1","name":"Léa","nameRev":3},{"id":"c2","name":"Tom","nameRev":1}],"activeId":"c1"}""",
                DeviceIdentity.STORAGE_KEY to "dad-phone",
            ),
        )
        val transport = emitEveryEvent(backgroundScope, kv)
        assertTrue(transport.sent.isNotEmpty())

        for (record in transport.sent) {
            val json = record.json
            for (forbidden in listOf(
                "Léa", "Tom", "nameRev", "activeId", "dad-phone", "name", "roster",
            )) {
                assertFalse(json.contains(forbidden), "$forbidden leaked into $json")
            }
        }
    }

    /**
     * The event path carries numbers and one closed-enum wire value. Nothing
     * else. `reportError` is the ONLY producer of free-form strings in the
     * module, and it is on a different path (`/errors`) and a different API.
     */
    @Test
    fun `no free text on the event path`() = runTest {
        val transport = emitEveryEvent(backgroundScope)
        val exerciseIds = ExerciseId.entries.map { it.wire }.toSet()

        for (event in transport.eventObjects()) {
            val props = event.getValue("props").jsonObject
            for ((key, value) in props) {
                val primitive = value.jsonPrimitive
                if (key == "exercise") {
                    assertTrue(primitive.isString)
                    assertTrue(primitive.content in exerciseIds, primitive.content)
                } else {
                    assertFalse(
                        primitive.isString,
                        "prop $key is a string — an escape hatch has opened",
                    )
                    assertNotNull(
                        primitive.content.toIntOrNull(),
                        "prop $key is not a number: ${primitive.content}",
                    )
                }
            }
        }
    }

    /**
     * [TelemetryEvent] is a closed list of eleven wire names, six of which have
     * no producer today (money.md R10 — aspirational, ported whole). The order
     * is the TS tuple's order.
     */
    @Test
    fun `the event list is exactly the eleven wire names`() {
        assertEquals(
            listOf(
                "exercise_started", "session_completed", "shop_opened", "item_bought",
                "mascot_grown", "trial_started", "trial_expired", "paywall_shown",
                "purchase_completed", "purchase_failed", "purchase_restored",
            ),
            TelemetryEvent.entries.map { it.wire },
        )
    }

    /**
     * Empty props encode as an empty object, not as nulls: a field that is not
     * set is a field that is not sent. Asserted on the bytes that would have
     * left the device rather than on the encoder, because the encoder is
     * private on purpose — what matters is the payload, not the function.
     */
    @Test
    fun `unset fields are absent, never null`() = runTest {
        val transport = RecordingTelemetryTransport()
        val t = Telemetry(
            endpoint = "https://t.test",
            transport = transport,
            kv = InMemoryKVStore(),
            appVersion = FixedAppVersion("0.1.0"),
            scope = backgroundScope,
        )
        t.setConsent(true)
        t.track(TelemetryEvent.SHOP_OPENED)
        t.track(TelemetryEvent.SHOP_OPENED, TelemetryProps(level = 1))
        t.track(TelemetryEvent.SESSION_COMPLETED, fullProps)
        t.flush()
        t.awaitPendingSends()

        assertEquals(
            """{"v":"0.1.0","events":[{"event":"shop_opened","props":{}},{"event":"shop_opened","props":{"level":1}},{"event":"session_completed","props":{"level":3,"rounds":9,"perfect":7,"points":21,"cost":40,"stage":6,"daysLeft":11,"exercise":"spell-two-syllables-mixed"}}]}""",
            transport.sent.single().json,
        )
    }
}
