import QuartzCore
import SwiftUI

import ALCore

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// Port of `src/components/Tile.tsx` — the pick primitive every exercise builds
// on. Invariants 1, 3 and 6 all land in this file:
//
//   1. Feedback fires at touch-DOWN, before any view commit. The pipeline is
//      `TilePress.pointerDown`, called synchronously from the `touchDown`
//      handler (D5): press animation on the hosted layer, then `onPick()`
//      (whose SFX are the engine's, fired inside the same call), then the
//      shake if the verdict is `.reject`. Nothing here waits on `@State`.
//   2. (via Anim) press/shake are Core Animation on a `LayerHost` layer —
//      never a SwiftUI animation, never a re-render.
//   3. No fail state. A `.reject` shakes and does NOTHING else — `disabled`
//      is set by the exercise (an already-used tray tile, the celebration
//      lock), never by a wrong answer. There is no disabled-because-wrong
//      path in this file, and none can be added without a new parameter.
//   6. Accessibility floor: `accessibilityLabel` is REQUIRED (the TSX prop is
//      optional but every engine passes one — the Swift signature makes the
//      invariant structural), and the 92 pt default side is the tap-target
//      floor (`clamp(92px, 27vw, 150px)`).
//
// The optional « Écouter » affordance is a SEPARATE sibling button stacked
// below the tile with a real 8 pt gap (`gap-2`), so hearing a tile can never
// commit its pick. It speaks on pointerdown, same beat as a pick.

// MARK: - Reduced motion, as an environment value (D14 plumbing)

// D14 wants ONE `ReduceMotionSource`, injected at the root — but no injection
// point existed in ALUI before this file. The key lives here so `Tile` and
// `GameFrame` read the same source the tests and the render harness can force.
// The default FOLLOWS THE SYSTEM: a screen rendered without explicit injection
// still honours the OS switch (invariant 6), it just doesn't observe changes
// mid-session.

/// The system's reduce-motion switch, read live — the FALLBACK used when nobody
/// injected a source, not the one the shipped app should run on.
///
/// D14 asks for one source. There are two implementations of the protocol and
/// that is forced by the layering, not sloppiness: `ALPlatform.SystemReduceMotion`
/// observes `UIAccessibility.reduceMotionStatusDidChangeNotification` and so
/// picks up a mid-session change, but `ALUI` may not import `ALPlatform`
/// (ARCHITECTURE §1), so it cannot be this environment key's default. The app
/// root injects the observing one; this one exists so a screen rendered without
/// injection — a preview, a test, the render harness — still honours the OS
/// switch instead of silently animating. It reads the switch on every access;
/// what it lacks is the change notification.
public struct SystemDefaultReduceMotion: ReduceMotionSource {
    public init() {}

    public var isReduced: Bool {
        #if canImport(UIKit)
        return UIAccessibility.isReduceMotionEnabled
        #elseif canImport(AppKit)
        return NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        #else
        return false
        #endif
    }
}

private struct ALReduceMotionKey: EnvironmentKey {
    static let defaultValue: any ReduceMotionSource = SystemDefaultReduceMotion()
}

extension EnvironmentValues {
    /// The one reduce-motion source (D14). Inject `FixedReduceMotion` in tests
    /// and the render harness; the default follows the OS setting.
    public var alReduceMotion: any ReduceMotionSource {
        get { self[ALReduceMotionKey.self] }
        set { self[ALReduceMotionKey.self] = newValue }
    }
}

extension View {
    /// Publishes a reduce-motion source for this subtree (D14).
    public func alReduceMotion(_ source: any ReduceMotionSource) -> some View {
        environment(\.alReduceMotion, source)
    }
}

// MARK: - Authored metrics (Tile.tsx inline styles, verbatim)

public enum TileMetrics {
    /// `clamp(92px, 27vw, 150px)` — `dim`, the default tile side. The 92 pt
    /// floor IS the accessibility floor (invariant 6); never parameterise a
    /// tile below it.
    public static let defaultSize = FluidSpec(min: 92, vw: 27, max: 150)

    /// `clamp(30px, 9vw, 64px)` — the default glyph size.
    public static let defaultFontSize = FluidSpec(min: 30, vw: 9, max: 64)

    /// `padding: 0 clamp(10px, 3vw, 20px)`.
    public static let horizontalPadding = FluidSpec(min: 10, vw: 3, max: 20)

    /// `borderRadius: 28`.
    public static let cornerRadius: CGFloat = 28

    /// `disabled:opacity-40` — on the tile AND its Écouter button.
    public static let disabledOpacity: Double = 0.4

    /// The highlight ring: `0 0 0 6px #66BB6A` — a 6 pt SPREAD, i.e. a ring
    /// outside the border box whose radius grows to 28 + 6 = 34 (shell.md §4.7).
    public static let highlightRingWidth: CGFloat = 6
    public static let highlightRingRadius: CGFloat = 34

    /// `transition: box-shadow 0.15s`.
    public static let highlightTransition: TimeInterval = 0.15

    /// The tile column when an Écouter button exists: `gap-2` = 8 pt.
    public static let columnGap: CGFloat = 8

    /// Écouter: `height: clamp(40px, 11vw, 52px)`, `fontSize: clamp(16px, 4.5vw, 22px)`.
    public static let previewHeight = FluidSpec(min: 40, vw: 11, max: 52)
    public static let previewFontSize = FluidSpec(min: 16, vw: 4.5, max: 22)
}

// MARK: - The pointerdown pipeline (invariant 1)

/// The exact `onPointerDown` pipeline from `Tile.tsx`, extracted so the
/// ordering contract — press at touch-down, `onPick()` synchronous, shake on
/// `.reject` — is asserted on the host without rendering a view.
@MainActor
public enum TilePress {
    /// `Tile.tsx` `handle`:
    /// ```
    /// if (disabled) return;
    /// el?.animate(press, { duration: 130, easing: "ease-out" });
    /// if (onPick() === "reject") el?.animate(shake, { duration: 300, easing: "ease-in-out" });
    /// ```
    /// A disabled tile does nothing — no animation, no sound, no pick.
    /// `onPick` is synchronous and non-async by signature: the SFX it fires and
    /// the verdict it returns land in the same turn as the touch.
    /// No `ReduceMotionSource` parameter: `Tile.tsx` gates neither the press nor
    /// the shake on the media query (D29). Tap feedback is not decoration.
    public static func pointerDown(
        layer: CALayer?,
        disabled: Bool,
        onPick: () -> Verdict
    ) {
        guard !disabled else { return }
        Anim.press(layer)
        if onPick() == .reject {
            Anim.shake(layer)
        }
    }

    /// `Tile.tsx` `handlePreview`: press on the Écouter button's OWN layer,
    /// then speak. Never a shake — hearing a tile has no verdict.
    public static func previewDown(
        layer: CALayer?,
        disabled: Bool,
        onPreview: () -> Void
    ) {
        guard !disabled else { return }
        Anim.press(layer)
        onPreview()
    }
}

// MARK: - The miss-cooldown swallow window (invariant 8)

/// Port of the `coolUntil` ref pattern every single-pick engine carries
/// (`FindSoundExercise.tsx` and siblings):
/// ```
/// const coolUntil = useRef(0);
/// if (performance.now() < coolUntil.current) return "reject";  // silent
/// …on a miss: coolUntil.current = performance.now() + MISS_COOLDOWN_MS;
/// ```
/// After a miss, picks are SWALLOWED for `Rewards.missCooldownMs` (800 ms):
/// verdict `.reject` with zero audio and zero state change — the press and
/// shake animations still play (the swallow is silent, not invisible). It is a
/// swallow window, never a lock — invariant 3. The comparison is strict `<`,
/// so the tap at exactly +800 ms is accepted again.
///
/// Time is injected (`TimeSource`, D6) so the window is host-testable without
/// sleeping. Assembly engines have NO cooldown by design — their pacing is the
/// awaited « Oh non ! On recommence. » line; do not hand them one for symmetry.
public final class MissCooldown {
    private let time: any TimeSource
    private var coolUntilMillis: Int64 = 0

    public init(time: any TimeSource) {
        self.time = time
    }

    /// `performance.now() < coolUntil.current` — true while picks are swallowed.
    public var isSwallowing: Bool {
        time.nowMillis < coolUntilMillis
    }

    /// `coolUntil.current = performance.now() + MISS_COOLDOWN_MS` — call on the
    /// miss itself, in the same synchronous beat as `missRound`.
    public func registerMiss() {
        coolUntilMillis = time.nowMillis + Int64(Rewards.missCooldownMs)
    }
}

// MARK: - The Tile view

public struct Tile<Content: View>: View {
    private let bg: HexColor
    private let ink: HexColor
    private let disabled: Bool
    private let highlight: Bool
    private let size: FluidSpec
    private let fontSize: FluidSpec
    private let label: String
    private let onPick: () -> Verdict
    private let onPreview: (() -> Void)?
    private let previewLabel: String?
    private let content: Content

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    @State private var tileHandle = LayerHandle()
    @State private var listenHandle = LayerHandle()
    /// The tile's laid-out width, so the Écouter button stretches to match it
    /// (`items-stretch` on the TSX column). One-way: the button's width follows
    /// the tile's, never the reverse — no measurement cycle.
    @State private var tileWidth: CGFloat = 0

    /// - Parameters:
    ///   - bg/ink: data-driven colours, exactly the TSX `bg`/`ink` strings.
    ///   - accessibilityLabel: TSX `ariaLabel`. Required here — invariant 6
    ///     made structural (every engine passes one; see file header).
    ///   - onPick: runs SYNCHRONOUSLY on pointerdown; return `.reject` to shake.
    ///   - onPreview: the « hear it first » affordance — when set, a separate
    ///     full-width Écouter button is stacked below the tile.
    public init(
        bg: HexColor,
        ink: HexColor,
        disabled: Bool = false,
        highlight: Bool = false,
        size: FluidSpec? = nil,
        fontSize: FluidSpec? = nil,
        accessibilityLabel: String,
        onPick: @escaping () -> Verdict,
        onPreview: (() -> Void)? = nil,
        previewLabel: String? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.bg = bg
        self.ink = ink
        self.disabled = disabled
        self.highlight = highlight
        self.size = size ?? TileMetrics.defaultSize
        self.fontSize = fontSize ?? TileMetrics.defaultFontSize
        self.label = accessibilityLabel
        self.onPick = onPick
        self.onPreview = onPreview
        self.previewLabel = previewLabel
        self.content = content()
    }

    /// Convenience for the exercise palettes (`TILE_COLORS[i % count]`).
    public init(
        paint: Palette.TilePaint,
        disabled: Bool = false,
        highlight: Bool = false,
        size: FluidSpec? = nil,
        fontSize: FluidSpec? = nil,
        accessibilityLabel: String,
        onPick: @escaping () -> Verdict,
        onPreview: (() -> Void)? = nil,
        previewLabel: String? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.init(
            bg: paint.bg,
            ink: paint.ink,
            disabled: disabled,
            highlight: highlight,
            size: size,
            fontSize: fontSize,
            accessibilityLabel: accessibilityLabel,
            onPick: onPick,
            onPreview: onPreview,
            previewLabel: previewLabel,
            content: { content() }
        )
    }

    public var body: some View {
        let dim = size.resolve(viewport: viewport)
        if let onPreview {
            // Column: big pick tile on top, its own Écouter button below with
            // a real gap. Both are finger-sized, single-purpose targets that
            // never overlap (`flex flex-col items-stretch gap-2`).
            VStack(spacing: TileMetrics.columnGap) {
                tileButton(dim: dim)
                listenButton(dim: dim, onPreview: onPreview)
            }
            .frame(minWidth: dim)
        } else {
            tileButton(dim: dim)
        }
    }

    // MARK: the pick tile

    private func tileButton(dim: CGFloat) -> some View {
        LayerHost(handle: tileHandle) {
            content
                .font(Typography.rounded(fontSize.resolve(viewport: viewport), Typography.Weight.black))
                .foregroundStyle(ink.color)
                .padding(.horizontal, TileMetrics.horizontalPadding.resolve(viewport: viewport))
                .frame(minWidth: dim)
                .frame(height: dim)
                .background(bg.color, in: RoundedRectangle(cornerRadius: TileMetrics.cornerRadius))
                // `0 0 0 6px #66BB6A` — the spread ring, drawn OUTSIDE the
                // border box (radius 28 + 6 = 34). Present always, faded by
                // opacity so `transition: box-shadow 0.15s` has something to
                // animate.
                .overlay {
                    RoundedRectangle(cornerRadius: TileMetrics.highlightRingRadius)
                        .stroke(Palette.green.color, lineWidth: TileMetrics.highlightRingWidth)
                        .padding(-TileMetrics.highlightRingWidth / 2)
                        .opacity(highlight ? 1 : 0)
                }
                // normal:    0 8px 0 rgba(0,0,0,0.12), 0 12px 20px rgba(0,0,0,0.14)
                // highlight: 0 10px 22px rgba(0,0,0,0.18)
                .shadow(
                    color: .black.opacity(highlight ? 0.18 : 0.12),
                    radius: highlight ? 11 : 0,
                    y: highlight ? 10 : 8
                )
                .shadow(color: .black.opacity(highlight ? 0 : 0.14), radius: 10, y: 12)
                .animation(.easeInOut(duration: TileMetrics.highlightTransition), value: highlight)
        }
        .opacity(disabled ? TileMetrics.disabledOpacity : 1)
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: TileWidthKey.self, value: proxy.size.width)
            }
        )
        .onPreferenceChange(TileWidthKey.self) { tileWidth = $0 }
        .touchDown { [onPick, disabled] in
            // Invariant 1 — everything in this closure happens at touch-down,
            // synchronously, before SwiftUI commits anything.
            TilePress.pointerDown(
                layer: tileHandle.layer,
                disabled: disabled,
                onPick: onPick
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isButton)
    }

    // MARK: the Écouter sibling

    private func listenButton(dim: CGFloat, onPreview: @escaping () -> Void) -> some View {
        LayerHost(handle: listenHandle) {
            Text(Copy.Tile.listenGlyph)
                .font(
                    Typography.rounded(
                        TileMetrics.previewFontSize.resolve(viewport: viewport),
                        Typography.Weight.bold
                    )
                )
                .foregroundStyle(Palette.ink.color)
                // items-stretch: the button fills the column, whose width is
                // the tile's (never narrower than `dim`).
                .frame(width: max(tileWidth, dim))
                .frame(height: TileMetrics.previewHeight.resolve(viewport: viewport))
                .background(Color.white, in: Capsule())
                // 0 3px 0 rgba(0,0,0,0.10), 0 5px 12px rgba(0,0,0,0.12)
                .shadow(color: .black.opacity(0.10), radius: 0, y: 3)
                .shadow(color: .black.opacity(0.12), radius: 6, y: 5)
        }
        .opacity(disabled ? TileMetrics.disabledOpacity : 1)
        .touchDown { [disabled] in
            TilePress.previewDown(
                layer: listenHandle.layer,
                disabled: disabled,
                onPreview: onPreview
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(previewLabel ?? Copy.Tile.listenFallback)
        .accessibilityAddTraits(.isButton)
    }
}

private struct TileWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
