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

    /// Where a household id can arrive from with nobody watching: iCloud
    /// key-value store, i.e. this Apple ID's other devices. Held for the app's
    /// lifetime because it carries an observer.
    private let households: HouseholdDirectory = UbiquitousHouseholdDirectory()

    /// This device's id, used as the tie-break `by` on a pairing stamp. The
    /// same value `ProfileStore` keys its counters on — never sent anywhere
    /// (`DeviceIdentity`'s header); it travels only inside a stamp this device
    /// and the family's own devices compare.
    private let device: DeviceIdentity

    init() {
        platform = PlatformEnvironment()
        audio = LiveAudioEngine.live()
        haptics = PlatformHaptics.shipping
        device = DeviceIdentity(kv: platform.kv)
    }

    /// Once, at launch (the root view's `.task`).
    func start() {
        // `Telemetry.shared` — the TS module-level singleton the screens reach.
        platform.install()
        // Invariant 1: the first tap must not pay the 10–30 ms engine start.
        audio.prewarm()
        storeObservation = platform.startStoreObservation()
        // BEFORE the first sync, so a device that iCloud has already told
        // about the family's household exchanges rosters with the right one
        // instead of pushing a lone household and merging on the next resume.
        reconcileHousehold()
        households.observe { [weak self] claim in
            // Another device published while this one was running. Adopt and
            // exchange immediately; a parent who just paired the iPad should
            // not have to background the app to see it take.
            guard let self else { return }
            self.platform.sync.reconcile(with: claim)
            self.platform.profiles.syncNow()
        }
        // The web syncs on mount; a dead endpoint is a silent no-op.
        platform.profiles.syncNow()
        Task { await platform.entitlement.refresh() }
    }

    /* -- pairing ------------------------------------------------------------*/

    /**
     * Settle which household this device belongs to, and publish the answer.
     *
     * Writes back whenever the local claim wins, so two devices converge from
     * both directions rather than one of them deferring forever. Total: with
     * no iCloud account the read is nil, the local claim wins by default, and
     * the write goes to a local plist nobody reads. Nothing fails, nothing
     * surfaces, and the QR still works.
     */
    func reconcileHousehold() {
        let winner = platform.sync.reconcile(with: households.read())
        guard let winner else { return }
        if households.read() != winner { households.write(winner) }
    }

    /**
     * A scanned QR or a shared link arrived. True if it was ours and valid.
     *
     * Stamped with now, so it outranks both what this device held and what the
     * family's other devices hold — the deliberate act wins, which is the
     * whole reason the stamp exists (`HouseholdClaim`).
     */
    @discardableResult
    func open(_ url: URL) -> Bool {
        guard let id = PairingLink.household(from: url) else { return false }
        guard platform.sync.join(id, at: platform.time.nowMillis, by: device.deviceId()) else {
            return false
        }
        if let claim = platform.sync.claim() { households.write(claim) }
        // The local roster merges INTO the joined household on this call. It is
        // what makes joining non-destructive; see `HouseholdClaim`'s header.
        platform.profiles.syncNow()
        return true
    }

    /**
     * The link this device hands out, minting a household on first use.
     *
     * Only ever creates when there is none — `createHousehold()` overwrites,
     * and re-rolling the id every time the screen opened would silently orphan
     * a family that had already paired. nil when the build has no endpoint, so
     * the screen says so rather than showing a code that pairs with nothing.
     */
    func pairingLink() -> URL? {
        guard platform.configuration.syncEndpoint != nil else { return nil }
        let id = platform.sync.householdId() ?? platform.sync.createHousehold()
        if let claim = platform.sync.claim() { households.write(claim) }
        return PairingLink.url(household: id)
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
            // Resume: re-check the licence and exchange rosters. Reconcile
            // first — the other phone may have paired while this one was in
            // the background, and syncing the old household would push a
            // roster nobody will read.
            reconcileHousehold()
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
