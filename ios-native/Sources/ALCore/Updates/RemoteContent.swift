import Foundation

/* -------------------------------------------------------------------------- */
/* Remote content — a SPECIFIED but UNIMPLEMENTED seam (D13).                   */
/*                                                                             */
/* WHAT DIED IN THE PORT, and it is a real loss. The PWA shipped a live JS      */
/* bundle over the air. The DPLA §3.3.1(B) carve-out permits that precisely     */
/* because it is *interpreted code run by Apple's WebKit or JavaScriptCore*. A  */
/* native SwiftUI app has no WebKit in the loop and its UI is compiled machine  */
/* code: there is no legal and no technical way to hot-swap it. Fixing a typo   */
/* in French copy, a layout bug, or a wrong exercise icon now costs a store     */
/* release (~24h review) where it used to cost a same-day OTA.                  */
/*                                                                             */
/* WHAT MAY LEGITIMATELY COME BACK, and the line must be held hard. The         */
/* distinction 2.5.2 draws is *code that "introduces or changes features or     */
/* functionality"* versus *data interpreted by features the reviewed binary     */
/* already contains*. A JSON list of French words rendered by an exercise       */
/* engine App Review already saw is data — it does not need the §3.3.1(B)       */
/* carve-out at all, because it is not code.                                    */
/*                                                                             */
/*   ALLOWED over the air — new entries in LETTER_WORDS / SYLLABLE_WORDS /      */
/*   SYLLABLE_GRID_ROWS, new VO clips, retuned SYLLABLE_TIERS /                 */
/*   FIRST_LETTER_LEVELS / SOUND_LEVELS / REWARD_CURVE numbers, corrected       */
/*   French strings THAT ALREADY EXIST AS KEYS IN THE SHIPPED BINARY.           */
/*                                                                             */
/*   NEVER over the air — a new ExerciseId, a new SyllableMode, a new screen, a */
/*   new string key with no shipped fallback, ANYTHING UNDER Licensing/, the    */
/*   price, or a feature dormant at submission. Each is 2.3.1 or 2.5.2          */
/*   territory and each is a store release.                                     */
/*                                                                             */
/* NOTHING IS FETCHED HERE. Behaviour is frozen for this port, and shipping a   */
/* content channel is a new capability with its own App Review exposure and its */
/* own signing-key operational burden. The types and the guard exist so the     */
/* decision can be made later against a written line; `fetch` throws            */
/* `.notImplemented` and must keep throwing until that decision is taken        */
/* deliberately (money.md §8 decision G).                                       */
/* -------------------------------------------------------------------------- */

/// The manifest a future content channel would serve. Decodable so the schema is
/// pinned now, while it costs nothing.
public struct ContentManifest: Decodable, Equatable, Sendable {
    /// The app rejects anything it does not know. Whole-payload rejection: never
    /// partially apply — half a word list is a corrupt game.
    public let schema: Int
    public let version: String
    public let url: URL
    /// sha256 of the payload.
    public let checksum: String
    /// Lowest app version that can run this payload. The `versionAtLeast` guard,
    /// unchanged from `updates.ts` except for what it names: before the port it
    /// compared the *native shell* against a *bundle* requirement; there is only
    /// one version now.
    public let minNative: String?
    /// Ed25519 over (schema|version|checksum), verified with a public key
    /// compiled into the binary — first-party, no vendor, no SDK, for the same
    /// reason `PurchaseStore.swift` has no vendor in it (Kids Category 1.3 bans
    /// third-party PII and device information; the cheapest compliance story is
    /// to have no third party). `JSONDecoder` reads it from base64.
    public let signature: Data

    public init(
        schema: Int, version: String, url: URL, checksum: String,
        minNative: String?, signature: Data
    ) {
        self.schema = schema
        self.version = version
        self.url = url
        self.checksum = checksum
        self.minNative = minNative
        self.signature = signature
    }
}

public enum RemoteContentRejection: Equatable, Sendable {
    /// A schema this binary does not know how to interpret.
    case unknownSchema(Int)
    /// The payload needs a newer app than the one installed.
    case needsNewerApp(minNative: String, installed: String)
}

public enum RemoteContentError: Error, Equatable, Sendable {
    /// D13. There is no code download natively and there is no content channel
    /// yet. This is not a stub to be filled in casually — see the file header.
    case notImplemented
}

public enum RemoteContent {
    /// The schema versions this binary can interpret. Empty until a channel
    /// exists, so every manifest is rejected — which is the correct behaviour for
    /// an unimplemented seam.
    public static let knownSchemas: Set<Int> = []

    /**
     * Refuse a payload that needs a newer app.
     *
     * The guard that stops the one genuinely dangerous failure mode: content
     * calling a feature the installed binary does not have, which is a white
     * screen on a child's tablet with no way back. When it trips, the fix is a
     * store release, not an OTA.
     *
     * Port of `compatible(m, nativeVersion)`. `!m.minNative` is falsy for both
     * `undefined` and `""`, so an empty string passes.
     */
    public static func compatible(_ m: ContentManifest, appVersion: String) -> Bool {
        guard let minNative = m.minNative, !minNative.isEmpty else { return true }
        return versionAtLeast(appVersion, minNative)
    }

    /// Whole-payload accept/reject. Pure, so the line above is testable without
    /// a network, a key or a device.
    public static func accept(
        _ m: ContentManifest,
        appVersion: String,
        knownSchemas: Set<Int> = RemoteContent.knownSchemas
    ) -> RemoteContentRejection? {
        guard knownSchemas.contains(m.schema) else { return .unknownSchema(m.schema) }
        guard compatible(m, appVersion: appVersion) else {
            return .needsNewerApp(minNative: m.minNative ?? "", installed: appVersion)
        }
        return nil
    }

    /**
     * The fetch seam. **Deliberately unimplemented — see the file header.**
     *
     * When it is built: an ephemeral `URLSession` with cookies off
     * (`credentials: "omit"`), signature and checksum verified before anything is
     * written, applied at NEXT LAUNCH only (swapping content under a child who is
     * halfway through a round is the worst possible moment), last-known-good kept
     * so a payload that does not reach first paint rolls back, every failure
     * silent — a failed check must be indistinguishable from a normal launch to
     * the family using the app — at most a `Telemetry.reportError(_, "remote-content")`.
     */
    public static func fetch(manifestURL: URL) async throws -> ContentManifest {
        throw RemoteContentError.notImplemented
    }
}
