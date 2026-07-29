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
        .target(
            name: "ALUI",
            dependencies: ["ALCore", "ALArt"],
            resources: [.process("Resources")],
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
    ]
)
