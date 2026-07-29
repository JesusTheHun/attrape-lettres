import Foundation
import Testing

@testable import ALCore

// D13 / money.md §4.4. The seam is specified and UNIMPLEMENTED; these tests pin
// the guard (which is real) and the refusal (which must stay a refusal until the
// content channel is a deliberate decision with its own App Review exposure).

private func manifest(
    schema: Int = 1,
    version: String = "0.2.0",
    minNative: String? = nil
) -> ContentManifest {
    ContentManifest(
        schema: schema,
        version: version,
        url: URL(string: "https://cdn.test/\(version).json")!,
        checksum: "abc123",
        minNative: minNative,
        signature: Data([0x01, 0x02]))
}

@Suite("RemoteContent — the specified, unimplemented seam")
struct RemoteContentTests {

    @Test("no minNative means any app version may apply it")
    func noMinNativePasses() {
        #expect(RemoteContent.compatible(manifest(minNative: nil), appVersion: "0.1.0"))
        // `!m.minNative` is falsy for "" too.
        #expect(RemoteContent.compatible(manifest(minNative: ""), appVersion: "0.1.0"))
    }

    @Test("minNative refuses a payload that needs a newer app")
    func minNativeGuard() {
        #expect(RemoteContent.compatible(manifest(minNative: "1.3.0"), appVersion: "1.3.0"))
        #expect(RemoteContent.compatible(manifest(minNative: "1.3.0"), appVersion: "1.4.0"))
        #expect(!RemoteContent.compatible(manifest(minNative: "1.3.0"), appVersion: "1.2.9"))
        #expect(!RemoteContent.compatible(manifest(minNative: "1.10.0"), appVersion: "1.9.0"))
    }

    @Test("an unknown schema is rejected whole — never partially applied")
    func unknownSchemaRejected() {
        #expect(
            RemoteContent.accept(manifest(schema: 99), appVersion: "9.9.9", knownSchemas: [1])
                == .unknownSchema(99))
        #expect(RemoteContent.accept(manifest(schema: 1), appVersion: "9.9.9", knownSchemas: [1])
            == nil)
    }

    @Test("the schema check runs before the version check")
    func schemaBeforeVersion() {
        // Both wrong: the schema is the reason, because an unknown schema cannot
        // be interpreted at all.
        #expect(
            RemoteContent.accept(
                manifest(schema: 99, minNative: "9.0.0"), appVersion: "0.1.0", knownSchemas: [1])
                == .unknownSchema(99))
    }

    @Test("the needs-newer-app rejection names both versions")
    func needsNewerAppRejection() {
        #expect(
            RemoteContent.accept(
                manifest(minNative: "2.0.0"), appVersion: "1.0.0", knownSchemas: [1])
                == .needsNewerApp(minNative: "2.0.0", installed: "1.0.0"))
    }

    /// The shipped `knownSchemas` is empty, so nothing is applicable. That is the
    /// correct behaviour for an unimplemented seam and it must not be "fixed" by
    /// adding a schema without building the channel.
    @Test("out of the box, no manifest is applicable")
    func nothingIsApplicableYet() {
        #expect(RemoteContent.knownSchemas.isEmpty)
        #expect(RemoteContent.accept(manifest(), appVersion: "9.9.9") == .unknownSchema(1))
    }

    @Test("the manifest decodes from the documented JSON shape")
    func decodesFromJSON() throws {
        let json = #"""
            {"schema":1,"version":"0.2.0","url":"https://cdn.test/0.2.0.json",
             "checksum":"sha256-deadbeef","minNative":"1.3.0","signature":"AQI="}
            """#
        let decoded = try JSONDecoder().decode(ContentManifest.self, from: Data(json.utf8))
        #expect(decoded.schema == 1)
        #expect(decoded.version == "0.2.0")
        #expect(decoded.checksum == "sha256-deadbeef")
        #expect(decoded.minNative == "1.3.0")
        #expect(decoded.signature == Data([0x01, 0x02]))
    }

    @Test("minNative is optional in the wire shape")
    func minNativeOptional() throws {
        let json = #"""
            {"schema":1,"version":"0.2.0","url":"https://cdn.test/0.2.0.json",
             "checksum":"c","signature":"AQI="}
            """#
        let decoded = try JSONDecoder().decode(ContentManifest.self, from: Data(json.utf8))
        #expect(decoded.minNative == nil)
    }

    /// The port removes live updates and cannot bring them back (D13, money.md
    /// R4). `fetch` throwing `.notImplemented` is the written form of that; if
    /// this test ever needs changing, a capability decision is being made.
    @Test("fetch is not implemented, and that is the decision")
    func fetchIsNotImplemented() async {
        await #expect(throws: RemoteContentError.notImplemented) {
            _ = try await RemoteContent.fetch(
                manifestURL: URL(string: "https://cdn.test/manifest.json")!)
        }
    }
}
