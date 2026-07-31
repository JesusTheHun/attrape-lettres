import SwiftUI

/* -------------------------------------------------------------------------- */
/* The big 🔊 « Écouter » button, once — D55.                                   */
/*                                                                             */
/* Every exercise puts one under its mascot, and all eight TSX files author it  */
/* with the same class list:                                                   */
/*                                                                             */
/*   rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E]       */
/*   shadow [touch-action:none]                                                */
/*                                                                             */
/* The Swift port grew four copies of that — `LettersListenPill`,              */
/* `SpellListenPill`, `SoundEngineChrome.listenButton` and `AssembleView`'s     */
/* private one — which is exactly the shape of divergence CLAUDE.md warns       */
/* about: eight identical lines of JSX, ported four times, so a fix lands in    */
/* one of them and nobody sees the other three. It is one component now, and    */
/* the four names above are thin wrappers that only differ in the margin they   */
/* carry.                                                                      */
/*                                                                             */
/* [DEVIATION, reported] It PRESSES. The web's does not: `Tile.tsx` is the only */
/* file that calls `el.animate`, so on the web this pill is visually inert and  */
/* the only acknowledgement of a tap is the voice that follows it. On a phone   */
/* that reads as a dead button — the clip may still be decoding, and a child    */
/* who gets nothing back taps again, which cuts the line they just asked for.   */
/* It now runs the same 130 ms squish a tile does, through the same             */
/* `TilePress.previewDown` path: press first, then speak, and never a shake     */
/* (asking to hear something has no verdict — invariant 3).                     */
/* -------------------------------------------------------------------------- */

/// The pill's authored metrics — the Tailwind classes, once. A separate enum
/// rather than statics on the view because the view is `@MainActor` and the
/// engines re-export these from their own (nonisolated) metric namespaces.
public enum ListenPillMetrics {
    /// `px-5`.
    public static let paddingX: CGFloat = 20
    /// `py-2`.
    public static let paddingY: CGFloat = 8
    /// Tailwind's plain `shadow` utility: `0 1px 3px rgba(0,0,0,0.1),
    /// 0 1px 2px -1px rgba(0,0,0,0.1)`.
    public static let shadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)
    public static let shadowTight = CSSShadow(y: 1, blur: 2, opacity: 0.1)
}

@MainActor
struct ListenPill: View {

    /// What the button SHOWS — « 🔊 Écouter », or `🔊 {word}` where the engine
    /// prints the prompt on the button itself.
    let text: String
    let accessibilityLabel: String
    /// Runs at touch-down, after the press starts. The `locked` guard that stops
    /// it cutting a success line lives in the model's `replayPrompt()`.
    let action: () -> Void

    @State private var handle = LayerHandle()

    var body: some View {
        LayerHost(handle: handle) {
            Text(verbatim: text)
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.bold))
                .foregroundStyle(Palette.ink.color)
                .padding(.horizontal, ListenPillMetrics.paddingX)
                .padding(.vertical, ListenPillMetrics.paddingY)
                // The shadow is cast by the CAPSULE, inside the background —
                // never by the composed pill, whose glyphs would each cast one
                // of their own (D54).
                .background {
                    Capsule()
                        .fill(.white.opacity(Palette.White.o70))
                        .shadow(
                            color: .black.opacity(ListenPillMetrics.shadow.opacity),
                            radius: ListenPillMetrics.shadow.swiftUIRadius,
                            y: ListenPillMetrics.shadow.y)
                        .shadow(
                            color: .black.opacity(ListenPillMetrics.shadowTight.opacity),
                            radius: ListenPillMetrics.shadowTight.swiftUIRadius,
                            y: ListenPillMetrics.shadowTight.y)
                }
        }
        .contentShape(Capsule())
        .touchDown {
            // Invariant 1 — press and speak both happen inside the touch-down
            // call, before SwiftUI commits anything.
            TilePress.previewDown(layer: handle.layer, disabled: false, onPreview: action)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(.isButton)
    }
}
