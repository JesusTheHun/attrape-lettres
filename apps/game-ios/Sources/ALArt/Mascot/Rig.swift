import ALCore
import CoreGraphics
import SwiftUI

// Port of `src/mascot/Mascot.tsx` — the shell around the species rigs.
//
// Parametric, layered mascot: species × stage × wardrobe × mood → a draw list.
// Dispatches on config.species; growth is a PER-STAGE FEATURE TIMELINE
// (see Growth.swift + each species' STAGES table).
//
// What is here: the ground shadow, the feet-pivot growth scale, the species
// dispatch, the "Arc-en-ciel magique" silhouette-masked rainbow overlay and the
// five French accessibility labels (invariant 6).
//
// What is deliberately NOT here (motion layer, spec §10): the WAAPI mood
// animations (bob/pop/cheer, transform-origin 50%/82%, reduced-motion gate) and
// the sheen's sweep. They are transforms over this static draw list — invariant
// 2 says they never reach the render path, so they never reach this file.

/** Premium accessories rendered as a WHOLE-IMAGE rainbow sheen (not a worn part). */
let RAINBOW_IDS: [String] = [Accessory.Unicorn.starClip]

public enum MascotRig {

    /// `aria-label` per species — byte-for-byte French, hard-coded, not
    /// localised (spec §4, invariant 6).
    public static let labels: [Species: String] = [
        .unicorn: "Ma licorne",
        .cat: "Mon chat",
        .fox: "Mon renard",
        .rabbit: "Mon lapin",
        .dragon: "Mon dragon",
    ]

    /// The accessibility label for one species. The table above is total over
    /// the closed enum; the fallback can only be reached by a table regression,
    /// which `MascotRigTests` pins.
    public static func label(for species: Species) -> String {
        labels[species] ?? ""
    }

    /// Build the mascot's full draw list into `c` (viewBox space, 0…100).
    public static func draw(
        into c: inout SVGCanvas,
        config: MascotConfig,
        mood: Mood,
        preview: Bool = false
    ) {
        let layout = Growth.layoutFor(Double(config.stage))

        // Per-stage growth scale, pivoted at the feet (see Growth.stageScale).
        // Previews pin scale=1 so a `focus` crop lines up with raw layout
        // coordinates.
        let k = preview ? 1 : Growth.stageScale(config.stage)
        let pivot = layout.feetY

        // Ground shadow — outside the scaled rig group; `rx` is multiplied by
        // `k` manually while `cy` is not (Mascot.tsx does exactly this).
        c.fill(
            ellipsePath(50, layout.feetY + 3.5, layout.bodyRX * 0.92 * k, 3.6),
            with: .hex("#000", opacity: 0.1)
        )

        func scaledRig(_ c: inout SVGCanvas) {
            // transform="translate(50 pivot) scale(k) translate(-50 -pivot)"
            c.save()
            c.translate(50, pivot)
            c.scale(k)
            c.translate(-50, -pivot)
            drawRig(into: &c, config: config, layout: layout, mood: mood, preview: preview)
            c.restore()
        }

        // "Arc-en-ciel magique": a rainbow prism sweep MASKED to the pet's
        // silhouette, so it hugs the whole creature (wings + overflow beyond the
        // box included) and never spills onto the card/background or hard-cuts
        // at the viewBox edge. On the web the mask is the rig re-rendered as a
        // flat white silhouette (brightness(0) invert(1)); after that filter the
        // luminance mask ≡ an alpha mask, so this port draws the rig itself as
        // the matte and lets its alpha do the work (SVGDrawNode.mask's NB).
        let rainbow = config.accessories.contains { RAINBOW_IDS.contains($0) }
        if rainbow {
            scaledRig(&c)
            c.mask { m in
                scaledRig(&m)
            } content: { body in
                drawRainbowSheen(into: &body)
            }
        } else {
            scaledRig(&c)
        }
    }

    /// The species dispatch (Mascot.tsx's ternary chain).
    static func drawRig(
        into c: inout SVGCanvas,
        config: MascotConfig,
        // NB: qualified — SwiftUI declares a `Layout` protocol that shadows ALCore's.
        layout: ALCore.Layout,
        mood: Mood,
        preview: Bool
    ) {
        switch config.species {
        case .cat:
            drawCatRig(into: &c, config: config, layout: layout, stage: config.stage, mood: mood, preview: preview)
        case .fox:
            drawFoxRig(into: &c, config: config, layout: layout, stage: config.stage, mood: mood, preview: preview)
        case .rabbit:
            drawRabbitRig(into: &c, config: config, layout: layout, stage: config.stage, mood: mood, preview: preview)
        case .dragon:
            drawDragonRig(into: &c, config: config, layout: layout, stage: config.stage, mood: mood, preview: preview)
        case .unicorn:
            drawUnicornRig(into: &c, config: config, layout: layout, stage: config.stage, mood: mood, preview: preview)
        }
    }

    /// Convenience: record the whole mascot into a fresh canvas and return the
    /// draw list — what the tests and the render harness consume.
    public static func drawList(
        config: MascotConfig,
        mood: Mood,
        preview: Bool = false
    ) -> [SVGDrawNode] {
        var c = SVGCanvas()
        draw(into: &c, config: config, mood: mood, preview: preview)
        return c.nodes
    }
}

/// The rendered mascot: one static state of the rig in a `Canvas`, with the
/// species' French accessibility label attached (invariant 6).
///
/// This view renders exactly what `MascotRig.draw` records — which is also what
/// reduced motion shows. Mood motion (bob/pop/cheer) and the sheen sweep are
/// transforms applied OVER this view by the motion layer (spec §10); the app
/// shell also owns overflow management (a SwiftUI `Canvas` clips to its own
/// bounds, so the non-preview mascot must be hosted in a canvas larger than
/// `size` — top-stage wings and haloes deliberately overflow the 100-unit box).
public struct MascotRigView: View {
    public var config: MascotConfig
    public var mood: Mood
    public var size: CGFloat
    public var preview: Bool
    /// Crop rect in the 0…100 mascot space (shop tiles zoom onto one
    /// accessory). Only ever used with `preview` — which pins scale to 1 so the
    /// crop lines up with raw layout coordinates.
    public var focus: CGRect?

    public init(
        config: MascotConfig,
        mood: Mood,
        size: CGFloat = 88,
        preview: Bool = false,
        focus: CGRect? = nil
    ) {
        self.config = config
        self.mood = mood
        self.size = size
        self.preview = preview
        self.focus = focus
    }

    public var body: some View {
        Canvas { context, canvasSize in
            let viewBox = focus ?? CGRect(x: 0, y: 0, width: 100, height: 100)
            var canvas = SVGCanvas(
                viewBox: viewBox,
                fitting: CGRect(origin: .zero, size: canvasSize)
            )
            MascotRig.draw(into: &canvas, config: config, mood: mood, preview: preview)
            var ctx = context
            canvas.render(into: &ctx)
        }
        .frame(width: size, height: size)
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(Text(verbatim: MascotRig.label(for: config.species)))
    }
}
