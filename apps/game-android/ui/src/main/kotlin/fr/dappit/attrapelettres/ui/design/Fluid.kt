package fr.dappit.attrapelettres.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The CSS clamp() analogue (iOS D16, Sources/ALUI/Design/Fluid.swift).
//
// The PWA sizes nearly everything with `clamp(min, N vw, max)`: the hub title,
// every tile and its glyph, the badge paddings, the mascot pedestal, the big
// emoji. `vw` is 1 % of the VIEWPORT (window) width and -- this is the part a
// naive port gets wrong -- NOT 1 % of the card. `index.css` caps `#root > *` at
// 480 px while `vw` keeps growing, so on a 1024 dp tablet `8vw` is 82 dp even
// though the card is 480 dp wide. Measuring the container with a
// `BoxWithConstraints` would yield 38 dp: visibly wrong, and silently plausible.
//
// Hence one function, one definition of "viewport", published once at the app
// root from the window size through `LocalViewportWidth` and read from the
// composition everywhere else.
//
// Everything below is arithmetic over plain numbers, which is why a host test
// can pin it. CSS px == Android dp: both are 1/160 in at the reference density,
// so the authored numbers travel unchanged.

// CSS `clamp(min, vw * viewport / 100, max)`, in raw CSS px / Android dp.
//
// Semantics are the CSS ones exactly: `clamp(MIN, VAL, MAX)` is defined as
// `max(MIN, min(VAL, MAX))`, which means MIN WINS when the authored minimum
// exceeds the authored maximum. (Writing it the other way round --
// `min(max(MIN, VAL), MAX)` -- agrees on every clamp this app authors, because
// they all have `min < max`, but it would disagree on a future inverted pair and
// there is no reason to carry a second definition.)
//
// A zero or negative viewport therefore yields the authored minimum, which is
// what a composition measures for one frame before layout has a size.
fun fluid(min: Float, vw: Float, max: Float, viewport: Float): Float {
    val preferred = viewport * vw / 100f
    return kotlin.math.max(min, kotlin.math.min(preferred, max))
}

/** The same clamp in `Dp`, which is what a `Modifier.size` / `padding` wants. */
fun fluid(min: Dp, vw: Float, max: Dp, viewport: Dp): Dp =
    fluid(min.value, vw, max.value, viewport.value).dp

// CSS `clamp(min, N%, max)` resolved against a CONTAINER, not the viewport.
//
// One clamp in the app uses a percentage instead of `vw`: the Dashboard's mascot
// pedestal, `clamp(190px, 62%, 300px)`, whose `62%` is 62 % of the card's own
// width. It is deliberately a different function with a different name --
// folding it into `fluid` would invite a call site to pass a container width
// where a viewport width is meant, which is the exact bug D16 exists to prevent.
fun fluidPercent(min: Float, percent: Float, max: Float, container: Float): Float {
    val preferred = container * percent / 100f
    return kotlin.math.max(min, kotlin.math.min(preferred, max))
}

/** `fluidPercent` in `Dp`. */
fun fluidPercent(min: Dp, percent: Float, max: Dp, container: Dp): Dp =
    fluidPercent(min.value, percent, max.value, container.value).dp

/**
 * An authored `clamp(min, N vw, max)` triple, so a call site can name one and
 * resolve it later. Pure value type; [resolve] is the same arithmetic as [fluid].
 */
data class FluidSpec(val min: Float, val vw: Float, val max: Float) {

    fun resolve(viewport: Float): Float = fluid(min, vw, max, viewport)

    fun resolve(viewport: Dp): Dp = fluid(min, vw, max, viewport.value).dp

    /**
     * The resolved size as type, at a FIXED physical size: dividing by the
     * user's font scale cancels the scaling Compose would otherwise apply, so a
     * glyph stays inside the tile whose side came from the same clamp. See the
     * note on `Typography.fixedSp` -- the web scales neither, and a tile that
     * overflows at a 2x font scale is a worse accessibility outcome than a tile
     * that stays legible at 92 dp.
     */
    fun resolveSp(viewport: Dp, fontScale: Float): TextUnit =
        fixedSp(resolve(viewport.value), fontScale)

    fun resolveSp(viewport: Dp, density: Density): TextUnit =
        resolveSp(viewport, density.fontScale)
}

/**
 * CSS px -> a `TextUnit` that does not move with the system font scale.
 *
 * Identical to `with(density) { px.dp.toSp() }`, written out so a host test can
 * call it without a `Density` and so the reason survives the next refactor.
 */
fun fixedSp(px: Float, fontScale: Float): TextUnit =
    (if (fontScale <= 0f) px else px / fontScale).sp

// The viewport, published once ---------------------------------------------

/**
 * The window width in dp -- the `vw` basis for every [fluid] call.
 *
 * Set exactly once, at the app root, from the window size. Never set it from a
 * `BoxWithConstraints` around the card: the card is capped at 480 dp and `vw` is
 * not. `static` because it changes only on a configuration change, and a
 * non-static local would invalidate every reader on every recomposition of the
 * root -- which is invariant 2's neighbourhood.
 *
 * The default, 390 dp, is a Pixel-class portrait width. It is only ever seen by
 * a preview or a test, never by the running app.
 */
val LocalViewportWidth = staticCompositionLocalOf { 390.dp }

// The card shell -- index.css `#root` + `#root > *`, ported once --------------

object Shell {
    /** `#root > * { max-width: 480px }`. */
    val cardMaxWidth: Dp = 480.dp

    /** `#root { padding: max(16px, env(safe-area-inset-*)) }` -- the floor. */
    val minimumInset: Dp = 16.dp

    /** `min-h-[620px]` on every screen root. */
    val minimumScreenHeight: Dp = 620.dp

    /**
     * The card width for a given window: capped at 480 dp, minus the 16 dp
     * gutter on each side when the window is narrower than that.
     */
    fun cardWidth(viewport: Dp): Dp = minOf(cardMaxWidth, viewport - minimumInset * 2)

    fun cardWidth(viewport: Float): Float =
        kotlin.math.min(cardMaxWidth.value, viewport - minimumInset.value * 2)
}
