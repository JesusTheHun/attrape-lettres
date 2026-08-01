package fr.dappit.attrapelettres.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

// `flex flex-wrap items-center justify-center gap-N`, ported once.
//
// Two places in this module are authored `flex-wrap` in the TSX — the end
// buttons and the Finished star recap — and both need the CSS behaviour, not
// Compose's: a `Row` whose children do not fit SHRINKS them, which on a narrow
// phone squashes « Suivant » instead of dropping it to a second line. iOS wrote
// the same layout by hand (`ComponentsWrapRow` in EndButtons.swift) for the same
// reason.
//
// Compose does ship `FlowRow`, and it is still experimental; more to the point,
// the part that has to be RIGHT here is the line breaking, and a host test
// cannot run a composition. So the arithmetic is a pure function over measured
// sizes ([WrapRowLines.lines]) and the composable below is a thin `Layout` that
// feeds it `Placeable`s. Same split as everywhere else in :ui.

/** One laid-out line: which children are on it, and how big it is. */
data class WrapLine(
    /** Indices into the caller's size list, in order. */
    val items: List<Int>,
    /** The line's width INCLUDING the gaps between its children. */
    val width: Int,
    /** `items-center`: the line is as tall as its tallest child. */
    val height: Int,
)

object WrapRowLines {

    /**
     * Break [sizes] into lines no wider than [maxWidth], separated by [spacing].
     *
     * CSS breaks BEFORE the item that would overflow, and never breaks a line
     * that has only one item on it — an item wider than the container overflows
     * rather than disappearing. Both fall out of starting each line with its
     * first child unconditionally.
     */
    fun lines(sizes: List<IntSize>, maxWidth: Int, spacing: Int): List<WrapLine> {
        val out = mutableListOf<WrapLine>()
        var items = mutableListOf<Int>()
        var width = 0
        var height = 0

        for (index in sizes.indices) {
            val size = sizes[index]
            if (items.isEmpty()) {
                items = mutableListOf(index)
                width = size.width
                height = size.height
                continue
            }
            val widened = width + spacing + size.width
            if (widened > maxWidth) {
                out.add(WrapLine(items.toList(), width, height))
                items = mutableListOf(index)
                width = size.width
                height = size.height
            } else {
                items.add(index)
                width = widened
                height = maxOf(height, size.height)
            }
        }
        if (items.isNotEmpty()) out.add(WrapLine(items.toList(), width, height))
        return out
    }
}

/**
 * The wrapping, centred row.
 *
 * [spacing] is the CSS `gap` along the main axis and [lineSpacing] the same gap
 * between wrapped lines — CSS `gap` is both, so [lineSpacing] defaults to
 * [spacing] and no call site has to say it twice.
 */
@Composable
fun WrapRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    lineSpacing: Dp = spacing,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        // Unbounded: `flex-wrap` never shrinks a child to make it fit, it moves
        // it down. Measuring against the incoming maximum would reintroduce the
        // squashing this layout exists to avoid.
        val placeables = measurables.map { it.measure(Constraints()) }
        val gap = spacing.roundToPx()
        val lineGap = lineSpacing.roundToPx()
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE

        val rows = WrapRowLines.lines(placeables.map { IntSize(it.width, it.height) }, limit, gap)
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else rows.maxOfOrNull { it.width } ?: 0
        val height =
            rows.sumOf { it.height } + maxOf(0, rows.size - 1) * lineGap

        layout(width, height) {
            var y = 0
            for (row in rows) {
                // justify-center
                var x = (width - row.width) / 2
                for (index in row.items) {
                    val placeable = placeables[index]
                    // items-center
                    placeable.place(x, y + (row.height - placeable.height) / 2)
                    x += placeable.width + gap
                }
                y += row.height + lineGap
            }
        }
    }
}
