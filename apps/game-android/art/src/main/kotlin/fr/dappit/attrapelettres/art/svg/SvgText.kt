package fr.dappit.attrapelettres.art.svg

// The one `<text>` element in the whole app (iOS: Sources/ALArt/Icons/SVGText.swift).
//
// `ExerciseIcon.tsx` draws four letterforms — `A`, `V`, `A`, `a` — with
//
//   <text x y textAnchor="middle" dominantBaseline="central"
//         fontFamily="ui-rounded,'SF Pro Rounded',system-ui,sans-serif"
//         fontWeight={900} fontSize={size} fill="#fff" />
//
// and there is no path data behind them. Two options existed: trace the glyphs
// into `d` strings, or draw real text. Tracing is exactly what the retyped-
// geometry rule forbids — hand-copied outlines with no source string to check
// against, frozen at one OS version. So the port draws real text into the same
// transformed canvas as the paths, which is also closer to the web: a browser
// resolves that font stack to a system face and lays the glyph out with the
// font's own metrics. On Android, Chrome resolves the whole stack to Roboto
// (there is no `ui-rounded` and no SF Pro Rounded; `system-ui` is what wins),
// so the Compose adapter's platform sans at weight 900 IS what this TSX renders
// on an Android phone today.
//
// What has to be reimplemented is the anchoring, because the two systems
// measure from different places:
//
//   SVG      anchors the glyph run on a BASELINE chosen by `dominant-baseline`.
//   Compose  positions a text layout by its LINE BOX's top-left corner.
//
// See [SvgTextMetrics] for the arithmetic. Getting it wrong shifts a letter by
// a couple of units, which is invisible in review and loud in a pixel diff.
//
// This file is PURE KOTLIN on purpose. The metrics arithmetic is the part worth
// testing, so it lives here over plain data where a host test can reach it; the
// thin Compose adapter (TextMeasurer in, drawText out) sits next to the icon
// view in `icons/ExerciseIcon.kt` and host tests never touch it. That keeps
// `SvgRender.kt` the one file in the svg package that knows Compose exists.

/**
 * One `<text>` node: a string anchored at `(x, y)` in the icon's 32-unit space.
 *
 * All plain data — the Compose font types would drag the Android runtime into
 * every host test that looks at a glyph.
 */
data class IconText(
    val string: String,
    val x: Double,
    val y: Double,
    /** `fontSize`, in the same 32-unit space as every coordinate here. */
    val size: Double,
    /** `fontWeight={900}` — CSS weight units, converted to a font weight at the adapter. */
    val weight: Int = 900,
    /** `fill="#fff"`. */
    val fill: String = "#fff",
)

/**
 * The `textAnchor="middle"` + `dominantBaseline="central"` arithmetic, pulled
 * out as pure functions so it can be checked on the host against known font
 * metrics instead of only by eye.
 *
 * [ascent] and [descent] are both POSITIVE distances from the alphabetic
 * baseline (up and down respectively). On the Compose side that is
 * `TextLayoutResult.firstBaseline` and `line bottom - firstBaseline` — the
 * text layout's own sign convention, no flipping required.
 */
data class SvgTextMetrics(
    val ascent: Double,
    val descent: Double,
) {
    /**
     * Where the alphabetic baseline lands for `dominant-baseline="central"`.
     *
     * "central" is the baseline halfway between the ascender and the descender,
     * i.e. `(ascent - descent) / 2` ABOVE the alphabetic one. SVG puts THAT
     * baseline on `y`, so the alphabetic baseline sits below it by the same
     * amount.
     */
    fun baselineY(anchoredAt: Double): Double = anchoredAt + (ascent - descent) / 2

    /**
     * Top-left of the line box, the anchor a Compose `drawText` call positions.
     *
     * The line box runs from `baseline - ascent` to `baseline + descent`, so
     *
     *     top = y + (ascent - descent)/2 - ascent = y - (ascent + descent)/2
     *
     * — the line box centre lands exactly on `y`. That identity is the whole
     * result: `dominant-baseline="central"` and a line-box-centred draw agree,
     * as long as the font reports no asymmetric line gap. `textAnchor="middle"`
     * is the trivial half of it.
     */
    fun topLeft(x: Double, y: Double, width: Double): SvgPoint = SvgPoint(
        (x - width / 2).toFloat(),
        (y - (ascent + descent) / 2).toFloat(),
    )
}
