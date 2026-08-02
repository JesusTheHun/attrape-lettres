import ALArt
import ALCore
import SwiftUI

/* -------------------------------------------------------------------------- */
/* The app icon, drawn — not painted.                                          */
/*                                                                             */
/* Every other icon in this app is authored art (invariant 7: "an original      */
/* drawn icon, never an emoji"), and the app icon is the one a family sees      */
/* first. It is built here, from the SAME tokens the product uses — the pick    */
/* tile's face and ink out of `Palette.tileColors`, its corner-radius ratio out */
/* of `TileMetrics`, the stage wash out of `Palette.stage`, the confetti's own  */
/* six colours out of `ConfettiSystem.colors`. Change a tile colour and the     */
/* icon moves with it; nothing here re-types a hex that already exists.         */
/*                                                                             */
/* Why a SwiftUI view and not a 1024 PNG dropped in by hand:                    */
/*                                                                             */
/*   * it has a SOURCE. A raster has none — the day the coral shifts, a         */
/*     hand-made PNG is a file nobody can regenerate;                           */
/*   * `AppIconRasterTests` renders it and asserts the properties the App       */
/*     Store actually rejects for (opaque, square, no alpha) and the one it     */
/*     does not check but a parent does: that it still reads at 29 pt;          */
/*   * `swift run IconForge` regenerates every size for both apps — the iOS     */
/*     appiconset and the PWA's 192/512/apple-touch — from this one file, so    */
/*     the two products cannot drift the way their code deliberately does.      */
/*                                                                             */
/* Authored in a 1024-unit square, which is the only size Xcode 14+ wants; the  */
/* system downsamples the rest. Everything is a ratio of `designSide`, so       */
/* rendering at 60 for a legibility check is the same geometry, not a redraw.   */
/* -------------------------------------------------------------------------- */

/// The four directions the icon was drawn in, kept so the choice stays
/// reviewable. `swift run IconForge --sheet` renders all of them side by side,
/// each masked and shown down to 29 pt.
public enum AppIconVariant: String, CaseIterable, Sendable, Identifiable {
    /// A single pick tile on the stage wash — the app's own component, its own
    /// colours, caught mid-air.
    case tile
    /// The same mark inverted: cream tile on a coral field. **Shipping** — the
    /// only one of the four whose glyph is still a letter at 29 pt, and the
    /// only one that does not dissolve into a photo wallpaper.
    case coral
    /// The mascot, with a letter to catch.
    case fox
    /// Two tiles, the second peeking — the assembly game rather than the letter.
    case pair

    public var id: String { rawValue }

    /// French, because every other label in this app is.
    public var label: String {
        switch self {
        case .tile: "Tuile"
        case .coral: "Tuile inversée"
        case .fox: "Renard"
        case .pair: "Deux tuiles"
        }
    }
}

public enum AppIcon {
    /// The authoring square. Also the only size the asset catalog carries.
    public static let designSide: CGFloat = 1024

    /// The variant that ships. Named rather than assumed: `IconForge` writes
    /// this one into the asset catalog, and a test asserts the committed PNG is
    /// still what this view renders.
    public static let shipping: AppIconVariant = .coral

    /// iOS's icon mask, as a corner radius ratio (the superellipse is close
    /// enough to a continuous rounded rect at this size — Apple's own figure is
    /// 22.37 % of the side). Used ONLY for previews: the shipped PNG is a full
    /// square, and rounding it ourselves would double-round on device.
    public static let maskRadiusRatio: CGFloat = 0.2237
}

/// The icon itself. `side` scales the whole 1024-unit drawing; it does not
/// re-lay it out, so a 29 pt render is honestly what the home screen shows.
public struct AppIconView: View {
    public let variant: AppIconVariant
    public let side: CGFloat

    public init(_ variant: AppIconVariant = AppIcon.shipping, side: CGFloat = AppIcon.designSide) {
        self.variant = variant
        self.side = side
    }

    public var body: some View {
        art
            .frame(width: AppIcon.designSide, height: AppIcon.designSide)
            // No alpha anywhere: the App Store rejects an icon with any, and a
            // transparent corner is the classic way to find that out at upload
            // time. The background is the first thing drawn and it is opaque.
            .clipped()
            // `scaleEffect` does not change the LAYOUT size — the box stays
            // 1024 — so the outer frame centres a 1024 box inside `side` and
            // the centre-anchored scale lands on the same point. Anchoring at
            // `.topLeading` instead draws the art at the 1024 box's origin,
            // which the outer frame has already pushed off-screen: the first
            // sheet came back as four blank squares with four labels under
            // them.
            .scaleEffect(side / AppIcon.designSide)
            .frame(width: side, height: side)
    }

    @ViewBuilder private var art: some View {
        switch variant {
        case .tile: TileIcon()
        case .coral: CoralIcon()
        case .fox: FoxIcon()
        case .pair: PairIcon()
        }
    }
}

// MARK: - The pieces

/// One pick tile, at icon scale.
///
/// The radius is `TileMetrics.cornerRadius / TileMetrics.defaultSize.min` —
/// 28 over 92, the ratio at the tile's authored default size. (The product's
/// radius is FIXED at 28 while the tile grows to 150, so there is no single
/// true ratio; the default is the one a child sees most.)
///
/// The shadow is the tile's own pair — `0 8px 0 rgba(0,0,0,0.12)` and
/// `0 12px 20px rgba(0,0,0,0.14)` — scaled by the same factor as the tile, and
/// `.compositingGroup()` first, for the reason D54 gives at length: without it
/// SwiftUI casts a shadow per drawing primitive and the glyph smears its own
/// onto the face it sits on.
struct IconTile: View {
    let paint: Palette.TilePaint
    let glyph: String
    let side: CGFloat
    var glyphRatio: CGFloat = 0.62

    private var scale: CGFloat { side / TileMetrics.defaultSize.min }

    var body: some View {
        Text(verbatim: glyph)
            .font(Typography.rounded(side * glyphRatio, Typography.Weight.black))
            .foregroundStyle(paint.ink.color)
            .frame(width: side, height: side)
            .background(
                paint.bg.color,
                in: RoundedRectangle(
                    cornerRadius: side * (TileMetrics.cornerRadius / TileMetrics.defaultSize.min)
                )
            )
            .compositingGroup()
            .shadow(color: .black.opacity(0.12), radius: 0, y: 8 * scale * 0.55)
            .shadow(color: .black.opacity(0.14), radius: 10 * scale * 0.55, y: 12 * scale * 0.55)
    }
}

/// One confetti fleck. `ConfettiSystem` draws rotated rounded rects in six
/// colours; these are the same six, held still.
struct IconFleck: View {
    let colorIndex: Int
    let width: CGFloat
    let height: CGFloat
    let angle: Double

    var body: some View {
        RoundedRectangle(cornerRadius: min(width, height) * 0.34)
            .fill(HexColor(ConfettiSystem.colors[colorIndex % ConfettiSystem.colors.count]).color)
            .frame(width: width, height: height)
            .rotationEffect(.degrees(angle))
    }
}

/// The four flecks, in the band between the tile and the mask.
///
/// Both walls are computed, because both were got wrong by eye first — one pass
/// put flecks half under the mask, the next put them half under the tile, where
/// they read as stubs growing out of its edge:
///
///   INNER  a 560 tile turned 7° spans 280·cos7 + 280·sin7 ≈ 312 units from
///          centre. Nothing may sit closer than that.
///   OUTER  iOS's mask is a rounded square of radius 0.2237 × 1024 ≈ 229, so
///          its corner arcs are centred at (±283, ±283). A point past 283 on
///          BOTH axes is visible only while it stays within 229 of that
///          centre — every position below clears it by ≥ 150 units.
struct IconConfetti: View {
    var body: some View {
        ZStack {
            IconFleck(colorIndex: 1, width: 62, height: 62, angle: 18).offset(x: -352, y: -300)
            IconFleck(colorIndex: 2, width: 48, height: 104, angle: -24).offset(x: 350, y: -240)
            IconFleck(colorIndex: 4, width: 56, height: 56, angle: 42).offset(x: 300, y: 330)
            IconFleck(colorIndex: 5, width: 44, height: 92, angle: 14).offset(x: -330, y: 300)
        }
    }
}

// MARK: - The variants

/// The stage wash a child plays on, edge to edge.
private struct StageField: View {
    var body: some View { Palette.stage.gradient }
}

private struct TileIcon: View {
    var body: some View {
        ZStack {
            StageField()
            IconConfetti()
            IconTile(paint: Palette.tileColors[0], glyph: "A", side: 560)
                .rotationEffect(.degrees(-7))
        }
    }
}

struct CoralIcon: View {
    /// Two flecks, not four: on a saturated field they are decoration, and the
    /// cream tile is already the whole subject. Dropped entirely for the
    /// maskable cut — see `AppIconMaskableView`.
    var flecks: Bool = true

    /// The pick tile's coral, deepened into the web icon's orange — the two
    /// hexes this brand already owns, top to bottom.
    static let field = HexGradient(degrees: 180, [
        HexStop(Palette.tileColors[0].bg.hex, 0.0),
        HexStop("#E8722C", 1.0),
    ])

    /// The mascot rigs' cream tip, which is the lightest colour in the art.
    static let cream = Palette.TilePaint(bg: "#FFF6EE", ink: "#4A2317")

    var body: some View {
        ZStack {
            Self.field.gradient
            if flecks {
                IconFleck(colorIndex: 1, width: 62, height: 62, angle: 18).offset(x: -352, y: -300)
                IconFleck(colorIndex: 2, width: 48, height: 104, angle: -24).offset(x: 350, y: -240)
            }
            IconTile(paint: Self.cream, glyph: "A", side: 560)
                .rotationEffect(.degrees(-7))
        }
    }
}

private struct FoxIcon: View {
    /// Stage 5 — ear tufts, ruff and three tails, so the silhouette is
    /// unmistakably a fox. NOT stage 6: that adds the sparkle, and at icon
    /// scale it lands on the eye and reads as a defect.
    private static let config = MascotConfig(
        species: .fox,
        stage: 5,
        colors: [:],
        styles: [:],
        accessories: []
    )

    var body: some View {
        ZStack {
            StageField()
            MascotRigView(config: Self.config, mood: .cheer, size: 880)
                .offset(y: 40)
            IconTile(paint: Palette.tileColors[1], glyph: "A", side: 300)
                .rotationEffect(.degrees(12))
                .offset(x: 250, y: -300)
        }
    }
}

private struct PairIcon: View {
    var body: some View {
        ZStack {
            StageField()
            IconConfetti()
            // Pulled in from ±138/±110: at that offset the mask cut the B's
            // right stem off and the pair read as one tile with a blue smear.
            IconTile(paint: Palette.tileColors[2], glyph: "B", side: 430)
                .rotationEffect(.degrees(9))
                .offset(x: 118, y: -122)
            IconTile(paint: Palette.tileColors[0], glyph: "A", side: 480)
                .rotationEffect(.degrees(-8))
                .offset(x: -84, y: 86)
        }
    }
}

// MARK: - The maskable cut (Android / PWA)

/// The same mark, drawn for a mask that may be a CIRCLE.
///
/// `purpose: "maskable"` in a web manifest promises that everything meaningful
/// sits inside the **80 % safe zone** — a circle of radius 0.4 × side, 410
/// units here — because Android will crop to any shape it likes, circle
/// included. The shipping icon does not keep that promise: its flecks sit at
/// 462 and 424 from the centre and would come back sliced.
///
/// The tile itself is fine (its rounded corners put its furthest point at ~326),
/// so the maskable cut is simply the same icon without the confetti. Declaring
/// the flecked one maskable — which the manifest did, for the placeholder —
/// costs a visibly clipped icon on exactly the devices nobody tests on.
public struct AppIconMaskableView: View {
    public let side: CGFloat

    public init(side: CGFloat = AppIcon.designSide) {
        self.side = side
    }

    public var body: some View {
        CoralIcon(flecks: false)
            .frame(width: AppIcon.designSide, height: AppIcon.designSide)
            .clipped()
            .scaleEffect(side / AppIcon.designSide)
            .frame(width: side, height: side)
    }
}

// MARK: - Previews of the previews

/// The comparison sheet `IconForge --sheet` renders: every variant, masked the
/// way iOS masks it, and then the two sizes that decide whether an icon works —
/// 60 pt (home screen) and 29 pt (Settings, Spotlight). An icon is chosen at
/// 1024 and lived with at 60.
public struct AppIconSheet: View {
    public init() {}

    public var body: some View {
        HStack(alignment: .top, spacing: 28) {
            ForEach(AppIconVariant.allCases) { variant in
                VStack(spacing: 14) {
                    masked(variant, side: 300)
                    HStack(alignment: .bottom, spacing: 16) {
                        masked(variant, side: 60)
                        masked(variant, side: 29)
                    }
                    Text(verbatim: variant.label)
                        .font(Typography.rounded(19, Typography.Weight.bold))
                        .foregroundStyle(Palette.ink.color)
                }
            }
        }
        .padding(30)
        .background(Palette.page.color)
    }

    private func masked(_ variant: AppIconVariant, side: CGFloat) -> some View {
        AppIconView(variant, side: side)
            .clipShape(
                RoundedRectangle(
                    cornerRadius: side * AppIcon.maskRadiusRatio,
                    style: .continuous
                )
            )
    }
}
