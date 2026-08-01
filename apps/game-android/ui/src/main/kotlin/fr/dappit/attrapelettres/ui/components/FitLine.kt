package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Port of `src/components/FitLine.tsx` (iOS: Sources/ALUI/Components/FitLine.swift).
//
// The word presented to the child must always fit on ONE line. FitLine lays its
// children out in a no-wrap row at their NATURAL size and, when that row would
// overflow the width it is given, shrinks the whole row with a transform so it
// still fits. The wrapper's height is pinned to the scaled height so whatever
// sits below stays snug against the row.
//
// The TSX carries a bug fix this port must keep ("stop the shrunk word row
// collapsing onto the tray"): the row is measured at its natural size,
// INDEPENDENT of the wrapper's pinned height. On the web that is `items-start`
// on the wrapper plus `offsetWidth`/`offsetHeight` (layout metrics, which ignore
// the transform); without it the row stretches to the pinned height, the next
// measurement reads a smaller natural height, computes a smaller scale, pins a
// smaller height — a ResizeObserver feedback loop that visually collapses the
// word row onto the tray beneath it.
//
// This port cannot reproduce that loop even by accident, and that is the whole
// reason it is a `Layout` and not a `BoxWithConstraints` + state:
//
//   1. The row is measured ONCE, with `Constraints()` — no maximum at all — so
//      what comes back is its ideal size. Neither the width it is given nor the
//      height this layout reports can change that number.
//   2. The scale is applied at PLACEMENT time (`placeWithLayer`), so it never
//      feeds back into a measurement, exactly as a CSS transform never feeds
//      back into layout.
//   3. Nothing is written to state, so fitting never recomposes the exercise
//      (invariant 2's spirit; the TSX writes the transform straight to the DOM
//      for the same reason).
//
// Anyone touching this file: keep the `Constraints()` measure unbounded, keep
// the scale in the layer, and keep [FitLineFit] pure and clamped at 1 — the row
// is never ENLARGED, only shrunk.

/**
 * `FitLine.tsx`'s `fit()`, as arithmetic:
 *
 * ```ts
 * const k = natural > 0 && natural > available ? available / natural : 1;
 * inner.style.transform = k < 1 ? `scale(${k})` : "";
 * outer.style.height    = k < 1 ? `${naturalHeight * k}px` : "";
 * ```
 *
 * Pure, so a host test drives it with widths alone.
 */
object FitLineFit {

    /**
     * The shrink factor: 1 when the row fits (or nothing is measured yet),
     * `available / natural` when it would overflow. Never above 1.
     *
     * The comparison is a STRICT `>`, as authored: a row exactly as wide as the
     * space it has is left alone rather than multiplied by 1.0.
     */
    fun scale(natural: Float, available: Float): Float =
        if (natural > 0f && natural > available) available / natural else 1f

    /**
     * The wrapper's pinned height — only pinned while actually shrunk (`k < 1`).
     * `null` means "natural height, no override", the TSX's empty style string.
     */
    fun pinnedHeight(naturalHeight: Float, scale: Float): Float? =
        if (scale < 1f) naturalHeight * scale else null
}

/**
 * The one-line shrink-to-fit row.
 *
 * Callers put margins OUTSIDE (the TSX's `className`) and pass the row gap here
 * (the TSX's `rowClassName`: `gap-2` = 8 dp, `gap-1.5` = 6 dp), so the four call
 * sites port unchanged.
 *
 * [contentDescription] is the TSX's `ariaLabel` on the outer wrapper
 * (SpellSyllable passes « Mot à compléter »).
 */
@Composable
fun FitLine(
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 0.dp,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    // Bound outside the semantics lambda: inside it, the bare name would resolve
    // against the receiver's own `contentDescription` property.
    val labelled = contentDescription
        ?.let { text -> modifier.semantics { this.contentDescription = text } }
        ?: modifier

    Layout(
        modifier = labelled,
        content = {
            // flex-nowrap: one row, natural sizes, no wrapping and no shrinking.
            Row(
                horizontalArrangement = Arrangement.spacedBy(rowSpacing),
                verticalAlignment = Alignment.CenterVertically,
                content = { content() },
            )
        },
    ) { measurables, constraints ->
        val row = measurables.first()
        // Rule 1: unbounded. This is the `offsetWidth` of the port.
        val natural = row.measure(Constraints())
        val available =
            if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE

        val k = FitLineFit.scale(natural.width.toFloat(), available.toFloat())
        // `w-full` on the wrapper.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural.width
        val height =
            FitLineFit.pinnedHeight(natural.height.toFloat(), k)?.roundToInt() ?: natural.height

        layout(width, height) {
            // justify-center, then `transform-origin: center top` on the row, so
            // the shrunk row stays centred and hangs from the top edge.
            val x = ((width - natural.width) / 2f).roundToInt()
            natural.placeWithLayer(x, 0) {
                scaleX = k
                scaleY = k
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}
