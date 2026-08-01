package fr.dappit.attrapelettres.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.persistence.ProfileView
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.liftedPill
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.opacity

// ---------------------------------------------------------------------------
// `src/shop/GrowthCard.tsx` — growth upgrade, the headline spend.
//
// Grows the mascot one stage at a time. The price rises with maturity so
// growing stays a meaningful goal.
//
// INVARIANT 3 — no fail state. Too poor = a disabled button and « pas encore »;
// at the top = « Niveau max ✨ ». Nothing is locked, nothing is lost, and there
// is no error path: `spend` refusing is a quiet no-op.
//
// INVARIANT 8 — nothing here mints a point. `sessionReward` in :core is the only
// earner; this card only ever SPENDS, through `ProfileStore.spend`, which
// refuses an unaffordable price. The stage bump goes through `setConfig`.
//
// INVARIANT 9 — both of those are the same choke points every other mutation
// uses, so the spend lands on a counter and merges like everything else. This
// file holds no total of its own and writes nothing to storage.
// ---------------------------------------------------------------------------

/** `growthPrice(stage)` — the local price curve, rising with the current stage. */
fun growthPrice(stage: Int): Int = 30 * (stage + 1)

// --- The card's state (pure, host-tested) ------------------------------------

/** Everything the card shows for a (stage, balance) pair. */
data class GrowthCardSurface(
    val stage: Int,
    val price: Int,
    /** `config.stage >= GROWTH_STAGES - 1`. */
    val atMax: Boolean,
    val affordable: Boolean,
    /** `atMax || !affordable` — the button's disabled flag. */
    val disabled: Boolean,
    /** `Math.min(config.stage + 1, GROWTH_STAGES - 1)` — the peek preview. */
    val nextStage: Int,
    /** « 3/10 » beside the title. */
    val counter: String,
    /** `aria-label` on the pip row. */
    val meterLabel: String,
    /** Pips `i <= stage` fill green. */
    val filledPips: Int,
    /** The button's `aria-label`. */
    val accessibilityLabel: String,
    /** The button's text. */
    val buttonLabel: String,
    /** `!atMax && !affordable` — the fattest savings meter of all. */
    val showsMeter: Boolean,
)

/**
 * Fold a (stage, balance) pair into the card.
 *
 * THE `coerceIn` IS THE ONE THING THIS FUNCTION DOES THAT THE TSX DOES NOT, and
 * it is invariant 8's business rather than tidiness. [stage] arrives from
 * `MascotConfig.stage`, which is read off disk by the loose decoder
 * (`stage = src.stage?.looseCount`) with no range check — the same latitude the
 * web's `JSON.parse(localStorage…)` has. Nothing in the app ever writes a
 * negative stage, but a stage of −4 read back off disk would make
 * [growthPrice] −90, `affordable` trivially true, and `ProfileStore.spend(−90)`
 * a *credit* of 90 stars: the `spent` counter bumps by a negative and the
 * folded balance goes UP. `spend` does not reject a negative cost (inherited
 * verbatim from `useProfile.tsx`), and the only thing that kept that
 * unreachable was the claim that every price handed to it comes from an
 * authored table. [growthPrice] is computed, not authored, so the claim is made
 * true here instead — at the one place the computed price is born.
 *
 * Clamping at the top rather than in [performGrow] on purpose: a negative stage
 * would otherwise still render « Grandir · ⭐ -90 » and a pip row of −3, and a
 * surface that cannot be bought must also not be *shown*.
 *
 * :core has the same defence in `stageScale`'s `coerceIn(0, 9)`, for the same
 * reason and against the same input.
 */
fun growthCardSurface(stage: Int, balance: Int): GrowthCardSurface {
    @Suppress("NAME_SHADOWING") val stage = stage.coerceIn(0, GROWTH_STAGES - 1)
    val atMax = stage >= GROWTH_STAGES - 1
    val price = growthPrice(stage)
    val affordable = balance >= price
    return GrowthCardSurface(
        stage = stage,
        price = price,
        atMax = atMax,
        affordable = affordable,
        disabled = atMax || !affordable,
        nextStage = minOf(stage + 1, GROWTH_STAGES - 1),
        counter = "${stage + 1}/$GROWTH_STAGES",
        meterLabel = Copy.Shop.Growth.meter(stage + 1, GROWTH_STAGES),
        filledPips = stage + 1,
        accessibilityLabel = when {
            atMax -> Copy.Shop.Growth.AT_MAX
            affordable -> Copy.Shop.Growth.grow(price)
            else -> Copy.Shop.Growth.cannotAfford(price)
        },
        buttonLabel = when {
            atMax -> Copy.Shop.Growth.AT_MAX_LABEL
            affordable -> Copy.Shop.Growth.growLabel(price)
            else -> Copy.Shop.Growth.notYetLabel(price)
        },
        showsMeter = !atMax && !affordable,
    )
}

// --- Metrics (the Tailwind classes, verbatim) --------------------------------

object GrowthCardMetrics {
    /** `gap-4 rounded-3xl p-4`. */
    val SPACING: Dp = 16.dp
    val CORNER_RADIUS: Dp = 24.dp
    val PADDING: Dp = 16.dp

    /** `0 8px 18px rgba(0,0,0,0.08)`. */
    val SHADOW = CssShadow(y = 8.dp, blur = 18.dp, opacity = 0.08f)

    /** The peek: `rounded-2xl bg-white/70 p-2`, `<Mascot size={64} />`. */
    val PEEK_RADIUS: Dp = 16.dp
    val PEEK_PADDING: Dp = 8.dp
    val PEEK_MASCOT_SIZE: Dp = 64.dp

    /** The column: `flex-1 flex-col gap-2`. */
    val COLUMN_SPACING: Dp = 8.dp

    /** Pips: `flex gap-1`, each `h-2 flex-1 rounded-full`; empty at ink 15 %. */
    val PIP_SPACING: Dp = 4.dp
    val PIP_HEIGHT: Dp = 8.dp
    const val PIP_EMPTY_OPACITY: Float = 0.15f

    /** The button: `rounded-full px-5 py-3 text-base font-extrabold`. */
    val BUTTON_PADDING_X: Dp = 20.dp
    val BUTTON_PADDING_Y: Dp = 12.dp

    /** `opacity: disabled && !atMax ? 0.6 : 1`. */
    const val UNAFFORDABLE_OPACITY: Float = 0.6f

    /** `0 6px 0 rgba(0,0,0,0.12)` — the hard lip, only while enabled. */
    val BUTTON_LIP_DROP: Dp = 6.dp
    const val BUTTON_LIP_OPACITY: Float = 0.12f

    /** The savings meter under it: `height={12}`. */
    val METER_HEIGHT: Dp = 12.dp
}

// --- The grow action ----------------------------------------------------------

/**
 * `grow()` — the ONLY way a stage is bought, ported line for line:
 *
 *     if (atMax) return;
 *     if (spend(price)) { setConfig(stage + 1); onGrew(price) }
 *
 * Returns the price actually spent, or `null` when nothing happened (at max, or
 * the wallet is short — `spend` refuses, and refusal is a quiet no-op, never an
 * error: invariant 3).
 *
 * Non-`suspend`, like every `ProfileStore` mutation, so it can be called from
 * inside a pointer handler (A3).
 */
fun performGrow(store: ProfileStore): Int? {
    val profile = store.profile
    val surface = growthCardSurface(profile.config.stage, profile.balance)
    if (surface.atMax) return null
    if (!store.spend(surface.price)) return null
    // `setConfig(stage + 1)`, re-derived from the profile the closure is handed
    // (the same discipline `ProfileStore.buy` uses) and through the SAME clamp
    // that produced the price. Reading a bare `c.stage + 1` would let an
    // out-of-range value read off disk survive a purchase — see
    // [growthCardSurface] for why that value can exist at all.
    store.setConfig { c ->
        c.copy(stage = (c.stage.coerceIn(0, GROWTH_STAGES - 1) + 1).coerceAtMost(GROWTH_STAGES - 1))
    }
    return surface.price
}

// --- The view ------------------------------------------------------------------

/**
 * `<GrowthCard sinceBalance onGrew />`.
 *
 * The parent (the shop) supplies the profile it already reads and receives
 * [onGrow] to run the star flight plus the burst; the spend itself goes through
 * [performGrow].
 *
 * @param sinceBalance wallet at the previous shop visit — the savings meter
 *   animates from it.
 */
@Composable
fun GrowthCardView(
    profile: ProfileView,
    sinceBalance: Int,
    reduceMotion: ReduceMotionSource,
    onGrow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = growthCardSurface(profile.config.stage, profile.balance)
    val shape = RoundedCornerShape(GrowthCardMetrics.CORNER_RADIUS)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .cssShadow(GrowthCardMetrics.SHADOW, shape)
            // `rounded-3xl` + `linear-gradient(135deg,#E9F9E0,#D6F0FB)`. Drawn
            // as a round rect rather than clipped-then-filled: a `clip` would
            // add a layer for a wash that already knows its own corners.
            .drawBehind {
                drawRoundRect(
                    brush = Palette.growthCard.brush(size),
                    cornerRadius = CornerRadius(GrowthCardMetrics.CORNER_RADIUS.toPx()),
                )
            }
            .padding(GrowthCardMetrics.PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GrowthCardMetrics.SPACING),
    ) {
        Peek(surface.nextStage, profile.config, reduceMotion)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(GrowthCardMetrics.COLUMN_SPACING),
        ) {
            TitleRow(surface)
            Pips(surface)
            GrowButton(surface, reduceMotion, onGrow)

            // Growth is the longest save, so it gets the fattest meter of all.
            if (surface.showsMeter) {
                SavingsMeter(
                    cost = surface.price,
                    balance = profile.balance,
                    since = sinceBalance,
                    reduceMotion = reduceMotion,
                    height = GrowthCardMetrics.METER_HEIGHT,
                )
            }
        }
    }
}

/** « A peek at what the next stage looks like. » `aria-hidden` on the web. */
@Composable
private fun Peek(nextStage: Int, config: MascotConfig, reduceMotion: ReduceMotionSource) {
    Box(
        modifier = Modifier
            .background(
                Color.White.copy(alpha = Palette.White.o70),
                RoundedCornerShape(GrowthCardMetrics.PEEK_RADIUS),
            )
            .padding(GrowthCardMetrics.PEEK_PADDING)
            .clearAndSetSemantics { },
    ) {
        // `{ ...config, stage: nextStage }`.
        Ollie(
            config = config.copy(stage = nextStage),
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            size = GrowthCardMetrics.PEEK_MASCOT_SIZE,
        )
    }
}

@Composable
private fun TitleRow(surface: GrowthCardSurface) {
    val fontScale = LocalDensity.current.fontScale
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicText(
            text = Copy.Shop.Growth.TITLE,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )
        BasicText(
            text = surface.counter,
            style = Typography.style(
                size = Typography.Size.sm,
                weight = Typography.Weight.bold,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
        )
    }
}

/** The growth meter — `role="img"` plus its French label. */
@Composable
private fun Pips(surface: GrowthCardSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Image
                contentDescription = surface.meterLabel
            },
        horizontalArrangement = Arrangement.spacedBy(GrowthCardMetrics.PIP_SPACING),
    ) {
        for (i in 0 until GROWTH_STAGES) {
            Box(
                Modifier
                    .weight(1f)
                    .height(GrowthCardMetrics.PIP_HEIGHT)
                    .background(
                        if (i < surface.filledPips) {
                            Palette.green.color
                        } else {
                            Palette.ink.color.copy(alpha = GrowthCardMetrics.PIP_EMPTY_OPACITY)
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * « Grandir · ⭐ N » / « pas encore · ⭐ N » / « Niveau max ✨ ».
 *
 * The TSX splits the two halves of a tap: `onPointerDown={() => press(btn)}`
 * squishes, `onClick={grow}` spends. So does this — invariant 1 for the
 * feedback, touch-up for the money, which is the same bargain the shop tile and
 * the picker card strike.
 */
@Composable
private fun GrowButton(
    surface: GrowthCardSurface,
    reduceMotion: ReduceMotionSource,
    onGrow: () -> Unit,
) {
    val motion = rememberShopTileMotion(reduceMotion)
    val fontScale = LocalDensity.current.fontScale
    val face = if (surface.atMax) Palette.disabled.color else Palette.green.color

    BasicText(
        text = surface.buttonLabel,
        style = Typography.style(
            size = Typography.Size.base,
            weight = Typography.Weight.extrabold,
            color = Color.White,
            fontScale = fontScale,
        ),
        modifier = Modifier
            // `opacity: disabled && !atMax ? 0.6 : 1` — the maxed-out button
            // stays fully opaque and merely goes grey, so « Niveau max ✨ » reads
            // as an achievement rather than as something broken.
            .opacity(
                if (surface.disabled && !surface.atMax) {
                    GrowthCardMetrics.UNAFFORDABLE_OPACITY
                } else {
                    1f
                },
            )
            .shopMotion(motion)
            .then(
                // `boxShadow: disabled ? "none" : "0 6px 0 rgba(0,0,0,0.12)"`.
                if (surface.disabled) {
                    Modifier.background(face, CircleShape)
                } else {
                    Modifier.liftedPill(
                        fill = { SolidColor(face) },
                        lip = Color.Black.copy(alpha = GrowthCardMetrics.BUTTON_LIP_OPACITY),
                        drop = GrowthCardMetrics.BUTTON_LIP_DROP,
                        // No soft shadow on this one; a zero-opacity layer is
                        // skipped by `cssShadow`.
                        soft = CssShadow(y = 0.dp, blur = 0.dp, opacity = 0f),
                    )
                },
            )
            .shopTilePress(enabled = !surface.disabled, motion = motion, onTap = onGrow)
            .padding(
                horizontal = GrowthCardMetrics.BUTTON_PADDING_X,
                vertical = GrowthCardMetrics.BUTTON_PADDING_Y,
            )
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = surface.accessibilityLabel
                if (!surface.disabled) {
                    onClick {
                        onGrow()
                        true
                    }
                }
            },
    )
}
