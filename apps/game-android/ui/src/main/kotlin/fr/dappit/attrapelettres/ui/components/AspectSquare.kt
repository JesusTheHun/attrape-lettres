package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Tailwind `aspect-square`, and the trap it is NOT — D52.
//
// The iOS port spent a phase on this: SwiftUI's `.aspectRatio(1, contentMode:
// .fit)` means "shrink me until I fit inside the proposal", so a 24 pt digit in
// a 60 pt column resolved to a 28.67 pt square and every hub level button fell
// under the tap-target floor (invariant 6). It was reported as three separate
// visual bugs and was one line.
//
// Compose does not have that trap, and saying so is the point of this file.
// `Modifier.aspectRatio(1f)` with `matchHeightConstraintsFirst = false` derives
// the HEIGHT FROM THE WIDTH whenever the incoming width is bounded — which is
// exactly what CSS `aspect-ratio: 1 / 1` does, and what a `LazyVerticalGrid`
// cell or a `Row`'s `weight(1f)` always hands down. The content rides on top and
// never drives the box.
//
// What Android has INSTEAD, and what the tests here pin, is the floor itself:
// Material's minimum touch target is 48 dp, four more than Apple's 44. A square
// cell whose side is its column width therefore has a hard minimum column width
// below which invariant 6 is broken, and [AspectSquareMetrics] is where that
// number lives so a grid author can assert against it rather than eyeball it.

object AspectSquareMetrics {

    /**
     * Android's minimum touch target, 48 dp.
     *
     * Material's `MinimumInteractiveComponentSize`; Apple's equivalent floor is
     * 44 pt, which is why the iOS suite asserts 44 and this one asserts 48. The
     * app's own tiles are far larger (92 dp, `Copy.Tile.MINIMUM_SIDE`) — this is
     * the floor for the SMALL square cells, i.e. the hub's level buttons.
     */
    val tapTargetFloor: Dp = 48.dp

    /** A square cell's side is the width it is offered. CSS, in one line. */
    fun side(width: Dp): Dp = width

    /** Whether a column of [width] yields a square cell that clears the floor. */
    fun clearsTapTargetFloor(width: Dp): Boolean = side(width) >= tapTargetFloor

    /**
     * The column width a grid of [columns] columns and [gap] gutters gets out of
     * a [row] of available width — the arithmetic every square-cell grid does,
     * hoisted so a test can drive it at a device width instead of a screenshot.
     */
    fun columnWidth(row: Dp, columns: Int, gap: Dp): Dp {
        if (columns <= 0) return 0.dp
        return (row - gap * (columns - 1)) / columns
    }
}

/**
 * Tailwind `aspect-square`: height follows width.
 *
 * `matchHeightConstraintsFirst = false` is the default and is written out
 * because it is the whole behaviour — flipping it makes the box take its side
 * from the HEIGHT, which is the CSS-inverted spelling and the one that produced
 * the iOS defect.
 */
fun Modifier.aspectSquare(): Modifier = this.aspectRatio(1f, matchHeightConstraintsFirst = false)

/** A square box with [content] centred in it. */
@Composable
fun AspectSquareBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.aspectSquare(), contentAlignment = Alignment.Center) { content() }
}
