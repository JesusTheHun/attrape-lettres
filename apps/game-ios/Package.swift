// swift-tools-version: 6.0
import PackageDescription

// Attrape-Lettres — native iOS port.
//
// Layered so that everything except the last mile is testable with `swift test`
// on the host, no simulator, no Xcode project:
//
//   ALCore  pure logic  — types, content, levels, rewards, storage, sync, licensing
//   ALArt   SwiftUI     — the SVG path parser, mascots, exercise icons
//   ALUI    SwiftUI     — exercises, components, screens
//
// ALArt/ALUI build for macOS too (SwiftUI is cross-platform) which is what makes
// host-side testing of the geometry possible. Anything genuinely iOS-only sits
// behind a protocol in ALCore with a platform implementation.

let package = Package(
    name: "AttrapeLettres",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ALCore", targets: ["ALCore"]),
        .library(name: "ALArt", targets: ["ALArt"]),
        .library(name: "ALUI", targets: ["ALUI"]),
        .library(name: "ALPlatform", targets: ["ALPlatform"]),
    ],
    targets: [
        .target(
            name: "ALCore",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "ALArt",
            dependencies: ["ALCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // No `resources:` — ALUI genuinely has none. It carried an empty
        // `Resources/` with a `.gitkeep` from the scaffold, and SwiftPM duly
        // emitted a bundle containing exactly one hidden file, which `codesign`
        // rejects outright: "bundle format unrecognized, invalid, or
        // unsuitable". `swift build` never noticed, because it does not sign;
        // only the iOS build did. Re-add this line together with a real
        // resource, never ahead of one.
        .target(
            name: "ALUI",
            dependencies: ["ALCore", "ALArt"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // The iOS adapters — StoreKit, URLSession, UserDefaults, AVFoundation,
        // CoreHaptics. Imported only by App/. Living in the package rather than
        // the Xcode target is the whole point: otherwise CI never compiles it.
        .target(
            name: "ALPlatform",
            dependencies: ["ALCore"],
            // The baked voice-over lives here. `Resources/vo/` is STAGED by
            // `scripts/stage-vo.sh`, not committed: the 845 clips already exist
            // once in the repo under `src/vo/clips/` and a second 17 MB copy in
            // git buys nothing. The clip *manifest* is committed, so the tests
            // that matter (every utterance the app can speak has a clip) run
            // whether or not the audio has been staged.
            resources: [.process("Resources")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // `swift run IconForge` — bakes the app icon out of `AppIcon.swift`
        // into both products. A tool, not a dependency: nothing links it, the
        // Xcode target never sees it, and it exists so that the 1024 PNG in the
        // asset catalog has a source that can be re-rendered.
        .executableTarget(
            name: "IconForge",
            dependencies: ["ALUI"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "ALCoreTests",
            dependencies: ["ALCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "ALArtTests",
            dependencies: ["ALArt"],
            resources: [.process("Resources")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "ALUITests",
            dependencies: ["ALUI"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "ALPlatformTests",
            dependencies: ["ALPlatform"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
