import Foundation
import Testing

@testable import ALCore

// money.md §6.9 — four review-time rules turned into build-time ones.
//
// Cheap, and the only mechanism that survives a refactor by somebody who has not
// read D4 or D12. Each scan is a one-line grep with a paragraph of reason behind
// it; the reason is in the test name.

private func swiftFiles(under directory: URL) -> [URL] {
    guard
        let walker = FileManager.default.enumerator(
            at: directory, includingPropertiesForKeys: nil)
    else { return [] }
    return walker.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }.sorted {
        $0.path < $1.path
    }
}

private let alCoreRoot: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALCoreTests/MoneySourceScanTests.swift
    .deletingLastPathComponent()  // …/Tests/ALCoreTests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/ios-native
    .appendingPathComponent("Sources")
    .appendingPathComponent("ALCore")

@Suite("money — source scans")
struct MoneySourceScanTests {

    @Test("the scan can find the sources it is meant to scan")
    func rootExists() {
        #expect(FileManager.default.fileExists(atPath: alCoreRoot.path))
        #expect(!swiftFiles(under: alCoreRoot.appendingPathComponent("Licensing")).isEmpty)
        #expect(!swiftFiles(under: alCoreRoot.appendingPathComponent("Telemetry")).isEmpty)
        #expect(!swiftFiles(under: alCoreRoot.appendingPathComponent("Updates")).isEmpty)
    }

    /// D6. The trial clock takes an injected `TimeSource`; a 14-day offline grace
    /// whose clock cannot be advanced in a test is one nobody has ever verified.
    @Test("Licensing reads no system clock directly")
    func licensingHasNoDirectClockRead() throws {
        for file in swiftFiles(under: alCoreRoot.appendingPathComponent("Licensing")) {
            let source = try String(contentsOf: file, encoding: .utf8)
            #expect(
                !source.contains("Date()"),
                "\(file.lastPathComponent) constructs a date directly; inject TimeSource")
            #expect(
                !source.contains("Date.now"),
                "\(file.lastPathComponent) reads the system clock directly")
            #expect(
                !source.contains("DispatchTime.now"),
                "\(file.lastPathComponent) reads the system clock directly")
        }
    }

    /// D12 / invariant 10. Telemetry must not be able to name the per-install
    /// identifier, let alone send it.
    @Test("Telemetry never names the device identity")
    func telemetryDoesNotTouchDeviceIdentity() throws {
        for file in swiftFiles(under: alCoreRoot.appendingPathComponent("Telemetry")) {
            let source = try String(contentsOf: file, encoding: .utf8)
            #expect(
                !source.contains("deviceId"),
                "\(file.lastPathComponent) names the per-install identifier")
            #expect(
                !source.contains("DeviceIdentity"),
                "\(file.lastPathComponent) reaches into the device-identity module")
        }
    }

    /// D12. There is no API that accepts an arbitrary key, so no reviewer has to
    /// notice one being added — but a dictionary sneaking into the queue or the
    /// encoder would reopen the hole silently.
    @Test("Telemetry contains no string-keyed dictionary anywhere")
    func telemetryHasNoStringKeyedDictionary() throws {
        for file in swiftFiles(under: alCoreRoot.appendingPathComponent("Telemetry")) {
            let source = try String(contentsOf: file, encoding: .utf8)
            #expect(
                !source.contains("[String:"),
                "\(file.lastPathComponent) declares a string-keyed dictionary")
            #expect(
                !source.contains("Dictionary<String"),
                "\(file.lastPathComponent) declares a string-keyed dictionary")
        }
    }

    /// D4. The whole point of the `PurchaseStore` seam is that `swift test` runs
    /// the entitlement machine on a Mac with no store, no network and no signing.
    @Test("ALCore imports no store SDK, anywhere")
    func alCoreDoesNotImportStoreKit() throws {
        // Matched on real import STATEMENTS, not on prose: this file's siblings
        // are allowed — required, in fact — to write the rule down in a comment.
        let banned: Set<String> = ["StoreKit", "UIKit", "AppKit", "WebKit"]
        for file in swiftFiles(under: alCoreRoot) {
            let source = try String(contentsOf: file, encoding: .utf8)
            for line in source.split(separator: "\n", omittingEmptySubsequences: false) {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard trimmed.hasPrefix("import ") else { continue }
                let module = String(trimmed.dropFirst("import ".count))
                    .trimmingCharacters(in: .whitespaces)
                #expect(
                    !banned.contains(module),
                    "\(file.lastPathComponent) imports \(module) — that belongs in ALPlatform")
            }
        }
    }

    /// money.md R7. The web's `webStore` reported `paid: true` unconditionally
    /// *and the provider persisted it*. In a native build that is a silent unlock
    /// for everyone, so the paid-true double is fenced behind `#if DEBUG`.
    @Test("the paid-true preview store is fenced out of release builds")
    func previewStoreIsDebugOnly() throws {
        let file = alCoreRoot.appendingPathComponent("Licensing/PurchaseStore.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        let previewIndex = try #require(source.range(of: "struct PreviewPurchaseStore"))
        let fenceIndex = try #require(source.range(of: "#if DEBUG"))
        #expect(fenceIndex.lowerBound < previewIndex.lowerBound)
        #expect(source.contains("#endif"))
        // And the release default is the one that fails open via the trial clock.
        #expect(source.contains("struct StubPurchaseStore"))
    }
}
