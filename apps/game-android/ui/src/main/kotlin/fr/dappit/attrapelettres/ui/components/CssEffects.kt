package fr.dappit.attrapelettres.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The two CSS effects the web authors that Compose has no modifier for:
// `box-shadow` and `filter: grayscale(1)`. One implementation each, drawn at
// DRAW time, so nothing here ever recomposes or re-lays-out (invariant 2).
//
// iOS keeps the same pair split across `CSSShadow` (declared in EarnBadge.swift)
// and SwiftUI's built-in `.saturation(0)`. Android has neither for free.
//
// BOX-SHADOW, and why it is drawn by hand.
//
// The obvious spelling is `Modifier.shadow(elevation, shape)`, which asks the
// platform to cast the shape's outline shadow through the RenderNode. It is
// wrong here for one specific reason: every shadowed surface in this app is
// TRANSLUCENT (`bg-white/70` on the pill, on the back button, on the trial
// chip). The framework paints an elevation shadow underneath the whole outline
// and lets a non-opaque layer show it through, so a 10 %-black shadow greys the
// 70 %-white capsule from the inside. CSS does the opposite by rule: an outer
// `box-shadow` is clipped so that it is never painted inside the border box —
// which is why the web's pill is clean cream over the wash and not a grey lozenge.
//
// So [cssShadow] draws the shadow itself and clips the border box OUT of it
// (`ClipOp.Difference`). That reproduces the CSS painting rule exactly, keeps the
// shadow cast by the BOX (the shape passed in), and never by the glyphs inside
// it — which is the D54 defect the iOS port hit and had to fix with
// `.compositingGroup()` at twenty call sites. There is no Compose twin of that
// defect: a shadow here is a shape and a blur, not a per-primitive effect.
//
// KNOWN LIMIT, recorded rather than fudged: `BlurMaskFilter` is only guaranteed
// on a hardware-accelerated canvas from API 28. `minSdk` is 26, so on API 26/27
// the two Tailwind shadows may render hard-edged instead of soft. The failure
// mode is a marginally crisper 10 % shadow, not a crash and not a missing
// shadow, and both call sites are 1 dp offsets that read as a hairline either
// way. Verifying it needs a device.

/**
 * One CSS `box-shadow` layer, in the units CSS authors it in.
 *
 * `0 <y>px <blur>px <spread>px rgba(0,0,0,<opacity>)`. Kept as data so a test
 * can assert the authored numbers rather than a converted radius.
 */
data class CssShadow(
    val y: Dp,
    val blur: Dp,
    val opacity: Float,
    val spread: Dp = 0.dp,
) {
    /**
     * The Gaussian radius for this CSS blur length.
     *
     * CSS defines the blur as twice the standard deviation of the Gaussian, so
     * the radius is half the authored length. Same halving iOS applies for
     * SwiftUI's `.shadow(radius:)`, and the reason the two ports look alike.
     */
    val blurRadius: Dp get() = blur / 2f
}

/** The Tailwind shadow utilities the app actually uses, as authored. */
object Shadows {

    /** Tailwind `shadow`, layer 1: `0 1px 3px rgba(0,0,0,0.1)`. */
    val soft = CssShadow(y = 1.dp, blur = 3.dp, opacity = 0.1f)

    /** Tailwind `shadow`, layer 2: `0 1px 2px -1px rgba(0,0,0,0.1)`. */
    val tight = CssShadow(y = 1.dp, blur = 2.dp, opacity = 0.1f, spread = (-1).dp)

    /**
     * The plain `shadow` utility, both layers, in CSS source order.
     *
     * A `box-shadow` list paints LAST-first, so the tight layer sits above the
     * soft one. [cssShadow] reverses the list for exactly that reason; keep the
     * order here the CSS order so the constant can be read against the class.
     */
    val tailwind: List<CssShadow> = listOf(soft, tight)
}

/**
 * A CSS `box-shadow` list cast by [shape], painted behind this element.
 *
 * The border box is clipped out, so a translucent fill never reveals the shadow
 * beneath it — the CSS painting rule, and the whole reason this is not
 * `Modifier.shadow`. Apply it BEFORE the background in the chain, so the shadow
 * is drawn first and the fill lands on top of it.
 */
fun Modifier.cssShadow(
    shadows: List<CssShadow>,
    shape: Shape,
    color: Color = Color.Black,
): Modifier = this.drawBehind {
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val boxPath = Path()
    boxPath.addOutline(shape.createOutline(size, layoutDirection, this))

    // Reversed: the CSS list paints last-first, so the earliest-authored layer
    // ends up on top.
    for (shadow in shadows.asReversed()) {
        if (shadow.opacity <= 0f) continue
        val spreadPx = shadow.spread.toPx()
        val grown = Size(size.width + 2f * spreadPx, size.height + 2f * spreadPx)
        if (grown.width <= 0f || grown.height <= 0f) continue

        val shadowPath = if (spreadPx == 0f) {
            boxPath
        } else {
            Path().also { it.addOutline(shape.createOutline(grown, layoutDirection, this)) }
        }

        val paint = Paint()
        paint.color = color.copy(alpha = color.alpha * shadow.opacity)
        val radiusPx = shadow.blurRadius.toPx()
        if (radiusPx > 0f) {
            paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
        }

        drawIntoCanvas { canvas ->
            canvas.save()
            // Clip in the element's own space, BEFORE the offset: the hole is
            // the border box, the shadow is what moves.
            canvas.clipPath(boxPath, ClipOp.Difference)
            canvas.translate(-spreadPx, shadow.y.toPx() - spreadPx)
            canvas.drawPath(shadowPath, paint)
            canvas.restore()
        }
    }
}

/** [cssShadow] for a single layer. */
fun Modifier.cssShadow(
    shadow: CssShadow,
    shape: Shape,
    color: Color = Color.Black,
): Modifier = cssShadow(listOf(shadow), shape, color)

/**
 * CSS `filter: grayscale(amount)` over this element and everything inside it.
 *
 * The star strip's LOST cell is the reason this exists (`LOST = { filter:
 * grayscale(1), opacity: 0.45 }`): a greyed star is still a star, so the round
 * still reads as played (invariants 3 and 8). There is no Compose modifier for
 * it and `ColorFilter` only applies to images, so the content is drawn into a
 * layer with a saturation matrix on it — the same thing the CSS filter does,
 * once, at draw time.
 */
fun Modifier.grayscale(amount: Float = 1f): Modifier = this.drawWithContent {
    val paint = Paint()
    paint.colorFilter = ColorFilter.colorMatrix(
        ColorMatrix().apply { setToSaturation((1f - amount).coerceIn(0f, 1f)) },
    )
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}
