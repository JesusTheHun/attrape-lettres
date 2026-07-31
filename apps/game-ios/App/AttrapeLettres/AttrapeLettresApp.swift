import ALCore
import ALPlatform
import ALUI
import SwiftUI

// The whole app shell. Everything of substance lives in the SwiftPM package one
// directory up, which is what keeps it testable on the host — see DECISIONS.md
// D1. Resist adding code here; if it belongs to the app it belongs in a target.
//
// This file wires exactly four things, all through `AppEnvironment`:
//   1. the environment injections every screen reads (`ProfileStore`,
//      `EntitlementModel`, `Telemetry`, reduce-motion, the viewport width);
//   2. the protocol values `RootView` threads by parameter (audio, kv, time);
//   3. `start()` at launch — Telemetry.shared, audio prewarm (invariant 1),
//      the StoreKit transaction listener, the first sync + licence refresh;
//   4. the `scenePhase` fan-out (refresh on active, telemetry flush on
//      background).

@main
struct AttrapeLettresApp: App {
    @Environment(\.scenePhase) private var scenePhase

    /// `@State` so the graph is built once and keeps its identity across scene
    /// updates. Nothing in its construction can fail or block (invariant 11 —
    /// see `AppEnvironment.swift`'s header).
    @State private var appEnvironment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            RootView(
                audio: appEnvironment.audio,
                kv: appEnvironment.platform.kv,
                time: appEnvironment.platform.time
            )
            .environment(appEnvironment.platform.profiles)
            .environment(appEnvironment.platform.entitlement)
            .environment(appEnvironment.platform.telemetry)
            // D32: the OBSERVING reduce-motion source — the ALUI default only
            // samples, it never hears a mid-session change.
            .alReduceMotion(appEnvironment.platform.reduceMotion)
            // D16: `vw` is 1 % of the WINDOW, injected once at the root.
            .alViewportFromSelf()
            .task { appEnvironment.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            appEnvironment.scenePhaseChanged(to: phase)
        }
    }
}
