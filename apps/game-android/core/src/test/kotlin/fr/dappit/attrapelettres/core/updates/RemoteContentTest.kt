package fr.dappit.attrapelettres.core.updates

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The seam is specified and UNIMPLEMENTED (the iOS app's D13; same posture
// here). These tests pin the guard (which is real) and the refusal (which must
// stay a refusal until the content channel is a deliberate decision with its
// own review exposure).

private fun manifest(
    schema: Int = 1,
    version: String = "0.2.0",
    minNative: String? = null,
): ContentManifest = ContentManifest(
    schema = schema,
    version = version,
    url = "https://cdn.test/$version.json",
    checksum = "abc123",
    minNative = minNative,
    signature = byteArrayOf(0x01, 0x02),
)

class RemoteContentTest {

    @Test
    fun `no minNative means any app version may apply it`() {
        assertTrue(RemoteContent.compatible(manifest(minNative = null), appVersion = "0.1.0"))
        // `!m.minNative` is falsy for "" too.
        assertTrue(RemoteContent.compatible(manifest(minNative = ""), appVersion = "0.1.0"))
    }

    @Test
    fun `minNative refuses a payload that needs a newer app`() {
        assertTrue(RemoteContent.compatible(manifest(minNative = "1.3.0"), appVersion = "1.3.0"))
        assertTrue(RemoteContent.compatible(manifest(minNative = "1.3.0"), appVersion = "1.4.0"))
        assertTrue(!RemoteContent.compatible(manifest(minNative = "1.3.0"), appVersion = "1.2.9"))
        assertTrue(!RemoteContent.compatible(manifest(minNative = "1.10.0"), appVersion = "1.9.0"))
    }

    @Test
    fun `an unknown schema is rejected whole — never partially applied`() {
        assertEquals(
            RemoteContentRejection.UnknownSchema(99),
            RemoteContent.accept(manifest(schema = 99), appVersion = "9.9.9", knownSchemas = setOf(1)),
        )
        assertNull(RemoteContent.accept(manifest(schema = 1), appVersion = "9.9.9", knownSchemas = setOf(1)))
    }

    @Test
    fun `the schema check runs before the version check`() {
        // Both wrong: the schema is the reason, because an unknown schema
        // cannot be interpreted at all.
        assertEquals(
            RemoteContentRejection.UnknownSchema(99),
            RemoteContent.accept(
                manifest(schema = 99, minNative = "9.0.0"),
                appVersion = "0.1.0",
                knownSchemas = setOf(1),
            ),
        )
    }

    @Test
    fun `the needs-newer-app rejection names both versions`() {
        assertEquals(
            RemoteContentRejection.NeedsNewerApp(minNative = "2.0.0", installed = "1.0.0"),
            RemoteContent.accept(manifest(minNative = "2.0.0"), appVersion = "1.0.0", knownSchemas = setOf(1)),
        )
    }

    /**
     * The shipped `KNOWN_SCHEMAS` is empty, so nothing is applicable. That is
     * the correct behaviour for an unimplemented seam and it must not be
     * "fixed" by adding a schema without building the channel.
     */
    @Test
    fun `out of the box, no manifest is applicable`() {
        assertTrue(RemoteContent.KNOWN_SCHEMAS.isEmpty())
        assertEquals(
            RemoteContentRejection.UnknownSchema(1),
            RemoteContent.accept(manifest(), appVersion = "9.9.9"),
        )
    }

    @Test
    fun `the manifest decodes from the documented JSON shape`() {
        val json = """
            {"schema":1,"version":"0.2.0","url":"https://cdn.test/0.2.0.json",
             "checksum":"sha256-deadbeef","minNative":"1.3.0","signature":"AQI="}
        """.trimIndent()
        val decoded = Json.decodeFromString(ContentManifest.serializer(), json)
        assertEquals(1, decoded.schema)
        assertEquals("0.2.0", decoded.version)
        assertEquals("https://cdn.test/0.2.0.json", decoded.url)
        assertEquals("sha256-deadbeef", decoded.checksum)
        assertEquals("1.3.0", decoded.minNative)
        assertContentEquals(byteArrayOf(0x01, 0x02), decoded.signature)
    }

    @Test
    fun `minNative is optional in the wire shape`() {
        val json = """
            {"schema":1,"version":"0.2.0","url":"https://cdn.test/0.2.0.json",
             "checksum":"c","signature":"AQI="}
        """.trimIndent()
        val decoded = Json.decodeFromString(ContentManifest.serializer(), json)
        assertNull(decoded.minNative)
    }

    /**
     * The port removes live updates and cannot bring them back. `fetch`
     * throwing `NotImplemented` is the written form of that; if this test ever
     * needs changing, a capability decision is being made.
     */
    @Test
    fun `fetch is not implemented, and that is the decision`() = runTest {
        assertFailsWith<RemoteContentError.NotImplemented> {
            RemoteContent.fetch("https://cdn.test/manifest.json")
        }
    }
}
