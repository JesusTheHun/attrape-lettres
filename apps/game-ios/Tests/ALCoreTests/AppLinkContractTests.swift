import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* The app target's plists, as a contract — the iOS twin of Android's           */
/* `ManifestContractTest`, and for the same reason: every claim here fails      */
/* ONLY on a device, quietly, in somebody's hands.                              */
/*                                                                             */
/*   * a scheme renamed on one side of the seam compiles, ships, and simply     */
/*     never opens the app — the QR scans, iOS shrugs, and a parent concludes   */
/*     the feature is broken;                                                   */
/*   * a missing ubiquity-kvstore entitlement makes NSUbiquitousKeyValueStore   */
/*     degrade to a local plist that never syncs, which is INDISTINGUISHABLE    */
/*     from "nobody has paired a second device yet";                            */
/*   * a camera usage description added by reflex is invisible in testing and   */
/*     expensive exactly once, in a Kids Category review.                       */
/*                                                                             */
/* A source scan rather than `Bundle.main`: these tests run on the host, where  */
/* the bundle is the test runner's and knows nothing about the app target.      */
/* -------------------------------------------------------------------------- */

/// `App/Config/`, NOT `App/AttrapeLettres/`. The app target is an Xcode 16
/// synchronised folder group: everything inside `AttrapeLettres/` joins the
/// target automatically, so an Info.plist there is copied as a resource AND
/// processed as the Info.plist —
///
///     error: Multiple commands produce '.../Attrape-Lettres.app/Info.plist'
///
/// Keeping both files one directory up is the fix that needs no membership
/// exception in the project file.
private func appFile(_ name: String) throws -> String {
    // Tests/ALCoreTests/ -> the package root -> App/Config/
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let url = root.appendingPathComponent("App/Config/\(name)")
    return try String(contentsOf: url, encoding: .utf8)
}

/// XML comments stripped, so the documentation in these files cannot be read
/// as the declaration. Android's manifest test learned this the same way.
private func body(_ text: String) -> String {
    var out = text
    while let start = out.range(of: "<!--"), let end = out.range(of: "-->", range: start.upperBound..<out.endIndex) {
        out.removeSubrange(start.lowerBound..<end.upperBound)
    }
    return out
}

@Suite("Info.plist — the scheme the QR encodes")
struct URLSchemeContractTests {

    @Test("the registered scheme is the one PairingLink mints")
    func schemeMatches() throws {
        let plist = body(try appFile("Info.plist"))
        #expect(
            plist.contains("<string>\(PairingLink.scheme)</string>"),
            "Info.plist does not register \(PairingLink.scheme); every pairing link would open nothing"
        )
    }

    @Test("the scheme is declared under CFBundleURLSchemes, not merely mentioned")
    func declaredProperly() throws {
        let plist = body(try appFile("Info.plist"))
        #expect(plist.contains("CFBundleURLTypes"))
        #expect(plist.contains("CFBundleURLSchemes"))
    }

    @Test("the app still asks for no camera")
    func noCameraPermission() throws {
        // A QR is shown here and read by the system camera. An in-app scanner
        // would need this key, and this app's permission set is empty on
        // purpose — see the file's own header.
        let plist = body(try appFile("Info.plist"))
        #expect(!plist.contains("NSCameraUsageDescription"))
    }
}

@Suite("Entitlements — what makes pairing automatic")
struct EntitlementsContractTests {

    @Test("iCloud key-value store is entitled, or same-Apple-ID pairing silently does nothing")
    func kvStoreEntitled() throws {
        let entitlements = body(try appFile("AttrapeLettres.entitlements"))
        #expect(entitlements.contains("com.apple.developer.ubiquity-kvstore-identifier"))
    }

    @Test("no CloudKit container, and no iCloud Documents")
    func nothingHeavier() throws {
        // Three short strings do not need a container. Anything more here is a
        // second backing store beside the API we already run.
        let entitlements = body(try appFile("AttrapeLettres.entitlements"))
        #expect(!entitlements.contains("com.apple.developer.icloud-container-identifiers"))
        #expect(!entitlements.contains("CloudDocuments"))
    }
}

@Suite("The project points at both files")
struct ProjectWiringTests {

    @Test("every app build configuration carries the plist and the entitlements")
    func bothConfigurations() throws {
        let project = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("App/AttrapeLettres.xcodeproj/project.pbxproj"),
            encoding: .utf8
        )
        // Debug and Release both, or the feature works in development and
        // vanishes in the build that ships.
        //
        // Whole settings, not substrings: `GENERATE_INFOPLIST_FILE` ends in
        // `INFOPLIST_FILE` and a naive count reads 4 where the answer is 2.
        func settings(_ name: String) -> Int {
            project
                .split(separator: "\n")
                .filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix("\(name) = ") }
                .count
        }
        #expect(settings("INFOPLIST_FILE") == 2)
        #expect(settings("CODE_SIGN_ENTITLEMENTS") == 2)
        // Generation must stay off: it and INFOPLIST_FILE collide rather than
        // merge, and turning it back on would silently drop every key the
        // plist carries — the URL scheme included.
        #expect(!project.contains("GENERATE_INFOPLIST_FILE = YES"))
    }

    @Test("both configurations sign with the enrolled team, automatically")
    func signingTeam() throws {
        // 2YJBB225MB is the active Apple Developer enrolment. It is pinned
        // because this is a setting Xcode REWRITES on its own: opening the
        // project with a different team selected, or on a Mac that holds more
        // than one signing identity, silently swaps it — and this Mac holds
        // two (the other is 267VC765WT). The failure is not a build error, it
        // is a build signed by the wrong entity, which surfaces at upload.
        //
        // `Automatic` for the same reason it always is: the alternative pins a
        // provisioning-profile UUID in the project file, and profiles expire.
        let project = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("App/AttrapeLettres.xcodeproj/project.pbxproj"),
            encoding: .utf8
        )
        func settings(_ line: String) -> Int {
            project
                .split(separator: "\n")
                .filter { $0.trimmingCharacters(in: .whitespaces) == line }
                .count
        }
        #expect(settings("DEVELOPMENT_TEAM = 2YJBB225MB;") == 2)
        #expect(settings("CODE_SIGN_STYLE = Automatic;") == 2)
        #expect(
            !project.contains("PROVISIONING_PROFILE_SPECIFIER"),
            "a pinned profile has been added; it will expire and break the build for everyone"
        )
    }

    @Test("the asset catalog's app icon is named, in both configurations")
    func appIconNamed() throws {
        // `Assets.xcassets` can hold a perfect 1024 icon and ship a blank home
        // screen: without this setting Xcode compiles the catalog, injects no
        // `CFBundleIconName`, and the build is valid, signed and iconless. The
        // PNG itself is checked in `AppIconTests` (ALUITests); this is the wire
        // between it and the bundle.
        let project = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("App/AttrapeLettres.xcodeproj/project.pbxproj"),
            encoding: .utf8
        )
        let named = project
            .split(separator: "\n")
            .filter { $0.trimmingCharacters(in: .whitespaces) == "ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;" }
        #expect(named.count == 2, "the app icon is named in \(named.count) of 2 build configurations")
    }

    @Test("the sync endpoint is set, or the whole app is inert")
    func endpointPresent() throws {
        let plist = body(try appFile("Info.plist"))
        #expect(plist.contains("<key>ALSyncURL</key>"))
        #expect(
            plist.contains("<string>https://api.attrape-lettres.app</string>"),
            "an empty ALSyncURL normalises to absent and SyncClient goes inert"
        )
    }
}
