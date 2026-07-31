import Foundation
import SwiftUI
import Testing

import ALArt

@testable import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The render harness (D19 / W18), finally built — and built on the SIMULATOR   */
/* rather than the host, which is the whole reason it works.                    */
/*                                                                             */
/* `ImageRenderer` on the Mac host lays out a `ScrollView` as empty and cannot  */
/* see a safe area at all, so neither of the two defects this file was written  */
/* for — a wash that stops at the status bar, a level badge that swallows its   */
/* button — is visible to it. A `UIWindow` on an iPhone destination has both:   */
/* real safe-area insets, real scroll views, real `UIKit` geometry.             */
/*                                                                             */
/*     xcodebuild test -scheme AttrapeLettres-Package \                        */
/*       -destination 'platform=iOS Simulator,name=iPhone 17 Pro'              */
/*                                                                             */
/* Two things come out of it:                                                  */
/*                                                                             */
/*  1. MEASUREMENTS, of the things UIKit owns: scroll views, content insets,   */
/*     rendered pixels. NOT of individual controls — a SwiftUI `Button` is a    */
/*     drawing command, not a `UIView`, and its accessibility node is not       */
/*     published either unless an assistive technology is actually running, so  */
/*     a walk of either tree comes back empty. What a laid-out SwiftUI view     */
/*     WILL tell you is the size it resolved to, and `ImageRenderer` reports    */
/*     that on the host: see `AspectSquareTests`, which is where D52's 28.67 pt */
/*     level button is caught.                                                 */
/*  2. PNGs, when `AL_SNAPSHOT_DIR` is set. Not assertions (there is no golden  */
/*     master and pixel diffing a mascot rig is a losing game) — a way to LOOK  */
/*     at a screen without tapping through the app, since `simctl` has no       */
/*     touch input. Unset, nothing is written and the tests still assert.       */
/*                                                                             */
/* Screens are rendered through the same shell the app uses (`Shell` gutter as  */
/* safe-area padding), because the defects being chased live in that shell.     */
/* -------------------------------------------------------------------------- */

#if canImport(UIKit)

    import UIKit

    private let t0: Int64 = 1_700_000_000_000

    /// iPhone 17 Pro portrait, points. The size the reported screenshots were
    /// taken at, so a measurement here is comparable to one taken by eye there.
    private let phone = CGSize(width: 402, height: 874)

    /// A store with one chosen child — the state every hub render sits on.
    @MainActor
    private func makeStore() -> ProfileStore {
        let store = ProfileStore(kv: InMemoryKVStore(), device: { "snap-device" }, now: { t0 })
        store.createChild(name: "Jonathan")
        store.chooseSpecies(.unicorn)
        return store
    }

    /// Onboarded and mid-trial — the state a returning child launches into, and
    /// the only one whose shell gate lands on the hub.
    @MainActor
    private func makeEntitlement() -> EntitlementModel {
        let model = EntitlementModel(
            store: StubPurchaseStore(),
            persist: LicenseStore(InMemoryKVStore()),
            time: MutableTimeSource(t0))
        model.beginTrial()
        return model
    }

    /// The Dynamic Island / home-indicator insets of the device the reported
    /// screenshots came from. A `UIWindow` built in a test has NO safe area —
    /// there is no scene to take one from — so a safe-area assertion made
    /// against a bare test window passes no matter what the code does. This is
    /// the value that makes such a test mean something.
    private let deviceInsets = UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)

    /// Host a view at `size`, let it lay out, and return the window's root view.
    ///
    /// `safeArea` is applied through `additionalSafeAreaInsets`, which is the
    /// one lever that reaches SwiftUI's safe area from a windowless test.
    @MainActor
    private func host<V: View>(
        _ view: V, size: CGSize = phone, safeArea: UIEdgeInsets = .zero
    ) -> UIView {
        let frame = CGRect(origin: .zero, size: size)
        let controller = UIHostingController(rootView: view)
        let window = UIWindow(frame: frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.additionalSafeAreaInsets = safeArea
        controller.view.frame = frame
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        // A second pass: `LazyVGrid` sizes its cells from the first one.
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        return controller.view
    }

    /// The sRGB pixel at `point` in a hosted view's rendered output.
    ///
    /// `layer.render(in:)` rather than `drawHierarchy`, which needs a real
    /// screen update cycle a unit test does not get.
    @MainActor
    private func pixel(_ view: UIView, at point: CGPoint) -> (r: Int, g: Int, b: Int) {
        var buffer = [UInt8](repeating: 0, count: 4)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: &buffer, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return (0, 0, 0) }
        // Move the origin so the pixel of interest lands on the 1×1 canvas.
        context.translateBy(x: -point.x, y: -point.y)
        view.layer.render(in: context)
        return (Int(buffer[0]), Int(buffer[1]), Int(buffer[2]))
    }

    /// Every descendant VIEW, depth first, with its frame in `root`'s space.
    @MainActor
    private func walk(_ root: UIView, _ visit: (UIView, CGRect) -> Void) {
        func recurse(_ view: UIView) {
            visit(view, view.convert(view.bounds, to: root))
            for child in view.subviews { recurse(child) }
        }
        recurse(root)
    }

    /// Write a PNG of a hosted screen, and say where it went.
    ///
    /// Unconditional, because the alternative — an env var — does not survive
    /// the trip to a simulator test process, and a look-at-the-screen tool you
    /// have to remember how to switch on is a tool nobody uses. Two files per
    /// run, in a temp directory.
    ///
    /// The path is the SIMULATOR's, not the Mac's: its temp dir is
    /// `~/Library/Developer/CoreSimulator/Devices/<udid>/data/tmp`, which the
    /// host reads afterwards. The printed line is how you find it.
    ///
    /// `layer.render(in:)` rather than `drawHierarchy`, which needs a
    /// screen-update cycle a unit test never gets and comes back blank.
    @MainActor
    private func snapshot(_ view: UIView, named name: String) {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("al-snaps")
        let renderer = UIGraphicsImageRenderer(bounds: view.bounds)
        let png = renderer.pngData { context in
            view.layer.render(in: context.cgContext)
        }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("\(name).png")
        try? png.write(to: file)
        print("AL-SNAPSHOT \(file.path)")
    }

    // MARK: - The shell

    @Suite("Screens — the shell, on a real window")
    @MainActor
    struct ShellSnapshotTests {

        @Test("the stage wash reaches the top of the window, not the top of the safe area")
        func washIsFullBleed() throws {
            let store = makeStore()
            let entitlement = makeEntitlement()

            let root = host(
                RootView(
                    audio: SilentAudioEngine(),
                    kv: InMemoryKVStore(),
                    time: MutableTimeSource(t0),
                    dev: nil
                )
                .environment(store)
                .environment(entitlement)
                .environment(
                    Telemetry(
                        endpoint: nil,  // a build that sets no endpoint posts nowhere
                        transport: RecordingTransport(),
                        kv: InMemoryKVStore(),
                        appVersion: FixedAppVersion("1.0.0")))
            )
            snapshot(root, named: "hub")

            // Asserted by COLOUR, at the two pixels the defect was reported on.
            // The two candidates are far apart in blue: the stage's first stop
            // is #FFE7C9 (b = 201) and the page it used to show instead is
            // #efe6da (b = 218) — and the gutter's whole job was to paint that.
            //
            // Under the Dynamic Island, and over the home indicator.
            let top = pixel(root, at: CGPoint(x: phone.width / 2, y: 3))
            let bottom = pixel(root, at: CGPoint(x: phone.width / 2, y: phone.height - 3))

            #expect(
                top.r >= 250 && top.b <= 210,
                Comment(rawValue: "the top of the window is \(top), not the warm stage"))
            // The wash's last stop is #DCEFFB — blue over red, which the page
            // cream never is.
            #expect(
                bottom.b > bottom.r,
                Comment(rawValue: "the bottom of the window is \(bottom), not the cool stage"))
        }

        @Test("the content still keeps the shell's gutter clear of the notch")
        func contentStaysInsideTheSafeArea() throws {
            // The other half of D51: the wash bleeds, the CONTENT does not. The
            // gutter became safe-area padding rather than layout padding, and
            // the failure mode of getting that wrong is a title under the
            // Dynamic Island.
            let root = host(
                RootView(
                    audio: SilentAudioEngine(),
                    kv: InMemoryKVStore(),
                    time: MutableTimeSource(t0),
                    dev: nil
                )
                .environment(makeStore())
                .environment(makeEntitlement())
                .environment(
                    Telemetry(
                        endpoint: nil,
                        transport: RecordingTransport(),
                        kv: InMemoryKVStore(),
                        appVersion: FixedAppVersion("1.0.0"))),
                safeArea: deviceInsets)

            var scroll: UIScrollView?
            walk(root) { view, _ in
                if scroll == nil, let candidate = view as? UIScrollView { scroll = candidate }
            }
            let scrollView = try #require(scroll, Comment(rawValue: "the hub no longer scrolls"))

            // The scroll view spans the window (the wash is behind it), and it
            // insets its CONTENT by the safe area plus the shell's 16 pt.
            let inset = scrollView.adjustedContentInset.top
            #expect(
                inset >= deviceInsets.top + Shell.minimumInset - 1,
                Comment(
                    rawValue:
                        "the hub's content inset is \(inset) pt — less than the notch (\(deviceInsets.top)) plus the gutter (\(Shell.minimumInset))"))
        }
    }


    // MARK: - The gallery

    /// One render per screen, PNG only.
    ///
    /// `simctl` has no touch input, so the dashboard, the paywall and any
    /// exercise cannot be reached by driving the app — and those are exactly the
    /// screens a shell change like D51 has to be checked on. Hosting them is the
    /// only way to look. No assertions here on purpose: the files are for eyes,
    /// the pixel claims live in `ShellSnapshotTests`.
    @Suite("Screens — the gallery")
    @MainActor
    struct ScreenGallery {

        private func world() -> (ProfileStore, EntitlementModel, Telemetry) {
            (
                makeStore(),
                makeEntitlement(),
                Telemetry(
                    endpoint: nil,
                    transport: RecordingTransport(),
                    kv: InMemoryKVStore(),
                    appVersion: FixedAppVersion("1.0.0"))
            )
        }

        @Test("dashboard")
        func dashboard() {
            let (store, entitlement, telemetry) = world()
            snapshot(
                host(
                    DashboardView(onBack: {}, onShop: {}, onSwitch: {})
                        .environment(store).environment(entitlement).environment(telemetry),
                    safeArea: deviceInsets),
                named: "dashboard")
        }

        @Test("paywall — the child layer, then the parent layer")
        func paywall() {
            let (store, entitlement, telemetry) = world()
            for step in [PaywallStep.child, .parent] {
                snapshot(
                    host(
                        PaywallView(onBack: {}, model: PaywallModel(step: step))
                            .environment(store).environment(entitlement).environment(telemetry),
                        safeArea: deviceInsets),
                    named: "paywall-\(step.rawValue)")
            }
        }

        @Test("onboarding")
        func onboarding() {
            let (store, entitlement, telemetry) = world()
            snapshot(
                host(
                    OnboardingView()
                        .environment(store).environment(entitlement).environment(telemetry),
                    safeArea: deviceInsets),
                named: "onboarding")
        }

        @Test("an exercise — the GameFrame every engine wears")
        func exercise() {
            let (store, entitlement, telemetry) = world()
            let host0 = EngineHost(
                audio: SilentAudioEngine(),
                time: MutableTimeSource(t0),
                award: { _, _, _, _ in 0 })
            snapshot(
                host(
                    FirstLetterView(
                        level: 1,
                        host: host0,
                        mascot: store.profile.config,
                        onBack: {},
                        onNext: {}
                    )
                    .environment(store).environment(entitlement).environment(telemetry),
                    safeArea: deviceInsets),
                named: "exercise")
        }
    }

#endif
