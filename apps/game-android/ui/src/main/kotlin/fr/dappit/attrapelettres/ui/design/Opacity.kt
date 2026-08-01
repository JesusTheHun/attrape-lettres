package fr.dappit.attrapelettres.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// ===========================================================================
// THE PORT'S `opacity`. One line of code, and it exists because the obvious
// spelling is wrong.
//
// Every fade in this app comes from CSS: `disabled:opacity-40` on a tile,
// `opacity: 0.45` on a lost star, `opacity: 0.6` on an unaffordable shop
// button. CSS `opacity` composites the element and its overflow — a shadow, a
// ring, anything painted outside the border box comes along, faded, and is
// never cut.
//
// `Modifier.alpha` does NOT do that. Disassembled from ui-android 1.11.4:
//
//     0: fload_1  1: fconst_1  2: fcmpg  3: ifne 10     // alpha == 1f ?
//    11: ifne 43                                        // yes -> `this`, no layer
//    ...
//    27: iconst_1                                       // no  -> graphicsLayer(clip = TRUE)
//
// i.e. any alpha below 1 installs a layer that CLIPS TO BOUNDS. On a Tile that
// silently ate the highlight ring: the ring is a rounded rect inflated 6 dp on
// every side, so clipping it to the tile's own rect leaves nothing but four
// green slivers in the corners, between the square bound and the face's radius
// 28 arc. It looked like a corner-radius bug and was a clipping bug. Measured
// on a Pixel 7 emulator: with the tile at [232,991][416,1175], green pixels
// existed at dy=2 and dy=182 and NOWHERE on the centre line, which is the
// signature of a clip rather than of bad geometry.
//
// The block form of `graphicsLayer` leaves `clip` at its default false, so this
// composites like CSS does. The `>= 1f` short-circuit is kept from the original
// so a fully opaque node still costs no layer at all.
//
// USE THIS, NEVER `Modifier.alpha`. `OpacitySourceScanTest` enforces it, because
// nothing that runs on the host can see a clipped shadow.
// ===========================================================================

@Stable
fun Modifier.opacity(value: Float): Modifier =
    if (value >= 1f) this else this.graphicsLayer { alpha = value }
