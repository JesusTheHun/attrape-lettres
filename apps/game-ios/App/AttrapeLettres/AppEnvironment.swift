import ALCore
import ALPlatform
import ALUI
import SwiftUI

#if canImport(UIKit)
    import UIKit
#endif

/* -------------------------------------------------------------------------- */
/* The composition root — the ONE place the live adapters are chosen and the    */
/* app graph is assembled. Everything of substance lives in                     */
/* `ALPlatform.PlatformEnvironment` (kv → licences → entitlement → telemetry →  */
/* sync → profiles, host-tested); this file only adds the audio engine, the     */
/* lifecycle fan-out and the StoreKit listener, because those are the pieces    */
/* that need an app to hang from.                                              */
/*                                                                             */
/* ── INVARIANT 11 ──────────────────────────────────────────────────────────── */
/* Nothing in here is a hard precondition for launching. `PlatformEnvironment`  */
/* is non-throwing and constructible with no store, no network and a corrupt    */
/* KV (a bad blob migrates to a fresh roster — `ProfileStorage`'s loose         */
/* decoding); `StoreKitPurchaseStore` answers `.unreachable` when the store     */
/* does not, and `canPlay(.unknown) == true` keeps the child playing.           */
/* `startStoreObservation()` is nil where there is no StoreKit. Every call in   */
/* `start()` is fire-and-forget: a flat network fails silent, never closed.     */
/*                                                                             */
/* ── Endpoints ─────────────────────────────────────────────────────────────── */
/* `URLSessionTransports` reads `ALSyncURL` / `ALTelemetryURL` from the         */
/* Info.plist (`PlatformConfiguration.fromBundle()`). The project uses          */
/* `GENERATE_INFOPLIST_FILE = YES` and sets them in the AttrapeLettres          */
/* target's build settings, Debug and Release alike:                            */
/*                                                                             */
/*     INFOPLIST_KEY_ALSyncURL      = https://api.attrape-lettres.app           */
/*     INFOPLIST_KEY_ALTelemetryURL = ""                                        */
/*                                                                             */
/* SYNC IS ON. Telemetry is still empty, deliberately: an endpoint here would   */
/* only be honest once the consent the Kids Category requires is asked for and  */
/* recorded, and that is a product decision, not a build setting.               */
/*                                                                             */
/* An empty value means absent (`PlatformConfiguration.normalised`), so a build */
/* that clears either key fails silent rather than fails closed — nothing waits */
/* on the network and no child is stopped. Debug points at the same stack as    */
/* Release because there is only one; the day a staging stack exists            */
/* (`STACK=attrape-staging ./scripts/deploy.sh`), Debug should point at it, or  */
/* development traffic lands in real families' table.                          */
/*                                                                             */
/* (This file does not own the pbxproj; the settings are recorded here and in   */
/* the phase report.)                                                          */
/* -------------------------------------------------------------------------- */

@MainActor
final class AppEnvironment {

    /// kv, time, licences, entitlement, telemetry, sync, profiles — built once.
    let platform: PlatformEnvironment

    /// The SFX graph + clip bank + speech fallback + single-flight channel.
    /// ONE instance for the app lifetime (never per exercise).
    let audio: LiveAudioEngine

    /// The haptics seam. `.off` is the shipping mode — the PWA has zero
    /// haptics and behaviour is frozen (`AudioPort.swift`'s note). Held here so
    /// the day a product decision lands, only this line and the consumer move.
    let haptics: PlatformHaptics

    /// `Transaction.updates` — held for the app's whole lifetime. Without it,
    /// Ask-to-Buy approvals, Family Sharing grants and revocations never land.
    private var storeObservation: Task<Void, Never>?

    init() {
        platform = PlatformEnvironment()
        audio = LiveAudioEngine.live()
        haptics = PlatformHaptics.shipping
    }

    /// Once, at launch (the root view's `.task`).
    func start() {
        // `Telemetry.shared` — the TS module-level singleton the screens reach.
        platform.install()
        // Invariant 1: the first tap must not pay the 10–30 ms engine start.
        audio.prewarm()
        storeObservation = platform.startStoreObservation()
        // The web syncs on mount; a dead endpoint is a silent no-op.
        platform.profiles.syncNow()
        Task { await platform.entitlement.refresh() }
    }

    /// The `scenePhase` fan-out — the web's visibility/lifecycle listeners.
    func scenePhaseChanged(to phase: ScenePhase) {
        switch phase {
        case .active:
            // The OS setting can change while backgrounded (D32's observer
            // covers the foreground; this covers the gap).
            platform.reduceMotion.refresh()
            // Re-arm the audio graph BEFORE a finger can arrive. `unlock()` on
            // the tap path already heals a suspended graph, but invariant 1 is
            // that the tap must not pay for it — and there are two ways to reach
            // a suspended one: an interruption that ended with
            // `shouldResume == false` (`LiveAudioEngine.handle`), and a launch
            // whose session activation lost to the app not being foreground yet.
            // Idempotent: ready graph in, one Bool read out.
            audio.prewarm()
            // Resume: re-check the licence and exchange rosters.
            platform.profiles.syncNow()
            Task { await platform.entitlement.refresh() }
        case .background:
            flushTelemetry()
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    /// The web's `keepalive: true` beacon on pagehide: a background task keeps
    /// the process alive long enough for the queued batch to leave.
    private func flushTelemetry() {
        let telemetry = platform.telemetry
        #if canImport(UIKit)
            let application = UIApplication.shared
            var token = UIBackgroundTaskIdentifier.invalid
            token = application.beginBackgroundTask(withName: "al-telemetry-flush") {
                if token != .invalid {
                    application.endBackgroundTask(token)
                    token = .invalid
                }
            }
            telemetry.flush()
            Task { @MainActor in
                await telemetry.awaitPendingSends()
                if token != .invalid {
                    application.endBackgroundTask(token)
                    token = .invalid
                }
            }
        #else
            telemetry.flush()
        #endif
    }
}
