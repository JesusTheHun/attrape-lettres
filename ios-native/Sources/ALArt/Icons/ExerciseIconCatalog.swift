import CoreGraphics
import SwiftUI

import ALCore

/// Port of `src/components/ExerciseIcon.tsx`.
///
/// The hub icon for each exercise: a tinted rounded badge + an in-house white
/// pictogram (no emoji). Every glyph is drawn to say what the game IS — the
/// letter games earn real letterforms, the rest earn simple silhouettes.
///
/// Keyed by `ExerciseId` on purpose: a NEW exercise won't type-check until it
/// has an icon here, so the catalog and the icon set can never drift. See
/// CLAUDE.md "Add an exercise". Decorative — the hub already labels every game
/// by name, so the icon is accessibility-hidden.
///
/// ---
///
/// **Invariant 7 is enforced by the `switch` below, and only by it.** Three
/// rules keep it that way (spec/shell.md §3.4):
///
/// 1. `exerciseIconSpec` has **no `default:`** and **no `@unknown default:`**.
///    `@unknown default` exists for library-evolution enums; `ExerciseId` is
///    in-module and frozen, so it is not allowed here — it would silently turn
///    the compile error into a blank icon.
/// 2. `ExerciseId` and this function must stay in the same package and the
///    package must not be built with `-enable-library-evolution`, or the
///    compiler starts *requiring* `@unknown default` and rule 1 becomes
///    impossible.
/// 3. **No `[ExerciseId: ExerciseIconSpec]` dictionary.** A dictionary compiles
///    with a missing key and returns `nil` at runtime — the exact regression
///    invariant 7 exists to prevent. TypeScript's `Record<ExerciseId, …>` is a
///    total function checked at the object literal; a Swift `switch` is the
///    only shape with the same guarantee.

// MARK: - Node types

/// One drawing step of an icon, in the 32-unit viewBox space.
///
/// Two cases because SVG has one thing SwiftUI's `Path` does not: text. Draw
/// order is SVG painter's order = JSX statement order, so shapes and text
/// interleave in the list rather than text being lifted to the top or bottom —
/// `first-letter`'s sparkle draws AFTER its `A`, `pick-vowel`'s dashed box
/// AFTER its `V`.
public enum IconNode {
    /// A run of fills and strokes recorded through `SVGCanvas`.
    case shapes([SVGDrawNode])
    /// A `<text>` glyph (see `SVGText.swift`).
    case text(IconText)
}

public struct ExerciseIconSpec {
    /// The badge colour. All 17 are distinct — a test asserts it, because a
    /// copy-pasted branch compiles perfectly.
    public let tint: String
    public let nodes: [IconNode]

    public init(tint: String, nodes: [IconNode]) {
        self.tint = tint
        self.nodes = nodes
    }
}

// MARK: - Shared style

/// ```ts
/// /** Shared white-stroke style for pictogram lines. */
/// const line = {
///   fill: "none", stroke: "#fff", strokeWidth: 2.4,
///   strokeLinecap: "round", strokeLinejoin: "round",
/// } as const;
/// ```
///
/// Spread into 8 paths and 2 circles, with per-node `strokeWidth` / `opacity`
/// overrides. Written once here, overridden at the call sites with `.with(…)`,
/// exactly as in the TSX.
public struct IconStroke: Equatable {
    public var color: String = "#fff"
    public var width: Double = 2.4
    public var opacity: Double = 1
    public var dash: [CGFloat] = []
    public var lineCap: CGLineCap = .round
    public var lineJoin: CGLineJoin = .round

    var paint: SVGPaint { .hex(color, opacity: opacity) }

    var style: StrokeStyle {
        StrokeStyle(lineWidth: width, lineCap: lineCap, lineJoin: lineJoin, dash: dash)
    }

    func with(
        color: String? = nil,
        width: Double? = nil,
        opacity: Double? = nil,
        dash: [CGFloat]? = nil,
        lineCap: CGLineCap? = nil,
        lineJoin: CGLineJoin? = nil
    ) -> IconStroke {
        IconStroke(
            color: color ?? self.color,
            width: width ?? self.width,
            opacity: opacity ?? self.opacity,
            dash: dash ?? self.dash,
            lineCap: lineCap ?? self.lineCap,
            lineJoin: lineJoin ?? self.lineJoin
        )
    }
}

public extension IconStroke {
    /// The `line` object from the TSX. Namespaced rather than a bare
    /// module-level `let line` — `line` is far too common a name to own.
    static let line = IconStroke()
}

private let white = SVGPaint.hex("#fff")

private func whiteFill(_ opacity: Double) -> SVGPaint { .hex("#fff", opacity: opacity) }

private func circle(_ cx: Double, _ cy: Double, _ r: Double) -> Path {
    Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
}

/// `<rect x y width height rx>` — SVG's `rx` with no `ry` means `ry = rx`, a
/// uniform circular corner.
private func rect(_ x: Double, _ y: Double, _ w: Double, _ h: Double, rx: Double) -> Path {
    Path(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: rx)
}

private extension SVGCanvas {
    mutating func strokePath(_ path: Path, _ stroke: IconStroke) {
        self.stroke(path, with: stroke.paint, style: stroke.style)
    }
}

/// Accumulates nodes in statement order, breaking the shape run whenever a
/// `<text>` appears so painter's order survives.
private struct IconBuilder {
    private var done: [IconNode] = []
    private var pending = SVGCanvas()

    mutating func draw(_ body: (inout SVGCanvas) -> Void) {
        body(&pending)
    }

    mutating func text(_ node: IconText) {
        flush()
        done.append(.text(node))
    }

    private mutating func flush() {
        guard !pending.nodes.isEmpty else { return }
        done.append(.shapes(pending.nodes))
        pending = SVGCanvas()
    }

    mutating func finish() -> [IconNode] {
        flush()
        return done
    }
}

private func spec(_ tint: String, _ build: (inout IconBuilder) -> Void) -> ExerciseIconSpec {
    var builder = IconBuilder()
    build(&builder)
    return ExerciseIconSpec(tint: tint, nodes: builder.finish())
}

/// ```tsx
/// function Glyph({ x, y, size, children }) {
///   return <text x={x} y={y} textAnchor="middle" dominantBaseline="central"
///                fontFamily={ROUNDED} fontWeight={900} fontSize={size} fill="#fff">{children}</text>;
/// }
/// ```
private func glyph(_ string: String, x: Double, y: Double, size: Double) -> IconText {
    IconText(string, x: x, y: y, size: size, weight: .black, fill: "#fff")
}

/// ```tsx
/// /**
///  * The "écritures mêlées" corner chip — a white sub-badge with a shuffle mark.
///  * Both mixed twins wear it, so a child reads them as the same game "mixed up".
///  */
/// ```
private func shuffleChip(tint: String, into c: inout SVGCanvas) {
    c.fill(circle(23.5, 23.5, 6.6), with: white)
    // <g stroke={tint} strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" fill="none">
    let s = IconStroke.line.with(color: tint, width: 1.7)
    c.strokePath(Path(svg: "M20.5 26 L26 21"), s)
    c.strokePath(Path(svg: "M20.5 21 L26 26"), s)
    c.strokePath(Path(svg: "M23.8 21 L26 21 L26 23.2"), s)
    c.strokePath(Path(svg: "M23.8 26 L26 26 L26 23.8"), s)
}

// MARK: - The catalog

/// `const GLYPHS: Record<ExerciseId, { tint: string; glyph: ReactNode }>`.
///
/// NO `default:`. NO `@unknown default:`. See the file header.
public func exerciseIconSpec(_ id: ExerciseId) -> ExerciseIconSpec {
    switch id {

    // First letter — a big A leading the word, with a little sparkle on it.
    case .firstLetter:
        return spec("#FF8A5B") { b in
            b.text(glyph("A", x: 15, y: 17.5, size: 17))
            b.draw { c in
                c.fill(
                    Path(svg: "M24 5.5 L24.98 7.02 L27.5 9 L24.98 10.98 L24 12.5 L23.02 10.98 L20.5 9 L23.02 7.02 Z"),
                    with: white
                )
            }
        }

    // Find the sound — the magnifier again (the "trouve" language of the hub),
    // but the lens holds a sound: a dot radiating two waves instead of an x.
    case .findSound:
        return spec("#7CB342") { b in
            b.draw { c in
                c.strokePath(circle(13.5, 14, 6.2), .line)
                c.strokePath(Path(svg: "M18 18.5 L23.5 24"), IconStroke.line.with(width: 2.8))
                c.fill(circle(11, 14, 1.4), with: white)
                c.strokePath(Path(svg: "M13.4 11.9 a3 3 0 0 1 0 4.2"), IconStroke.line.with(width: 1.7))
                c.strokePath(Path(svg: "M15.6 10.4 a5.2 5.2 0 0 1 0 7.2"), IconStroke.line.with(width: 1.7, opacity: 0.7))
            }
        }

    // Hear the syllable — sound waves arriving at two coupled tiles (consonne +
    // voyelle), joined underneath by the fusion arc: two letters, one syllable.
    case .hearSyllable:
        return spec("#26A69A") { b in
            b.draw { c in
                c.strokePath(Path(svg: "M8.5 12.8 a4.2 4.2 0 0 0 0 6.4"), IconStroke.line.with(width: 2))
                c.strokePath(Path(svg: "M6 10.2 a8 8 0 0 0 0 11.6"), IconStroke.line.with(width: 2, opacity: 0.65))
                c.fill(rect(12, 10.5, 7.5, 9.5, rx: 2.4), with: white)
                c.fill(rect(20.5, 10.5, 6.5, 9.5, rx: 2.4), with: whiteFill(0.8))
                c.strokePath(Path(svg: "M13.5 22.8 C 16.5 25.6, 22 25.6, 25 22.8"), IconStroke.line.with(width: 2))
            }
        }

    // The right vowel — the consonant is written, the vowel's place is still open.
    case .pickVowel:
        return spec("#F06292") { b in
            b.text(glyph("V", x: 11, y: 16, size: 17))
            b.draw { c in
                // NB: this rect does NOT spread `line` — it names its own
                // stroke, so the caps and joins are SVG's defaults (butt,
                // miter), not round. Do not "helpfully" round them.
                c.strokePath(
                    rect(18, 9, 9.5, 14, rx: 2.8),
                    IconStroke(color: "#fff", width: 1.9, dash: [2.5, 2.3], lineCap: .butt, lineJoin: .miter)
                )
            }
        }

    // Complete the word — three slots, the middle piece missing (dashed).
    case .fillBlank:
        return spec("#7C6FF0") { b in
            b.draw { c in
                c.fill(rect(6, 12, 6, 8, rx: 1.7), with: white)
                c.strokePath(
                    rect(13, 12, 6, 8, rx: 1.7),
                    IconStroke(color: "#fff", width: 1.8, dash: [2.4, 2.2], lineCap: .butt, lineJoin: .miter)
                )
                c.fill(rect(20, 12, 6, 8, rx: 1.7), with: white)
            }
        }

    // Order the syllables — bars beside up/down reorder arrows.
    case .orderSyllables:
        return spec("#2EC4B6") { b in
            b.draw { c in
                c.fill(rect(13, 10, 13, 5, rx: 2.5), with: white)
                c.fill(rect(13, 17, 13, 5, rx: 2.5), with: whiteFill(0.72))
                c.strokePath(Path(svg: "M8 10.5 V21.5"), .line)
                c.strokePath(Path(svg: "M5.8 12.4 L8 10 L10.2 12.4"), .line)
                c.strokePath(Path(svg: "M5.8 19.6 L8 22 L10.2 19.6"), .line)
            }
        }

    // Find the intruder — a magnifier catching the odd one (a small x).
    case .findIntruder:
        return spec("#4D9DE0") { b in
            b.draw { c in
                c.strokePath(circle(14, 14, 6), .line)
                c.strokePath(Path(svg: "M18.4 18.4 L23.5 23.5"), IconStroke.line.with(width: 2.8))
                // NB: named attributes again — round CAP, default (miter) JOIN.
                c.strokePath(
                    Path(svg: "M12 12 L16 16 M16 12 L12 16"),
                    IconStroke(color: "#fff", width: 1.8, lineCap: .round, lineJoin: .miter)
                )
            }
        }

    // Make the sound — a speaker with two sound waves.
    case .spellSound:
        return spec("#F4A62A") { b in
            b.draw { c in
                c.fill(Path(svg: "M8 13 H11 L15 9 V23 L11 19 H8 Z"), with: white)
                c.strokePath(Path(svg: "M18 12 a6 6 0 0 1 0 8"), IconStroke.line.with(width: 2.2))
                c.strokePath(Path(svg: "M20.6 9 a10 10 0 0 1 0 14"), IconStroke.line.with(width: 2.2, opacity: 0.7))
            }
        }

    // Write the syllable — a pencil over a writing line.
    case .spellSyllable:
        return spec("#EF5D8F") { b in
            b.draw { c in
                c.fill(Path(svg: "M21 9 l2 2 -9 9 -3 1 1 -3 z"), with: white)
                c.strokePath(Path(svg: "M9 24 H23"), IconStroke.line.with(width: 2))
            }
        }

    // The syllable + intruders — aim for the right one (a bullseye).
    case .spellSyllablePlus:
        return spec("#E4572E") { b in
            b.draw { c in
                c.strokePath(circle(16, 16, 8), .line)
                c.strokePath(circle(16, 16, 4), .line)
                c.fill(circle(16, 16, 1.7), with: white)
            }
        }

    // Write two syllables — two writing lines + a pencil.
    case .spellTwoSyllables:
        return spec("#3BA55D") { b in
            b.draw { c in
                c.strokePath(Path(svg: "M7 15 H17"), IconStroke.line.with(width: 2.6))
                c.strokePath(Path(svg: "M7 21 H16"), IconStroke.line.with(width: 2.6, opacity: 0.8))
                c.fill(Path(svg: "M21 8 l3 3 -8 8 -4 1 1 -4 z"), with: white)
            }
        }

    // Read the word — a framed picture (sun + hills).
    case .readImage:
        return spec("#6C8BE0") { b in
            b.draw { c in
                c.strokePath(rect(6, 8, 20, 16, rx: 3), .line)
                c.fill(circle(11.5, 13, 1.9), with: white)
                c.strokePath(Path(svg: "M7 22 L12 16 L15 19 L18 15 L25 22"), IconStroke.line.with(width: 2.2))
            }
        }

    // Upper- and lowercase — a big A and a little a.
    case .matchCase:
        return spec("#F2994A") { b in
            b.text(glyph("A", x: 12, y: 18, size: 16))
            b.text(glyph("a", x: 22, y: 19, size: 11))
        }

    // Cursive letters — one flowing looped stroke.
    case .matchScript:
        return spec("#B06AB3") { b in
            b.draw { c in
                c.strokePath(
                    Path(svg: "M8 20 C 8 12, 13 11, 12.5 16 C 12 20, 15 21, 19 18 C 21 16.5, 22 15, 22.5 13"),
                    .line
                )
            }
        }

    // Sound twins — two twin tiles joined by one arc: one sound, two writings.
    case .soundTwins:
        return spec("#D81B60") { b in
            b.draw { c in
                c.fill(rect(6.5, 14.5, 8, 8, rx: 2.2), with: white)
                c.fill(rect(17.5, 14.5, 8, 8, rx: 2.2), with: whiteFill(0.8))
                c.strokePath(Path(svg: "M10.5 12.5 C 12 8.5, 20 8.5, 21.5 12.5"), IconStroke.line.with(width: 2))
            }
        }

    // The syllable + intruders, mixed writings — the bullseye, shuffled.
    case .spellSyllablePlusMixed:
        return spec("#B23A2A") { b in
            b.draw { c in
                c.strokePath(circle(14, 14, 6.5), .line)
                c.strokePath(circle(14, 14, 3), .line)
                c.fill(circle(14, 14, 1.5), with: white)
                shuffleChip(tint: "#B23A2A", into: &c)
            }
        }

    // Two syllables, mixed writings — the two lines + pencil, shuffled.
    case .spellTwoSyllablesMixed:
        return spec("#2E7D5B") { b in
            b.draw { c in
                c.strokePath(Path(svg: "M6 13 H15"), IconStroke.line.with(width: 2.4))
                c.strokePath(Path(svg: "M6 19 H14"), IconStroke.line.with(width: 2.4, opacity: 0.8))
                c.fill(Path(svg: "M18 8 l3 3 -7 7 -4 1 1 -4 z"), with: white)
                shuffleChip(tint: "#2E7D5B", into: &c)
            }
        }
    }
}
