import CoreGraphics
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
