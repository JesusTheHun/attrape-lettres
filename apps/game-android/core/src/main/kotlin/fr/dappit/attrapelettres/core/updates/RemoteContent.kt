package fr.dappit.attrapelettres.core.updates

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/* -------------------------------------------------------------------------- */
/* Remote content — a SPECIFIED but UNIMPLEMENTED seam (the iOS app's D13;     */
/* the situation is identical here and the line is held the same way).         */
/*                                                                             */
/* WHAT DIED IN THE PORT, and it is a real loss. The PWA shipped a live JS     */
/* bundle over the air. A native app's UI is compiled machine code: there is   */
/* no technical way to hot-swap it, and on both stores no permitted one —      */
/* Apple's §3.3.1(B) carve-out covered interpreted code run by WebKit, and     */
/* Play's Device and Network Abuse policy draws the same line around code      */
/* fetched outside Play. Fixing a typo in French copy, a layout bug, or a      */
/* wrong exercise icon now costs a store release where it used to cost a       */
/* same-day OTA.                                                               */
/*                                                                             */
/* WHAT MAY LEGITIMATELY COME BACK, and the line must be held hard. The        */
/* distinction the policies draw is code that "introduces or changes features  */
/* or functionality" versus data interpreted by features the reviewed binary   */
/* already contains. A JSON list of French words rendered by an exercise       */
/* engine review already saw is data — it is not code.                         */
/*                                                                             */
/*   ALLOWED over the air — new entries in LETTER_WORDS / SYLLABLE_WORDS /     */
/*   SYLLABLE_GRID_ROWS, new VO clips, retuned SYLLABLE_TIERS /                */
/*   FIRST_LETTER_LEVELS / SOUND_LEVELS / REWARD_CURVE numbers, corrected      */
/*   French strings THAT ALREADY EXIST AS KEYS IN THE SHIPPED BINARY.          */
/*                                                                             */
/*   NEVER over the air — a new ExerciseId, a new SyllableMode, a new screen,  */
/*   a new string key with no shipped fallback, ANYTHING UNDER licensing/,     */
/*   the price, or a feature dormant at submission. Each is store-policy       */
/*   territory (2.3.1 / 2.5.2 on iOS, and their Play equivalents) and each is  */
/*   a store release.                                                          */
/*                                                                             */
/* NOTHING IS FETCHED HERE. Behaviour is frozen for this port, and shipping a  */
/* content channel is a new capability with its own review exposure and its    */
/* own signing-key operational burden. The types and the guard exist so the    */
/* decision can be made later against a written line; `fetch` throws           */
/* `RemoteContentError.NotImplemented` and must keep throwing until that       */
/* decision is taken deliberately.                                             */
/* -------------------------------------------------------------------------- */

/**
 * The manifest a future content channel would serve. Serializable so the
 * schema is pinned now, while it costs nothing.
 *
 * Deliberately NOT a `data class`: `signature` is a `ByteArray`, and the
 * generated structural equality would compare it by reference — an equality
 * that lies is worse than none, and nothing needs manifest equality until a
 * channel exists.
 */
@Serializable
class ContentManifest(
    /**
     * The app rejects anything it does not know. Whole-payload rejection: never
     * partially apply — half a word list is a corrupt game.
     */
    val schema: Int,
    val version: String,
    // The iOS port models this as a Foundation URL; here it stays the wire
    // string — :core deliberately has no networking, so a parsed URL type
    // would buy nothing before the channel exists.
    val url: String,
    /** sha256 of the payload. */
    val checksum: String,
    /**
     * Lowest app version that can run this payload. The `versionAtLeast`
     * guard, unchanged from `updates.ts` except for what it names: before the
     * port it compared the *native shell* against a *bundle* requirement;
     * there is only one version now.
     */
    val minNative: String? = null,
    /**
     * Ed25519 over (schema|version|checksum), verified with a public key
     * compiled into the binary — first-party, no vendor, no SDK, for the same
     * reason `PurchaseStore` has no vendor in it (a kids app's cheapest
     * compliance story is to have no third party). Read from base64, the same
     * wire shape Swift's `JSONDecoder` gives `Data`.
     */
    @Serializable(with = Base64ByteArraySerializer::class)
    val signature: ByteArray,
)

/** `Data` decodes from a base64 string for free in Swift; kotlinx needs this told. */
internal object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("fr.dappit.attrapelettres.core.updates.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

sealed class RemoteContentRejection {
    /** A schema this binary does not know how to interpret. */
    data class UnknownSchema(val schema: Int) : RemoteContentRejection()

    /** The payload needs a newer app than the one installed. */
    data class NeedsNewerApp(val minNative: String, val installed: String) : RemoteContentRejection()
}

sealed class RemoteContentError(message: String) : Exception(message) {
    /**
     * There is no code download natively and there is no content channel yet.
     * This is not a stub to be filled in casually — see the file header.
     */
    class NotImplemented : RemoteContentError("Remote content is a specified, unimplemented seam.")
}

object RemoteContent {
    /**
     * The schema versions this binary can interpret. Empty until a channel
     * exists, so every manifest is rejected — which is the correct behaviour
     * for an unimplemented seam.
     */
    val KNOWN_SCHEMAS: Set<Int> = emptySet()

    /**
     * Refuse a payload that needs a newer app.
     *
     * The guard that stops the one genuinely dangerous failure mode: content
     * calling a feature the installed binary does not have, which is a white
     * screen on a child's tablet with no way back. When it trips, the fix is a
     * store release, not an OTA.
     *
     * Port of `compatible(m, nativeVersion)`. `!m.minNative` is falsy for both
     * `undefined` and `""`, so an empty string passes — `isNullOrEmpty` is the
     * same pair.
     */
    fun compatible(m: ContentManifest, appVersion: String): Boolean {
        val minNative = m.minNative
        if (minNative.isNullOrEmpty()) return true
        return versionAtLeast(appVersion, minNative)
    }

    /**
     * Whole-payload accept/reject. Pure, so the line above is testable without
     * a network, a key or a device.
     */
    fun accept(
        m: ContentManifest,
        appVersion: String,
        knownSchemas: Set<Int> = KNOWN_SCHEMAS,
    ): RemoteContentRejection? {
        if (m.schema !in knownSchemas) return RemoteContentRejection.UnknownSchema(m.schema)
        if (!compatible(m, appVersion)) {
            return RemoteContentRejection.NeedsNewerApp(
                minNative = m.minNative ?: "",
                installed = appVersion,
            )
        }
        return null
    }

    /**
     * The fetch seam. **Deliberately unimplemented — see the file header.**
     *
     * When it is built: a cookie-less client (the web's `credentials: "omit"`),
     * signature and checksum verified before anything is written, applied at
     * NEXT LAUNCH only (swapping content under a child who is halfway through
     * a round is the worst possible moment), last-known-good kept so a payload
     * that does not reach first paint rolls back, every failure silent — a
     * failed check must be indistinguishable from a normal launch to the
     * family using the app — at most a telemetry error report.
     */
    suspend fun fetch(manifestUrl: String): ContentManifest {
        throw RemoteContentError.NotImplemented()
    }
}
