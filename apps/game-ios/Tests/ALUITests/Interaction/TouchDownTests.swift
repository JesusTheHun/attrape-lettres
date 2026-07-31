import CoreGraphics
import SwiftUI
import Testing

@testable import ALUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// The touch-down primitive (D5) — invariant 1's owner. Everything here drives
// the recogniser's coordinator core directly; no UI is simulated. The contract
// under test comes from the PWA (`src/components/Tile.tsx`: `onPointerDown`
// fires the feedback synchronously; a click is down + up on the element), not
// from the Swift implementation.

@Suite("TouchDown — the pointerdown of the port")
@MainActor
struct TouchDownCoreTests {

    @Test("onDown fires at touch-DOWN (.began), not at touch-up")
    func downFiresAtBegan() {
        var downs = 0
        var ups = 0
        let core = TouchDownCore(onDown: { downs += 1 }, onUp: { _ in ups += 1 })

        core.began()
        // The finger is still down: the feedback has already fired, nothing
        // waited for the lift. A touch-UP implementation fails on this line.
        #expect(downs == 1)
        #expect(ups == 0)

        core.ended(at: CGPoint(x: 1, y: 1), in: CGRect(x: 0, y: 0, width: 100, height: 100))
        #expect(downs == 1)
        #expect(ups == 1)
    }

    @Test("onDown is synchronous — the value is observable immediately after the call")
    func downIsSynchronous() {
        var log: [String] = []
        let core = TouchDownCore(onDown: { log.append("down") }, onUp: { _ in })

        log.append("pre")
        core.began()
        log.append("post")

        // Routed through @State, a Task, DispatchQueue.main.async or an
        // animation block, "down" would land after "post" (or not yet at all)
        // and this exact ordering breaks.
        #expect(log == ["pre", "down", "post"], Comment(rawValue: "onDown must run inside began(), not on a later runloop turn"))
    }

    @Test("onUp distinguishes lift-inside from lift-outside")
    func upInsideVersusOutside() {
        var insides: [Bool] = []
        let bounds = CGRect(x: 0, y: 0, width: 100, height: 100)
        let core = TouchDownCore(onDown: {}, onUp: { insides.append($0) })

        core.began()
        core.ended(at: CGPoint(x: 10, y: 10), in: bounds)

        core.began()
        core.ended(at: CGPoint(x: 150, y: 50), in: bounds) // slid off, then lifted

        #expect(insides == [true, false])
    }

    @Test("a system cancellation (scroll pan took the touch) is a lift-outside — the web's pointercancel")
    func cancellationIsOutside() {
        var insides: [Bool] = []
        let core = TouchDownCore(onDown: {}, onUp: { insides.append($0) })

        core.began()
        core.cancelled()

        #expect(insides == [false])
    }

    @Test("no phantom events: an up without a down is dropped, a second down while tracking is dropped")
    func phantomEventsAreDropped() {
        var downs = 0
        var ups = 0
        let core = TouchDownCore(onDown: { downs += 1 }, onUp: { _ in ups += 1 })
        let bounds = CGRect(x: 0, y: 0, width: 10, height: 10)

        core.ended(at: .zero, in: bounds) // up with no down
        core.cancelled() // cancel with no down
        #expect(ups == 0)

        core.began()
        core.began() // duplicate down while the finger is already down
        #expect(downs == 1)

        core.ended(at: .zero, in: bounds)
        core.ended(at: .zero, in: bounds) // duplicate up
        #expect(ups == 1)
    }

    /// The shape the scroll fix relies on: once a drag has been ruled a scroll,
    /// the lift that follows must not become a tap. `cancelled()` clears
    /// `isTracking`, so the `.ended` UIKit still delivers is dropped — and the
    /// caller sees exactly ONE `onUp`, reporting `false`.
    ///
    /// Without this, `Picker`'s card would leave `active` toggled and `ShopItem`
    /// would open a try-on dialog at the end of a flick (D49).
    @Test("a cancel mid-touch swallows the lift that follows it")
    func cancelThenLiftIsOneOutsideUp() {
        var insides: [Bool] = []
        let bounds = CGRect(x: 0, y: 0, width: 100, height: 100)
        let core = TouchDownCore(onDown: {}, onUp: { insides.append($0) })

        core.began()
        core.cancelled() // the pan won: this touch is a scroll
        // UIKit still delivers `.ended` at the lift, and the finger IS inside.
        core.ended(at: CGPoint(x: 50, y: 50), in: bounds)

        #expect(insides == [false], Comment(rawValue: "a scrolled touch must never report inside: true"))

        // …and the surface still works for the NEXT, genuine tap.
        core.began()
        core.ended(at: CGPoint(x: 50, y: 50), in: bounds)
        #expect(insides == [false, true])
    }

    @Test("the core tracks a full down-up cycle and is reusable for the next tap")
    func reusableAcrossTaps() {
        var downs = 0
        let core = TouchDownCore(onDown: { downs += 1 }, onUp: { _ in })
        let bounds = CGRect(x: 0, y: 0, width: 10, height: 10)

        for _ in 0..<3 {
            core.began()
            core.ended(at: .zero, in: bounds)
        }
        #expect(downs == 3)
    }
}

#if canImport(UIKit)

// iOS-only: the recogniser configuration that MAKES this a touch-down
// primitive, and the delegate rule that keeps scrolling alive. These compile
// for the host but only run on an iOS destination (UIKit does not exist on
// macOS) — the host suite covers the coordinator core above.
@Suite("TouchDown — UIKit recogniser wiring")
@MainActor
struct TouchDownRecognizerTests {

    @Test("zero minimumPressDuration: .began IS touch-down")
    func recognizerFiresAtTouchDown() {
        let coordinator = TouchDownSurface.Coordinator(
            core: TouchDownCore(onDown: {}, onUp: { _ in })
        )
        let recognizer = TouchDownSurface.configuredRecognizer(coordinator: coordinator)

        #expect(recognizer.minimumPressDuration == 0)
        #expect(recognizer.cancelsTouchesInView == false)
        #expect(recognizer.delegate === coordinator)
    }

    @Test("the recogniser never prevents another — a scroll view's pan can still win")
    func allowsSimultaneousRecognition() {
        let coordinator = TouchDownSurface.Coordinator(
            core: TouchDownCore(onDown: {}, onUp: { _ in })
        )
        let recognizer = TouchDownSurface.configuredRecognizer(coordinator: coordinator)

        #expect(coordinator.gestureRecognizer(
            recognizer,
            shouldRecognizeSimultaneouslyWith: UIPanGestureRecognizer()
        ))
    }

    // MARK: - Scroll versus tap (D49)

    /// `isDragging` and `isDecelerating` are read-only on `UIScrollView` and
    /// driven by its pan; a unit test cannot pan. Overriding them is the honest
    /// way to assert the RULE — that a scrolling ancestor means "not a tap" —
    /// without pretending to have simulated a finger. Whether a real SwiftUI
    /// `ScrollView` puts a `UIScrollView` on this path is a separate question,
    /// and it is `ShopScrollUITests` that answers it.
    private final class ScrollingStub: UIScrollView {
        // NB `stub`-prefixed: `isDragging`'s ObjC property name is `dragging`,
        // so a stored `var dragging` reads as an override attempt and will not
        // compile.
        var stubDragging = false
        var stubDecelerating = false
        override var isDragging: Bool { stubDragging }
        override var isDecelerating: Bool { stubDecelerating }
    }

    private func nest(_ leaf: UIView, under root: UIView, depth: Int) {
        var parent = root
        for _ in 0..<depth {
            let mid = UIView()
            parent.addSubview(mid)
            parent = mid
        }
        parent.addSubview(leaf)
    }

    @Test("the enclosing scroll view is found through intermediate views")
    func findsScrollViewAncestor() {
        let scroll = ScrollingStub()
        let catcher = UIView()
        nest(catcher, under: scroll, depth: 3)

        #expect(TouchDownSurface.Coordinator.enclosingScrollView(of: catcher) === scroll)
    }

    @Test("no scroll view above ⇒ nothing to ask, and never a cancel")
    func noScrollViewMeansNoCancel() {
        let catcher = UIView()
        UIView().addSubview(catcher)

        #expect(TouchDownSurface.Coordinator.enclosingScrollView(of: catcher) == nil)
        // Every exercise screen is this case: invariant 1 forbids a scroll view
        // over a tile grid, so the tap path must not gain a single behaviour
        // change from this fix.
        #expect(!TouchDownSurface.Coordinator.isScrolling(around: catcher))
        #expect(!TouchDownSurface.Coordinator.isScrolling(around: nil))
    }

    @Test("an idle scroll view is not scrolling — a tap in a shop that is standing still still taps")
    func idleScrollViewIsATap() {
        let scroll = ScrollingStub()
        let catcher = UIView()
        nest(catcher, under: scroll, depth: 2)

        #expect(!TouchDownSurface.Coordinator.isScrolling(around: catcher))
    }

    @Test("dragging or coasting ⇒ this touch is a scroll, not a tap")
    func draggingOrDeceleratingIsAScroll() {
        let scroll = ScrollingStub()
        let catcher = UIView()
        nest(catcher, under: scroll, depth: 2)

        scroll.stubDragging = true
        #expect(TouchDownSurface.Coordinator.isScrolling(around: catcher))

        // Momentum: iOS-wide, the first touch on a coasting scroll view stops it
        // and activates nothing underneath.
        scroll.stubDragging = false
        scroll.stubDecelerating = true
        #expect(TouchDownSurface.Coordinator.isScrolling(around: catcher))
    }

    /// End to end through the coordinator, which is what actually runs: a
    /// `.changed` phase while the page is scrolling must cancel, so the `.ended`
    /// that follows cannot fire the action.
    @Test("a .changed during a drag cancels, and the lift after it is swallowed")
    func changedDuringDragCancels() {
        var downs = 0
        var insides: [Bool] = []
        let coordinator = TouchDownSurface.Coordinator(
            core: TouchDownCore(onDown: { downs += 1 }, onUp: { insides.append($0) })
        )
        let scroll = ScrollingStub()
        let catcher = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        nest(catcher, under: scroll, depth: 1)
        catcher.addGestureRecognizer(
            TouchDownSurface.configuredRecognizer(coordinator: coordinator))

        let inside = CGPoint(x: 50, y: 50)
        coordinator.apply(state: .began, view: catcher, location: inside)
        #expect(downs == 1, Comment(rawValue: "the press feedback still fires at touch-down"))

        scroll.stubDragging = true
        coordinator.apply(state: .changed, view: catcher, location: inside)
        // UIKit delivers the lift regardless, and the finger IS on the tile.
        coordinator.apply(state: .ended, view: catcher, location: inside)

        #expect(insides == [false], Comment(rawValue: "a flick that lifts on a tile must not tap it"))
    }

    /// The other half, and the reason the fix is not simply "never tap": the
    /// identical phase sequence over a scroll view standing still MUST tap, or
    /// the shop and the species picker become unusable.
    @Test("the same down-move-up over a still scroll view is a tap")
    func changedWithoutDragStillTaps() {
        var insides: [Bool] = []
        let coordinator = TouchDownSurface.Coordinator(
            core: TouchDownCore(onDown: {}, onUp: { insides.append($0) })
        )
        let scroll = ScrollingStub()
        let catcher = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        nest(catcher, under: scroll, depth: 1)

        let inside = CGPoint(x: 50, y: 50)
        coordinator.apply(state: .began, view: catcher, location: inside)
        coordinator.apply(state: .changed, view: catcher, location: inside)
        coordinator.apply(state: .ended, view: catcher, location: inside)

        #expect(insides == [true])
    }

    /// The load-bearing assumption of the whole fix, asserted rather than
    /// assumed: `enclosingScrollView` walks UIKit superviews, and nothing
    /// documents that SwiftUI's `ScrollView` is backed by a `UIScrollView`. If a
    /// future SwiftUI stops using one, the fix silently stops working and a
    /// flick starts buying things again — so this test hosts a real `ScrollView`
    /// containing a real `.touchDown`, finds the catcher UIKit actually built,
    /// and looks up from it.
    @Test("a real SwiftUI ScrollView puts a UIScrollView above the catcher")
    func swiftUIScrollViewIsOnThePath() throws {
        struct Probe: View {
            var body: some View {
                ScrollView(.vertical) {
                    Color.clear
                        .frame(width: 200, height: 2_000)
                        .touchDown({}, onUp: { _ in })
                }
            }
        }

        let frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        let host = UIHostingController(rootView: Probe())
        let window = UIWindow(frame: frame)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.frame = frame
        host.view.layoutIfNeeded()

        let catcher = try #require(
            Self.findCatcher(in: host.view),
            Comment(rawValue: "no touch-down catcher in the hosted hierarchy"))
        #expect(
            TouchDownSurface.Coordinator.enclosingScrollView(of: catcher) != nil,
            Comment(rawValue: "SwiftUI's ScrollView is no longer a UIScrollView — the scroll-vs-tap fix is dead"))
    }

    /// The catcher is `TouchDownSurface`'s plain `UIView`, identified by the one
    /// thing that makes it ours: a zero-duration long-press recogniser.
    ///
    /// The class match must be EXACT. `ScrollView`'s own indicator knob carries
    /// a `UIScrollViewKnobLongPressGestureRecognizer` — a
    /// `UILongPressGestureRecognizer` subclass, also with
    /// `minimumPressDuration == 0` — so an `as?` cast finds the scroll view
    /// itself, whose superview chain has no scroll view above it. That false
    /// positive is what made this test fail against a working fix.
    private static func findCatcher(in view: UIView) -> UIView? {
        let isCatcher = view.gestureRecognizers?.contains {
            type(of: $0) == UILongPressGestureRecognizer.self
                && ($0 as? UILongPressGestureRecognizer)?.minimumPressDuration == 0
        }
        if isCatcher == true { return view }
        for child in view.subviews {
            if let found = findCatcher(in: child) { return found }
        }
        return nil
    }

    @Test("a lift outside the bounds is still not a tap, scroll view or no")
    func liftOutsideIsNotATap() {
        var insides: [Bool] = []
        let coordinator = TouchDownSurface.Coordinator(
            core: TouchDownCore(onDown: {}, onUp: { insides.append($0) })
        )
        let catcher = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        UIView().addSubview(catcher)

        coordinator.apply(state: .began, view: catcher, location: CGPoint(x: 50, y: 50))
        coordinator.apply(state: .ended, view: catcher, location: CGPoint(x: 400, y: 50))

        #expect(insides == [false])
    }
}

#elseif canImport(AppKit)

// The macOS platform path: a real NSView whose mouseDown drives the same core,
// synchronously inside AppKit event dispatch. This is what the host suite can
// exercise end to end.
@Suite("TouchDown — AppKit view path")
@MainActor
struct TouchDownAppKitTests {

    private func mouseEvent(_ type: NSEvent.EventType, at point: CGPoint) -> NSEvent? {
        NSEvent.mouseEvent(
            with: type,
            location: point,
            modifierFlags: [],
            timestamp: 0,
            windowNumber: 0,
            context: nil,
            eventNumber: 0,
            clickCount: 1,
            pressure: 1
        )
    }

    @Test("mouseDown fires onDown synchronously; mouseUp inside reports inside")
    func mouseDownIsTouchDown() throws {
        var log: [String] = []
        let view = TouchDownNSView(
            core: TouchDownCore(
                onDown: { log.append("down") },
                onUp: { log.append($0 ? "up-inside" : "up-outside") }
            )
        )
        view.frame = CGRect(x: 0, y: 0, width: 100, height: 100)

        let down = try #require(mouseEvent(.leftMouseDown, at: CGPoint(x: 10, y: 10)))
        view.mouseDown(with: down)
        #expect(log == ["down"], Comment(rawValue: "feedback at mouse-down, before any lift"))

        let up = try #require(mouseEvent(.leftMouseUp, at: CGPoint(x: 10, y: 10)))
        view.mouseUp(with: up)
        #expect(log == ["down", "up-inside"])
    }

    @Test("mouseUp outside the view reports outside")
    func mouseUpOutside() throws {
        var insides: [Bool] = []
        let view = TouchDownNSView(
            core: TouchDownCore(onDown: {}, onUp: { insides.append($0) })
        )
        view.frame = CGRect(x: 0, y: 0, width: 100, height: 100)

        let down = try #require(mouseEvent(.leftMouseDown, at: CGPoint(x: 10, y: 10)))
        view.mouseDown(with: down)
        let up = try #require(mouseEvent(.leftMouseUp, at: CGPoint(x: 400, y: 400)))
        view.mouseUp(with: up)

        #expect(insides == [false])
    }
}

#endif
