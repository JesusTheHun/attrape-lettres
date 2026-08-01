package fr.dappit.attrapelettres.art.icons

import fr.dappit.attrapelettres.art.svg.IconText
import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPathCommand
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.core.domain.ExerciseId

// Port of `src/components/ExerciseIcon.tsx` (iOS: ExerciseIconCatalog.swift).
//
// The hub icon for each exercise: a tinted rounded badge + an in-house white
// pictogram (no emoji). Every glyph is drawn to say what the game IS — the
// letter games earn real letterforms, the rest earn simple silhouettes.
//
// Keyed by `ExerciseId` on purpose: a NEW exercise won't compile until it has
// an icon here, so the catalog and the icon set can never drift. See CLAUDE.md
// "Add an exercise". Decorative — the hub already labels every game by name, so
// the icon view is hidden from accessibility services.
//
// ---
//
// INVARIANT 7 IS ENFORCED BY THE `when` BELOW, AND ONLY BY IT. Two rules keep
// it that way:
//
// 1. `exerciseIconSpec` is a `when` EXPRESSION over the enum with no `else`
//    branch and no default tint. Kotlin requires an expression-position `when`
//    over an enum to be exhaustive, so "somebody added an exercise without
//    drawing it" is a compile error in this file, not a blank badge at runtime.
//    An `else` would silently turn that compile error back into the blank icon.
// 2. NO `Map<ExerciseId, ExerciseIconSpec>`. A map compiles with a missing key
//    and returns null at runtime — the exact regression invariant 7 exists to
//    prevent. TypeScript's `Record<ExerciseId, ...>` is a total function
//    checked at the object literal; a Kotlin exhaustive `when` is the shape
//    with the same guarantee.

// --- Node types --------------------------------------------------------------

/**
 * One drawing step of an icon, in the 32-unit viewBox space.
 *
 * Two cases because SVG has one thing a path list does not: text. Draw order is
 * SVG painter's order = JSX statement order, so shapes and text interleave in
 * the list rather than text being lifted to the top or bottom — `first-letter`'s
 * sparkle draws AFTER its `A`, `pick-vowel`'s dashed box AFTER its `V`.
 */
sealed interface IconNode {
    /** A run of fills and strokes recorded through [SvgCanvas]. */
    data class Shapes(val nodes: List<SvgDrawNode>) : IconNode

    /** A `<text>` glyph (see `svg/SvgText.kt`). */
    data class Text(val text: IconText) : IconNode
}

data class ExerciseIconSpec(
    /**
     * The badge colour. All 17 are distinct — a test asserts it, because a
     * copy-pasted branch compiles perfectly.
     */
    val tint: String,
    val nodes: List<IconNode>,
)

// --- Shared style ------------------------------------------------------------

/**
 * ```ts
 * // Shared white-stroke style for pictogram lines.
 * const line = {
 *   fill: "none", stroke: "#fff", strokeWidth: 2.4,
 *   strokeLinecap: "round", strokeLinejoin: "round",
 * } as const;
 * ```
 *
 * Spread into 8 paths and 2 circles, with per-node `strokeWidth` / `opacity`
 * overrides. Written once here, overridden at the call sites with data-class
 * `copy(...)`, exactly as the TSX spreads `{...line}` and then overrides.
 */
data class IconStroke(
    val color: String = "#fff",
    val width: Double = 2.4,
    val opacity: Double = 1.0,
    val dash: List<Double> = emptyList(),
    val cap: SvgLineCap = SvgLineCap.ROUND,
    val join: SvgLineJoin = SvgLineJoin.ROUND,
) {
    val paint: SvgPaint get() = SvgPaint.hex(color, opacity)

    val style: SvgStrokeStyle get() = SvgStrokeStyle(lineWidth = width, cap = cap, join = join, dash = dash)

    companion object {
        /**
         * The `line` object from the TSX. Namespaced rather than a bare
         * top-level `LINE` — `line` is far too common a name to own.
         */
        val LINE = IconStroke()
    }
}

private val WHITE = SvgPaint.hex("#fff")

private fun whiteFill(opacity: Double): SvgPaint = SvgPaint.hex("#fff", opacity)

private fun circle(cx: Double, cy: Double, r: Double): List<SvgPathCommand> =
    SvgShapes.circle(cx, cy, r)

/**
 * `<rect x y width height rx>` — SVG's `rx` with no `ry` means `ry = rx`, a
 * uniform circular corner.
 */
private fun rect(x: Double, y: Double, w: Double, h: Double, rx: Double): List<SvgPathCommand> =
    SvgShapes.roundedRect(x, y, w, h, rx)

private fun SvgCanvas.strokePath(d: String, stroke: IconStroke) {
    stroke(d, stroke.paint, stroke.style)
}

private fun SvgCanvas.strokePath(path: List<SvgPathCommand>, stroke: IconStroke) {
    stroke(path, stroke.paint, stroke.style)
}

/**
 * Accumulates nodes in statement order, breaking the shape run whenever a
 * `<text>` appears so painter's order survives.
 */
private class IconBuilder {
    private val done = ArrayList<IconNode>()
    private var pending = SvgCanvas()

    fun draw(body: (SvgCanvas) -> Unit) {
        body(pending)
    }

    fun text(node: IconText) {
        flush()
        done.add(IconNode.Text(node))
    }

    private fun flush() {
        if (pending.nodes.isEmpty()) return
        done.add(IconNode.Shapes(pending.nodes))
        pending = SvgCanvas()
    }

    fun finish(): List<IconNode> {
        flush()
        return done
    }
}

private fun spec(tint: String, build: IconBuilder.() -> Unit): ExerciseIconSpec {
    val builder = IconBuilder()
    builder.build()
    return ExerciseIconSpec(tint = tint, nodes = builder.finish())
}

/**
 * ```tsx
 * function Glyph({ x, y, size, children }) {
 *   return <text x={x} y={y} textAnchor="middle" dominantBaseline="central"
 *                fontFamily={ROUNDED} fontWeight={900} fontSize={size} fill="#fff">{children}</text>;
 * }
 * ```
 */
private fun glyph(string: String, x: Double, y: Double, size: Double): IconText =
    IconText(string, x = x, y = y, size = size, weight = 900, fill = "#fff")

/**
 * The "écritures mêlées" corner chip — a white sub-badge with a shuffle mark.
 * Both mixed twins wear it, so a child reads them as the same game "mixed up".
 */
private fun shuffleChip(tint: String, c: SvgCanvas) {
    c.fill(circle(23.5, 23.5, 6.6), WHITE)
    // <g stroke={tint} strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" fill="none">
    val s = IconStroke.LINE.copy(color = tint, width = 1.7)
    c.strokePath("M20.5 26 L26 21", s)
    c.strokePath("M20.5 21 L26 26", s)
    c.strokePath("M23.8 21 L26 21 L26 23.2", s)
    c.strokePath("M23.8 26 L26 26 L26 23.8", s)
}

// --- The catalog -------------------------------------------------------------

/**
 * `const GLYPHS: Record<ExerciseId, { tint: string; glyph: ReactNode }>`.
 *
 * NO `else`. NO default tint. See the file header.
 */
fun exerciseIconSpec(id: ExerciseId): ExerciseIconSpec = when (id) {

    // First letter — a big A leading the word, with a little sparkle on it.
    ExerciseId.FIRST_LETTER -> spec("#FF8A5B") {
        text(glyph("A", x = 15.0, y = 17.5, size = 17.0))
        draw { c ->
            c.fill(
                "M24 5.5 L24.98 7.02 L27.5 9 L24.98 10.98 L24 12.5 L23.02 10.98 L20.5 9 L23.02 7.02 Z",
                WHITE,
            )
        }
    }

    // Find the sound — the magnifier again (the "trouve" language of the hub),
    // but the lens holds a sound: a dot radiating two waves instead of an x.
    ExerciseId.FIND_SOUND -> spec("#7CB342") {
        draw { c ->
            c.strokePath(circle(13.5, 14.0, 6.2), IconStroke.LINE)
            c.strokePath("M18 18.5 L23.5 24", IconStroke.LINE.copy(width = 2.8))
            c.fill(circle(11.0, 14.0, 1.4), WHITE)
            c.strokePath("M13.4 11.9 a3 3 0 0 1 0 4.2", IconStroke.LINE.copy(width = 1.7))
            c.strokePath("M15.6 10.4 a5.2 5.2 0 0 1 0 7.2", IconStroke.LINE.copy(width = 1.7, opacity = 0.7))
        }
    }

    // Hear the syllable — sound waves arriving at two coupled tiles (consonne +
    // voyelle), joined underneath by the fusion arc: two letters, one syllable.
    ExerciseId.HEAR_SYLLABLE -> spec("#26A69A") {
        draw { c ->
            c.strokePath("M8.5 12.8 a4.2 4.2 0 0 0 0 6.4", IconStroke.LINE.copy(width = 2.0))
            c.strokePath("M6 10.2 a8 8 0 0 0 0 11.6", IconStroke.LINE.copy(width = 2.0, opacity = 0.65))
            c.fill(rect(12.0, 10.5, 7.5, 9.5, rx = 2.4), WHITE)
            c.fill(rect(20.5, 10.5, 6.5, 9.5, rx = 2.4), whiteFill(0.8))
            c.strokePath("M13.5 22.8 C 16.5 25.6, 22 25.6, 25 22.8", IconStroke.LINE.copy(width = 2.0))
        }
    }

    // The right vowel — the consonant is written, the vowel's place is still open.
    ExerciseId.PICK_VOWEL -> spec("#F06292") {
        text(glyph("V", x = 11.0, y = 16.0, size = 17.0))
        draw { c ->
            // NB: this rect does NOT spread `line` — it names its own stroke,
            // so the caps and joins are SVG's defaults (butt, miter), not
            // round. Do not "helpfully" round them.
            c.strokePath(
                rect(18.0, 9.0, 9.5, 14.0, rx = 2.8),
                IconStroke(
                    color = "#fff",
                    width = 1.9,
                    dash = listOf(2.5, 2.3),
                    cap = SvgLineCap.BUTT,
                    join = SvgLineJoin.MITER,
                ),
            )
        }
    }

    // Complete the word — three slots, the middle piece missing (dashed).
    ExerciseId.FILL_BLANK -> spec("#7C6FF0") {
        draw { c ->
            c.fill(rect(6.0, 12.0, 6.0, 8.0, rx = 1.7), WHITE)
            c.strokePath(
                rect(13.0, 12.0, 6.0, 8.0, rx = 1.7),
                IconStroke(
                    color = "#fff",
                    width = 1.8,
                    dash = listOf(2.4, 2.2),
                    cap = SvgLineCap.BUTT,
                    join = SvgLineJoin.MITER,
                ),
            )
            c.fill(rect(20.0, 12.0, 6.0, 8.0, rx = 1.7), WHITE)
        }
    }

    // Order the syllables — bars beside up/down reorder arrows.
    ExerciseId.ORDER_SYLLABLES -> spec("#2EC4B6") {
        draw { c ->
            c.fill(rect(13.0, 10.0, 13.0, 5.0, rx = 2.5), WHITE)
            c.fill(rect(13.0, 17.0, 13.0, 5.0, rx = 2.5), whiteFill(0.72))
            c.strokePath("M8 10.5 V21.5", IconStroke.LINE)
            c.strokePath("M5.8 12.4 L8 10 L10.2 12.4", IconStroke.LINE)
            c.strokePath("M5.8 19.6 L8 22 L10.2 19.6", IconStroke.LINE)
        }
    }

    // Find the intruder — a magnifier catching the odd one (a small x).
    ExerciseId.FIND_INTRUDER -> spec("#4D9DE0") {
        draw { c ->
            c.strokePath(circle(14.0, 14.0, 6.0), IconStroke.LINE)
            c.strokePath("M18.4 18.4 L23.5 23.5", IconStroke.LINE.copy(width = 2.8))
            // NB: named attributes again — round CAP, default (miter) JOIN.
            c.strokePath(
                "M12 12 L16 16 M16 12 L12 16",
                IconStroke(color = "#fff", width = 1.8, cap = SvgLineCap.ROUND, join = SvgLineJoin.MITER),
            )
        }
    }

    // Make the sound — a speaker with two sound waves.
    ExerciseId.SPELL_SOUND -> spec("#F4A62A") {
        draw { c ->
            c.fill("M8 13 H11 L15 9 V23 L11 19 H8 Z", WHITE)
            c.strokePath("M18 12 a6 6 0 0 1 0 8", IconStroke.LINE.copy(width = 2.2))
            c.strokePath("M20.6 9 a10 10 0 0 1 0 14", IconStroke.LINE.copy(width = 2.2, opacity = 0.7))
        }
    }

    // Write the syllable — a pencil over a writing line.
    ExerciseId.SPELL_SYLLABLE -> spec("#EF5D8F") {
        draw { c ->
            c.fill("M21 9 l2 2 -9 9 -3 1 1 -3 z", WHITE)
            c.strokePath("M9 24 H23", IconStroke.LINE.copy(width = 2.0))
        }
    }

    // The syllable + intruders — aim for the right one (a bullseye).
    ExerciseId.SPELL_SYLLABLE_PLUS -> spec("#E4572E") {
        draw { c ->
            c.strokePath(circle(16.0, 16.0, 8.0), IconStroke.LINE)
            c.strokePath(circle(16.0, 16.0, 4.0), IconStroke.LINE)
            c.fill(circle(16.0, 16.0, 1.7), WHITE)
        }
    }

    // Write two syllables — two writing lines + a pencil.
    ExerciseId.SPELL_TWO_SYLLABLES -> spec("#3BA55D") {
        draw { c ->
            c.strokePath("M7 15 H17", IconStroke.LINE.copy(width = 2.6))
            c.strokePath("M7 21 H16", IconStroke.LINE.copy(width = 2.6, opacity = 0.8))
            c.fill("M21 8 l3 3 -8 8 -4 1 1 -4 z", WHITE)
        }
    }

    // Read the word — a framed picture (sun + hills).
    ExerciseId.READ_IMAGE -> spec("#6C8BE0") {
        draw { c ->
            c.strokePath(rect(6.0, 8.0, 20.0, 16.0, rx = 3.0), IconStroke.LINE)
            c.fill(circle(11.5, 13.0, 1.9), WHITE)
            c.strokePath("M7 22 L12 16 L15 19 L18 15 L25 22", IconStroke.LINE.copy(width = 2.2))
        }
    }

    // Upper- and lowercase — a big A and a little a.
    ExerciseId.MATCH_CASE -> spec("#F2994A") {
        text(glyph("A", x = 12.0, y = 18.0, size = 16.0))
        text(glyph("a", x = 22.0, y = 19.0, size = 11.0))
    }

    // Cursive letters — one flowing looped stroke.
    ExerciseId.MATCH_SCRIPT -> spec("#B06AB3") {
        draw { c ->
            c.strokePath(
                "M8 20 C 8 12, 13 11, 12.5 16 C 12 20, 15 21, 19 18 C 21 16.5, 22 15, 22.5 13",
                IconStroke.LINE,
            )
        }
    }

    // Sound twins — two twin tiles joined by one arc: one sound, two writings.
    ExerciseId.SOUND_TWINS -> spec("#D81B60") {
        draw { c ->
            c.fill(rect(6.5, 14.5, 8.0, 8.0, rx = 2.2), WHITE)
            c.fill(rect(17.5, 14.5, 8.0, 8.0, rx = 2.2), whiteFill(0.8))
            c.strokePath("M10.5 12.5 C 12 8.5, 20 8.5, 21.5 12.5", IconStroke.LINE.copy(width = 2.0))
        }
    }

    // The syllable + intruders, mixed writings — the bullseye, shuffled.
    ExerciseId.SPELL_SYLLABLE_PLUS_MIXED -> spec("#B23A2A") {
        draw { c ->
            c.strokePath(circle(14.0, 14.0, 6.5), IconStroke.LINE)
            c.strokePath(circle(14.0, 14.0, 3.0), IconStroke.LINE)
            c.fill(circle(14.0, 14.0, 1.5), WHITE)
            shuffleChip("#B23A2A", c)
        }
    }

    // Two syllables, mixed writings — the two lines + pencil, shuffled.
    ExerciseId.SPELL_TWO_SYLLABLES_MIXED -> spec("#2E7D5B") {
        draw { c ->
            c.strokePath("M6 13 H15", IconStroke.LINE.copy(width = 2.4))
            c.strokePath("M6 19 H14", IconStroke.LINE.copy(width = 2.4, opacity = 0.8))
            c.fill("M18 8 l3 3 -7 7 -4 1 1 -4 z", WHITE)
            shuffleChip("#2E7D5B", c)
        }
    }
}
