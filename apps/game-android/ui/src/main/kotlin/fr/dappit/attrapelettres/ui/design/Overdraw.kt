package fr.dappit.attrapelettres.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset

// ===========================================================================
// HEADROOM FOR A SHADOW THAT PAINTS OUTSIDE THE BORDER BOX.
//
// CSS lets a `box-shadow` paint anywhere: a 6 px spread or an 8 px offset lands
// outside the element and nothing crops it, because a shadow takes part in no
// layout. The web tile leans on that twice — the green highlight ring
// (`0 0 0 6px #66BB6A`) and the hard lip (`0 8px 0 …`).
//
// Compose has no such freedom. `Modifier.graphicsLayer` — and therefore
// `Modifier.alpha`, `Modifier.shadow`, and anything animating a scale — RECORDS
// its subtree into a layer the size of its node, and everything painted outside
// those bounds is simply not in the recording. The `clip` flag does NOT govern
// this: `clip` chooses whether the layer's OUTLINE (the rounded corners) crops
// the content, while the recording bounds always do. `clip = false` and a
// `drawBehind` that paints 6 dp out therefore looks correct in review and
// silently loses the ring on a device.
//
// Measured, not assumed. With a magenta disc of radius 30 dp drawn at the
// tile's own (0, 0), all that survived on a Pixel 7 emulator was a sliver in
// the corner — the sole part of it inside both the node rect and outside the
// face's radius-28 arc. Moving the `drawBehind` out from under `Modifier.shadow`
// changed nothing, which ruled the shadow out and left the press-animation and
// opacity layers as the crop.
//
// So the layer needs to be bigger than the tile while the LAYOUT stays exactly
// what the web computes — neighbouring tiles must not move apart by 12 dp
// because a ring might appear on one of them. This modifier is that: it
// measures its subtree [all] larger on every side, then reports the original
// size and places the subtree back at the negative offset. Everything to its
// right — layers included — is [all] bigger and can paint into the margin;
// everything to its left, including the pointer-input node that owns invariant
// 1, sees the tile's true size and an unchanged hit rect.
// ===========================================================================

@Stable
fun Modifier.overdraw(all: Dp): Modifier = this.layout { measurable, constraints ->
    val margin = all.roundToPx()
    val extra = margin * 2
    // `offset` widens the constraints rather than replacing them, so a tile that
    // was already at its parent's max width still gets its margin.
    val placeable = measurable.measure(constraints.offset(extra, extra))
    val width = (placeable.width - extra).coerceAtLeast(0)
    val height = (placeable.height - extra).coerceAtLeast(0)
    layout(width, height) { placeable.place(-margin, -margin) }
}
