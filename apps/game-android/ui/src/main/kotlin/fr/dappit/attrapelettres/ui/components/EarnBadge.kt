package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.PopMotion
import fr.dappit.attrapelettres.ui.interaction.popFlourish
import fr.dappit.attrapelettres.ui.interaction.rememberPopFlourish

// Port of `src/components/EarnBadge.tsx` — the « +N etoile » pill on a Finished
// screen (iOS: Sources/ALUI/Components/EarnBadge.swift).
//
//   <div
//     ref={usePopFlourish()}
//     aria-label={`Tu gagnes ${earned} étoiles`}
//     className="flex items-center gap-2 rounded-full font-black text-[#4A3B00]"
//     style={{
//       background: "linear-gradient(180deg,#FFDE6B 0%,#FFC107 100%)",
//       padding: "clamp(8px,2.4vw,14px) clamp(18px,5vw,30px)",
//       fontSize: "clamp(28px,8vw,46px)",
//       boxShadow: "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)",
//     }}
//   >
//     <span>+{earned}</span>
//     <span aria-hidden>⭐</span>
//   </div>
//
// INVARIANT 8 — this component DISPLAYS. `earned` is whatever
// `core.rewards.sessionReward` returned; there is no arithmetic in this file, no
// rounding, no clamping, no `coerceAtLeast(0)`. Read the value, show the value.
// The one transformation applied to it is string interpolation, and it lives in
// `Copy.EarnBadge.amount` where a test can hold it still. (`Finished` decides
// whether the pill appears at all — `earned > 0` — which is a visibility rule,
// not a change to the number.)
//
// INVARIANT 6 — the pop flourish is gated: `rememberPopFlourish` routes through
// `MotionSurface.POP`, and under reduced motion nothing animates while the pill
// still appears at full size.

object EarnBadgeMetrics {
    /** `clamp(8px, 2.4vw, 14px)` — the pill's vertical padding. */
    val PADDING_Y = FluidSpec(8f, 2.4f, 14f)

    /** `clamp(18px, 5vw, 30px)` — its horizontal padding. */
    val PADDING_X = FluidSpec(18f, 5f, 30f)

    /** `clamp(28px, 8vw, 46px)` — the digits. */
    val FONT_SIZE = FluidSpec(28f, 8f, 46f)

    /** `gap-2`. */
    val GAP: Dp = 8.dp

    /** `0 8px 0 #E0A800` — the hard lip's drop. */
    val LIP_DROP: Dp = 8.dp

    /** `0 16px 26px rgba(0,0,0,0.2)` — the soft shadow. */
    val SOFT_SHADOW = CssShadow(y = 16.dp, blur = 26.dp, opacity = 0.2f)
}

@Composable
fun EarnBadge(
    earned: Int,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    // Keyed on the value: a screen that shows a second reward pops again, and a
    // recomposition that changes nothing does not.
    val pop: PopMotion = rememberPopFlourish(reduceMotion, key = earned)
    val style = Typography.style(
        size = EarnBadgeMetrics.FONT_SIZE.resolve(viewport),
        weight = Typography.Weight.black,
        color = Palette.goldInk.color,
        fontScale = fontScale,
    )

    Row(
        modifier = modifier
            .popFlourish(pop)
            .liftedPill(
                fill = { size -> Palette.goldPill.brush(size) },
                lip = Palette.goldLip.color,
                drop = EarnBadgeMetrics.LIP_DROP,
                soft = EarnBadgeMetrics.SOFT_SHADOW,
            )
            .padding(
                horizontal = EarnBadgeMetrics.PADDING_X.resolve(viewport),
                vertical = EarnBadgeMetrics.PADDING_Y.resolve(viewport),
            )
            // The whole pill is ONE label, as the `aria-label` makes it: the
            // star is `aria-hidden` and « +7 ⭐ » read out piecemeal is noise.
            .clearAndSetSemantics { contentDescription = Copy.EarnBadge.label(earned) },
        horizontalArrangement = Arrangement.spacedBy(EarnBadgeMetrics.GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = Copy.EarnBadge.amount(earned), style = style)
        BasicText(text = Copy.EarnBadge.STAR, style = style)
    }
}

// --- The lifted pill, shared with « Suivant » --------------------------------

/**
 * `box-shadow: 0 Npx 0 <lip>, 0 Ypx Bpx rgba(0,0,0,a)` on a `rounded-full` box.
 *
 * The order is the CSS one and it is load-bearing: a box-shadow list paints
 * LAST-first, so the soft blur sits below the hard lip, which sits below the
 * box. Drawing the lip as a real offset capsule (rather than a second blurred
 * layer) is what makes it a flat, un-blurred colour — the same construction
 * `Tile.kt` uses for the tile's lip, and the same one iOS's `liftedCapsule`
 * uses. The blurred layer goes through the shared [cssShadow], which clips the
 * border box out of it exactly as CSS does.
 *
 * [fill] is a function of the box size because the gold pill is a GRADIENT and
 * `Brush.linearGradient` takes absolute offsets — the size is only known inside
 * the draw scope.
 */
fun Modifier.liftedPill(
    fill: (Size) -> Brush,
    lip: Color,
    drop: Dp,
    soft: CssShadow,
): Modifier = this
    .cssShadow(soft, CircleShape)
    .drawBehind {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = lip, topLeft = Offset(0f, drop.toPx()), size = size, cornerRadius = radius)
        drawRoundRect(brush = fill(size), size = size, cornerRadius = radius)
    }
