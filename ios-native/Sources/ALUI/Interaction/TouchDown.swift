import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// The ONE touch-down primitive, app-wide (D5). The port of the PWA's
// `onPointerDown` — every interactive gameplay element (tiles, listen buttons,
// slot-remove buttons, child cards) routes through this and nothing else.
//
// Invariant 1 — feedback fires on pointerdown, BEFORE the view commits. The web
// does SFX + the press animation synchronously inside the pick handler
// (`src/components/Tile.tsx`, `onPointerDown`). So `onDown` here runs
// SYNCHRONOUSLY inside the recogniser's `.began` callback: it is a stored
// closure invoked directly — never routed through `@State`, a `Binding`, a
// `Task`, an animation block, or anything that defers to the next runloop turn.
//
// Why not the obvious alternatives (adjudicated in D5 / ARCHITECTURE §6.1):
//   - SwiftUI `Button` performs its action on touch-UP. Wrong semantics outright.
//   - `.onTapGesture` also recognises on touch-up, after gesture arbitration.
//   - `DragGesture(minimumDistance: 0)` does fire on touch-down but routes
//     through SwiftUI's gesture graph (a parent gesture can delay it) and would
//     force the press animation through `@State` — exactly what invariant 1
//     forbids.
//   - `UIControl.touchDown` (engines.md's `TilePressControl`) is the documented
//     fallback; the recogniser won because it needs no child-view-controller
//     plumbing and no accessibility passthrough work. It also survives
//     `delaysContentTouches`: a scroll view delays `touchesBegan` on content
//     VIEWS, but gesture recognisers attached to content subviews observe the
//     touch immediately.
//
// Scrolling: the recogniser must lose to a scroll view's pan. Because it begins
// the instant the finger lands, it must not *prevent* the pan either — the
// delegate allows simultaneous recognition, and when the scroll view takes the
// touch (cancelling content touches) the recogniser transitions to `.cancelled`,
// which reports `onUp(false)`. This mirrors the web, where a scroll fires
// `pointercancel` and no click follows the `pointerdown`.
//
// Accessibility (invariant 6): the catcher view is NOT an accessibility element,
// so the wrapped SwiftUI content keeps its label and traits untouched. VoiceOver
// activation synthesises a touch at the element's activation point, which lands
// on the catcher and drives the same `.began` → `.ended` path as a finger.

// MARK: - The platform-neutral core (host-testable)

/// The state machine both platform views drive. It exists so the touch-down
/// contract — synchronous `onDown` at touch-down, `onUp(inside:)` at lift —
/// is asserted by `swift test` on the host, where `UIGestureRecognizer` does
/// not exist.
@MainActor
final class TouchDownCore {
    var onDown: () -> Void
    var onUp: (_ inside: Bool) -> Void
    private(set) var isTracking = false

    init(onDown: @escaping () -> Void, onUp: @escaping (_ inside: Bool) -> Void) {
        self.onDown = onDown
        self.onUp = onUp
    }

    /// Touch landed. `onDown` is invoked before this function returns —
    /// invariant 1 lives on this line.
    func began() {
        guard !isTracking else { return }
        isTracking = true
        onDown()
    }

    /// Finger lifted at `point` (in the catcher view's coordinate space).
    /// `inside` is containment in `bounds` — the pointer-up-on-the-element
    /// test that makes a web `click`.
    func ended(at point: CGPoint, in bounds: CGRect) {
        guard isTracking else { return }
        isTracking = false
        onUp(bounds.contains(point))
    }

    /// The system took the touch (scroll pan, incoming call, VoiceOver
    /// interruption). The web analogue is `pointercancel`: no click.
    func cancelled() {
        guard isTracking else { return }
        isTracking = false
        onUp(false)
    }
}

// MARK: - Platform catcher views

#if canImport(UIKit)

struct TouchDownSurface: UIViewRepresentable {
    var onDown: () -> Void
    var onUp: (_ inside: Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(core: TouchDownCore(onDown: onDown, onUp: onUp))
    }

    /// Factored out of `makeUIView` so a test can assert the configuration
    /// that makes this touch-DOWN: `minimumPressDuration == 0`.
    static func configuredRecognizer(coordinator: Coordinator) -> UILongPressGestureRecognizer {
        let recognizer = UILongPressGestureRecognizer(
            target: coordinator,
            action: #selector(Coordinator.handle(_:))
        )
        recognizer.minimumPressDuration = 0 // fire `.began` at touch-down
        recognizer.cancelsTouchesInView = false
        recognizer.delegate = coordinator
        return recognizer
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = nil
        view.isOpaque = false
        // Not an accessibility element: the SwiftUI content underneath keeps
        // its label and traits (invariant 6).
        view.isAccessibilityElement = false
        view.addGestureRecognizer(Self.configuredRecognizer(coordinator: context.coordinator))
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        // Keep the captured state fresh; the closures themselves stay stored
        // properties invoked synchronously — no @State on the down path.
        context.coordinator.core.onDown = onDown
        context.coordinator.core.onUp = onUp
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let core: TouchDownCore

        init(core: TouchDownCore) {
            self.core = core
        }

        @objc func handle(_ recognizer: UILongPressGestureRecognizer) {
            switch recognizer.state {
            case .began:
                core.began()
            case .ended:
                guard let view = recognizer.view else {
                    core.cancelled()
                    return
                }
                core.ended(at: recognizer.location(in: view), in: view.bounds)
            case .cancelled, .failed:
                core.cancelled()
            default:
                break
            }
        }

        /// Never prevent another recogniser: the zero-duration press begins
        /// instantly, and without this a scroll view's pan could no longer
        /// recognise — scrolling would break on every tile.
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}

#elseif canImport(AppKit)

// The macOS host path (D1: everything compiles and tests on the host). AppKit
// delivers `mouseDown` synchronously inside event dispatch, so the
// synchronous-at-touch-down property holds here exactly as on iOS.
struct TouchDownSurface: NSViewRepresentable {
    var onDown: () -> Void
    var onUp: (_ inside: Bool) -> Void

    func makeNSView(context: Context) -> TouchDownNSView {
        TouchDownNSView(core: TouchDownCore(onDown: onDown, onUp: onUp))
    }

    func updateNSView(_ nsView: TouchDownNSView, context: Context) {
        nsView.core.onDown = onDown
        nsView.core.onUp = onUp
    }
}

final class TouchDownNSView: NSView {
    let core: TouchDownCore

    init(core: TouchDownCore) {
        self.core = core
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("TouchDownNSView is code-only")
    }

    /// A click in an inactive window still counts — closest to touch semantics.
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        core.began()
    }

    override func mouseUp(with event: NSEvent) {
        core.ended(at: convert(event.locationInWindow, from: nil), in: bounds)
    }
}

#endif

// MARK: - The SwiftUI surface

extension View {
    /// The app-wide touch-down primitive (D5). `onDown` runs synchronously at
    /// the instant the finger lands — the `pointerdown` of the PWA. `onUp`
    /// reports whether the finger lifted inside the view's bounds (`true` is
    /// the web's `click`; a lift outside or a system cancellation is `false`).
    ///
    /// Play feedback (SFX, `Anim.press`, the pick verdict) inside `onDown`,
    /// never behind a state update.
    public func touchDown(
        _ onDown: @escaping () -> Void,
        onUp: @escaping (_ inside: Bool) -> Void = { _ in }
    ) -> some View {
        overlay {
            TouchDownSurface(onDown: onDown, onUp: onUp)
        }
    }
}
