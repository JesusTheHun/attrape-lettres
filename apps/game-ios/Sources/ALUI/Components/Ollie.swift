import SwiftUI

import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* Ollie — the child's companion on screen.                                    */
/*                                                                             */
/* `src/components/Ollie.tsx` is the ORIGINAL, an emoji in a bobbing div. It    */
/* still sits in the tree and is imported by nothing: `src/types.ts` records    */
/* « Drop-in replacement for <Ollie mood>. Agent A implements the SVG rig. »,   */
/* and every live call site (the seven exercises, the hub, the Dashboard, the   */
/* shop) renders `<Mascot config mood />`. So this file ports `Mascot.tsx`'s    */
/* SHELL under the name the app has always used for the slot.                  */
/*                                                                             */
/* The rig itself is ALArt's and complete — `MascotRig.draw` builds the whole   */
/* draw list (ground shadow, feet-pivot growth scale, species dispatch, rainbow */
/* overlay, the five French labels) and `Motion.swift` carries the bob/pop/     */
/* cheer keyframes and the sheen sweep as modifiers. Not one path, keyframe or  */
/* colour is re-authored here. What is here is the part ALArt deliberately      */
/* delegates to the app shell, and it is exactly two things:                    */
/*                                                                             */
/*  1. OVERFLOW. `Mascot.tsx` sets `overflow: visible` on the `<svg>` and the   */
/*     top-stage wings, haloes and sparkles draw well outside the 0…100 box. A  */
/*     SwiftUI `Canvas` clips to its bounds, so `MascotRigView`'s `size × size` */
/*     canvas would shear them off. This view therefore renders the rig into a  */
/*     canvas covering the web's own mask window — `x=-120 y=-120 w=340 h=340`, */
/*     `userSpaceOnUse` — and then pins the LAYOUT box back to `size × size`,   */
/*     unclipped, so hit-testing and layout match the web exactly (spec §6).    */
/*     `preview` DOES clip (`overflow: hidden`), which is what `MascotRigView`  */
/*     already gives for free.                                                  */
/*                                                                             */
/*  2. THE SHEEN SWEEP. « Arc-en-ciel magique » is a rainbow band swept across  */
/*     the pet and masked to its silhouette, 3.6 s linear infinite. ALArt bakes  */
/*     the band at its base offset into the rig's draw list; animating it means */
/*     splitting the two, so this view draws the rig WITHOUT the whole-image    */
/*     accessory, then overlays the band — the same band, `drawRainbowSheen` —  */
/*     with `mascotSheenMotion` on it and `.mask { rig }` around it. That is    */
/*     the spec's hybrid: « rig = one Canvas (static per state); sheen = a small*/
/*     separate view … with a Core-Animation backed offset ».                   */
/*                                                                             */
/* INVARIANT 2 — nothing here redraws per frame. Both canvases are static for a */
/* given (config, mood, preview); the bob and the sweep are transforms over     */
/* them. INVARIANT 6 — every animation is gated on the injected                 */
/* `ReduceMotionSource` (D14) inside `MascotMoodMotion` / `MascotSheenMotion`,  */
/* and the accessibility label comes from `MascotRig.labels`.                   */
/* -------------------------------------------------------------------------- */

public struct Ollie: View {

    /// `<mask maskUnits="userSpaceOnUse" x={-120} y={-120} width={340} height={340}>`
    /// — the web's own statement of how far outside the box the pet can reach.
    /// Used as the drawing canvas' viewBox so nothing is clipped.
    public static let overflowViewBox = CGRect(x: -120, y: -120, width: 340, height: 340)

    /// `overflowViewBox.width / 100` — how many times `size` the drawing canvas
    /// is.
    public static var overflowFactor: CGFloat { overflowViewBox.width / 100 }

    /// `RAINBOW_IDS` in `Mascot.tsx`: « Premium accessories rendered as a
    /// WHOLE-IMAGE rainbow sheen (not a worn part). » ALArt keeps its own copy
    /// internal; the id itself is ALCore's, so the two cannot drift apart
    /// silently — `OllieTests` pins the list.
    public static let rainbowIDs: [String] = [Accessory.Unicorn.starClip]

    public var config: MascotConfig
    public var mood: Mood
    /// `size = 88` — the TSX default.
    public var size: CGFloat
    /// « Shop thumbnails never bob. » Also clips, and pins the growth scale to 1.
    public var preview: Bool
    /// A crop rect in the 0…100 mascot space; only ever used with `preview`.
    public var focus: CGRect?
    /// D14.
    public var reduceMotion: ReduceMotionSource

    public init(
        config: MascotConfig,
        mood: Mood,
        size: CGFloat = 88,
        preview: Bool = false,
        focus: CGRect? = nil,
        reduceMotion: ReduceMotionSource
    ) {
        self.config = config
        self.mood = mood
        self.size = size
        self.preview = preview
        self.focus = focus
        self.reduceMotion = reduceMotion
    }

    /// Does this look wear the whole-image rainbow?
    var wearsRainbow: Bool {
        config.accessories.contains { Self.rainbowIDs.contains($0) }
    }

    /// The same config with the whole-image accessories dropped, so
    /// `MascotRig.draw` takes its plain branch and leaves the band to this
    /// view. Safe by construction: the rainbow ids are never worn parts (the
    /// unicorn rig says so in as many words), so removing them changes nothing
    /// else about the drawing.
    var rigConfig: MascotConfig {
        guard wearsRainbow else { return config }
        var stripped = config
        stripped.accessories = config.accessories.filter { !Self.rainbowIDs.contains($0) }
        return stripped
    }

    public var body: some View {
        Group {
            if preview {
                // `overflow: hidden` + `k = 1` + the `focus` viewBox: exactly
                // what `MascotRigView` renders, and it never animates.
                MascotRigView(
                    config: config,
                    mood: mood,
                    size: size,
                    preview: true,
                    focus: focus
                )
            } else {
                playfield
            }
        }
        // The mood animation rides the `<svg>` ELEMENT, whose box is `size ×
        // size` — so `transform-origin: 50% 82%` resolves against THAT box and
        // the modifier must sit outside the overflow frame, not inside it.
        .mascotMoodMotion(mood: mood, preview: preview, reduceMotion: reduceMotion)
    }

    /// The unclipped, animatable mascot.
    private var playfield: some View {
        let canvasSide = size * Self.overflowFactor
        return ZStack {
            rig
            if wearsRainbow {
                sheenBand
                    .mascotSheenMotion(unitScale: size / 100, reduceMotion: reduceMotion)
                    .frame(width: canvasSide, height: canvasSide)
                    // The silhouette mask is STATIC; only the band inside it
                    // moves. On the web this is `<mask>` + `<use>` of the rig;
                    // after `brightness(0) invert(1)` the luminance mask is
                    // identical to an alpha mask, so `.mask { rig }` is exact
                    // (spec §6, « Do not port the filter »).
                    .mask { rig }
            }
        }
        .frame(width: canvasSide, height: canvasSide)
        // …and the layout box is still `size × size`. `.frame` does not clip,
        // so the wings keep hanging over the edge, as `overflow: visible` says.
        .frame(width: size, height: size)
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(Text(verbatim: MascotRig.label(for: config.species)))
    }

    /// The rig, drawn into the oversized viewBox. Static for a given state.
    private var rig: some View {
        Canvas { context, canvasSize in
            var canvas = SVGCanvas(
                viewBox: Self.overflowViewBox,
                fitting: CGRect(origin: .zero, size: canvasSize)
            )
            MascotRig.draw(into: &canvas, config: rigConfig, mood: mood, preview: false)
            var ctx = context
            canvas.render(into: &ctx)
        }
        .frame(width: size * Self.overflowFactor, height: size * Self.overflowFactor)
    }

    /// `<RainbowSheen>` — the nine-stop prism band, rotated −20° about (50,50).
    /// Same viewBox as the rig so the two line up under the mask.
    private var sheenBand: some View {
        Canvas { context, canvasSize in
            var canvas = SVGCanvas(
                viewBox: Self.overflowViewBox,
                fitting: CGRect(origin: .zero, size: canvasSize)
            )
            drawRainbowSheen(into: &canvas)
            var ctx = context
            canvas.render(into: &ctx)
        }
        .frame(width: size * Self.overflowFactor, height: size * Self.overflowFactor)
    }
}
