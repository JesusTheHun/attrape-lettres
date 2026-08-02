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

    /// Non-nil while the pairing screen is up. `joined` distinguishes the two
    /// arrivals: a parent opening it to hand the code out, and a link that has
    /// just been accepted, which wants the confirmation instead of another QR.
    @State private var pairing: PairingSheet?

    var body: some Scene {
        WindowGroup {
            RootView(
                audio: appEnvironment.audio,
                kv: appEnvironment.platform.kv,
                time: appEnvironment.platform.time,
                // Read by « Suggérer une correction » and nothing else: a report
                // about a clip we re-baked last month is unactionable without it.
                version: appEnvironment.platform.appVersion,
                onPair: { pairing = PairingSheet(joined: false) }
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
            // A scanned QR, or a link shared to this device. An unrecognised
            // URL is ignored in silence: iOS only routes our own scheme here,
            // and a malformed one is not an error a family has to clear
            // (invariant 3's spirit).
            .onOpenURL { url in
                if appEnvironment.open(url) {
                    pairing = PairingSheet(joined: true)
                }
            }
            .sheet(item: $pairing) { sheet in
                PairingFlow(
                    joined: sheet.joined,
                    link: { appEnvironment.pairingLink() },
                    onDone: { pairing = nil }
                )
            }
        }
        .onChange(of: scenePhase) { _, phase in
            appEnvironment.scenePhaseChanged(to: phase)
        }
    }
}

/// `sheet(item:)` needs identity; the flag is the whole state.
struct PairingSheet: Identifiable {
    let joined: Bool
    var id: Bool { joined }
}

/**
 * The gate, then the screen.
 *
 * A parent opening this hands out the household credential and reaches a share
 * sheet, so guideline 1.3 applies exactly as it does to the paywall: the door
 * may be visible to a child, what is behind it may not be reachable by
 * tapping. `ParentalGateView` re-rolls its sum on every open, so a child who
 * watches once learns nothing.
 *
 * A LINK ARRIVING SKIPS THE GATE, deliberately. By then the join has already
 * happened — `AppEnvironment.open(_:)` ran before this sheet was raised — and
 * the screen is a confirmation, not a control. Gating a message a parent
 * cannot act on would be theatre, and it would leave the family unable to read
 * why their two phones now share a household.
 */
struct PairingFlow: View {
    let joined: Bool
    let link: () -> URL?
    let onDone: () -> Void

    /// Not persisted, and per presentation: passing once must buy nothing —
    /// see `ParentalGate.swift`'s header.
    @State private var passed = false

    var body: some View {
        if joined || passed {
            HouseholdPairingView(
                // Not minted on the confirmation path: this device already has
                // the household it just joined, and calling for a link there
                // would be a second one nobody asked for.
                link: joined ? nil : link(),
                joined: joined,
                onDone: onDone
            )
        } else {
            ParentalGateView(
                reason: Copy.Pairing.gateReason,
                onPass: { passed = true },
                onCancel: onDone
            )
        }
    }
}
