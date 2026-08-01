package fr.dappit.attrapelettres.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.DefaultLook
import fr.dappit.attrapelettres.core.persistence.ProfileView
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.KeyframeSpec
import fr.dappit.attrapelettres.ui.interaction.MotionSurface
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ===========================================================================
// `src/shop/ShopItem.tsx` — one buyable / equippable tile — plus the factory-
// look tile (`DefaultTile` in `Shop.tsx`), which shares its whole visual
// scaffold. The twin of iOS's `Shop/ShopItem.swift`.
//
// Purely presentational: the parent computes every state flag and owns the
// buy()/setConfig() side-effect in `onTap`. State is coded for a PRE-READER —
// colour, shape and fill, never prose:
//   gold price tag   = you can buy this now
//   grey tag + meter = keep saving (the meter is the countdown, no arithmetic)
//   corner check     = yours (green-filled when worn; a cross means tap removes)
//   seedling chip    = your friend must grow first
// The art itself is NEVER greyed out — a grey drawing reads as "broken".
//
// Every flag and every string is extracted into [ShopItemSurface] /
// [DefaultLookSurface] so the host suite asserts them against the TSX without a
// renderer (A11: a @Composable body cannot be invoked by a JUnit test, so the
// decisions live in plain Kotlin and the composable is a thin renderer).
//
// INVARIANT 8, the other side of it: nothing in this file computes a price, a
// balance or a reward. It receives `cost` off the authored CATALOG row and
// prints it. `onTap` is the parent's — the spend happens in `ShopModel`, and
// the money itself moves in `:core`'s `ProfileStore`.
//
// INVARIANT 6: the tile floors at 96 dp (`min-h-[96px]`), past the 92 dp tile
// floor the exercises use and well past the 44 dp platform minimum; every tile
// carries ONE `contentDescription` naming the item and its state; and the two
// shop animations are reduce-motion gated at their source (`ShopAnim.kt`).
//
// A11Y NOTE — one label per tile. `clearAndSetSemantics` collapses the preview,
// the name and the price chip into the single sentence the `aria-label` is
// (« Queue bouclée, coûte 40 points »). Reading the pieces out separately is
// noise to a screen reader, and the price chip is `aria-hidden` in the TSX for
// exactly that reason.
// ===========================================================================

// --- The two shop animations, their gate, and the press wiring --------------

/**
 * THE shop's reduce-motion gate. `ShopAnim.kt` delegates to it; nothing in this
 * package decides it twice.
 *
 * `shop/anim.ts` checks `prefers-reduced-motion` ITSELF at the top of every one
 * of its exports (line 7, `reducedMotion()`), so every shop caller inherits the
 * gate. That is the opposite of `Tile.tsx`, whose press and shake are bare
 * `el.animate` calls — see D29 and the table in `interaction/Anim.kt`, where
 * [MotionSurface.PRESS] is documented as *the exercise tile's* squish and is
 * deliberately ungated.
 *
 * The shop's squish is therefore a different surface from the tile's, and it
 * routes through the module's one gate ([Anim.shouldAnimate]) under the row
 * carrying the same rule: [MotionSurface.POP], the web's other self-gating
 * flourish. No new enum entry — `MotionSurface` is shared with every screen in
 * the app, and one more row in it buys nothing this function does not already
 * give, in one place, with a test on it.
 */
fun shopMotionAllowed(reduceMotion: ReduceMotionSource): Boolean =
    Anim.shouldAnimate(MotionSurface.POP, reduceMotion)

/** The two keyframe tables of `shop/anim.ts` that are not celebrations. */
object ShopAnimSpecs {

    /**
     * `PRESS = [scale(1), scale(0.94), scale(1)]`,
     * `{ duration: 130, easing: "ease-out" }`.
     *
     * NB 0.94, not `Anim.PRESS`'s 0.90: two different authored animations. The
     * shop's tiles are bigger, and the deeper squish reads as a wobble on them.
     */
    val PRESS = KeyframeSpec(
        values = listOf(1f, 0.94f, 1f),
        keyTimes = listOf(0f, 0.5f, 1f),
        durationMillis = 130,
        easing = Anim.EASE_OUT,
    )

    /**
     * `POP = [scale(1), scale(1.12), scale(1)]`,
     * `{ duration: 260, easing: "ease-out" }` — « a happy little bounce, used on
     * the live preview when something is equipped ».
     *
     * NOT `Anim.POP_SCALE`: that is `usePopFlourish.ts`'s 480 ms overshoot from
     * 0.4, a different animation on a different element. Same gate, different
     * curve.
     */
    val POP = KeyframeSpec(
        values = listOf(1f, 1.12f, 1f),
        keyTimes = listOf(0f, 0.5f, 1f),
        durationMillis = 260,
        easing = Anim.EASE_OUT,
    )
}

/**
 * One shop surface's squish channel — a tile, the buy button, the growth
 * button, a picker card, the header mascot's equip bounce.
 *
 * Gated, unlike `TileMotion`: the gate is the whole difference between
 * `shop/anim.ts` and `Tile.tsx`, and it is why this class takes a
 * [ReduceMotionSource] where `TileMotion` deliberately cannot.
 *
 * INVARIANT 2 — the `.value` read happens INSIDE the [Modifier.graphicsLayer]
 * lambda ([Modifier.shopMotion]), which is a deferred read: the layer block is
 * re-run and the recorded layer re-drawn, and nothing recomposes, re-measures or
 * re-lays-out. Never hoist [scale] into state a composable body reads.
 *
 * Both entry points are plain non-suspend calls made synchronously from the
 * `touchDown` handler, exactly as `el.animate(...)` returns immediately on the
 * web.
 */
@Stable
class ShopTileMotion(
    private val scope: CoroutineScope,
    private val reduceMotion: ReduceMotionSource,
) {

    /** Unitless scale, 1 at rest. Read inside a `graphicsLayer` lambda only. */
    val scale: Animatable<Float, AnimationVector1D> = Animatable(1f)

    private var job: Job? = null

    /** True when the setting is on: nothing below will animate. */
    val gated: Boolean get() = !shopMotionAllowed(reduceMotion)

    /** `press(el)` — the tactile squish, at pointer-down. */
    fun press() = play(ShopAnimSpecs.PRESS)

    /** `pop(el)` — the equip bounce. */
    fun pop() = play(ShopAnimSpecs.POP)

    private fun play(spec: KeyframeSpec) {
        // A gated animation does not run AND does not park the element on a
        // keyframe: the web returns before `el.animate`, so it never moves.
        if (gated) return
        job?.cancel()
        job = scope.launch {
            scale.snapTo(spec.start)
            scale.animateTo(spec.target, spec.toAnimationSpec())
        }
    }
}

/** One [ShopTileMotion] per animated element, tied to the composition's scope. */
@Composable
fun rememberShopTileMotion(reduceMotion: ReduceMotionSource): ShopTileMotion {
    val scope = rememberCoroutineScope()
    return remember(scope, reduceMotion) { ShopTileMotion(scope, reduceMotion) }
}

/** Apply a shop squish/bounce. Deferred read — see the invariant 2 note above. */
fun Modifier.shopMotion(motion: ShopTileMotion): Modifier = this.graphicsLayer {
    val s = motion.scale.value
    scaleX = s
    scaleY = s
}

/**
 * `onPointerDown={() => press(ref.current)}` + `onClick={onTap}`.
 *
 * The squish fires at touch-DOWN (invariant 1, through the module's one pointer
 * entry point) and the action on touch-UP inside — the shop tile is one of the
 * only two surfaces in the app that act on the lift, because a try-on triggered
 * mid-scroll would open a dialog a child never asked for.
 *
 * [enabled] `= false` is the web's `disabled` attribute: a disabled button fires
 * neither `pointerdown` nor `click`, so a locked tile gets no touch surface at
 * all — it is still drawn, still labelled, still announced.
 */
@Composable
fun Modifier.shopTilePress(
    enabled: Boolean,
    motion: ShopTileMotion,
    onTap: () -> Unit,
): Modifier = this.touchDown(
    enabled = enabled,
    onUp = { inside -> if (inside) onTap() },
    onDown = { motion.press() },
)

// --- The buyable tile's state (pure, host-tested) ---------------------------

/** The corner "yours" sticker. */
sealed interface ShopSticker {

    /** Not owned — no sticker. */
    data object None : ShopSticker

    /**
     * The check: white chip with green ink (owned, not worn) or green chip with
     * white ink (worn colour/style).
     */
    data class Check(val filled: Boolean) : ShopSticker

    /** The cross — a worn accessory comes off on tap. Always green-filled. */
    data object Remove : ShopSticker
}

/**
 * The price panel under the name — "only until owned; a bought item never shows
 * a number".
 */
sealed interface ShopPricePanel {
    data object None : ShopPricePanel

    /** « seedling niv. N · ⭐ C ». */
    data class Locked(val badge: String) : ShopPricePanel

    /** « ⭐ C » on gold. */
    data class Affordable(val badge: String) : ShopPricePanel

    /** « ⭐ C » greyed, plus the savings meter. */
    data class Unaffordable(val badge: String) : ShopPricePanel
}

/**
 * Everything `renderItem` (Shop.tsx) computes plus everything `ShopItem.tsx`
 * derives from it, for one option against one profile.
 *
 * Built by the fake constructor below rather than by a body-full `init`, so the
 * shape stays a `data class` (free equality for the tests) while the derivation
 * reads top-to-bottom like the TSX it ports.
 */
data class ShopItemSurface(
    val owned: Boolean,
    val equipped: Boolean,
    val locked: Boolean,
    val affordable: Boolean,
    val trying: Boolean,
    /** `equipped && option.category === "accessory"` — tapping takes it off. */
    val removable: Boolean,
    /** The second half of « <nom>, <état> ». */
    val stateLabel: String,
    val contentDescription: String,
    val sticker: ShopSticker,
    val pricePanel: ShopPricePanel,
) {
    val showsMeter: Boolean get() = pricePanel is ShopPricePanel.Unaffordable
}

/**
 * The surface for (option, profile, cart) — a "fake constructor", so the call
 * site reads exactly like the Swift and the Kotlin stays a plain data class.
 *
 * @param cartId the id of the option currently being TRIED ON, or null.
 */
@Suppress("FunctionNaming")
fun ShopItemSurface(
    option: CustomizationOption,
    profile: ProfileView,
    cartId: String?,
): ShopItemSurface {
    val config = profile.config
    val owned = option.id in profile.owned
    val equipped = when (option.category) {
        CustomizationCategory.ACCESSORY -> option.id in config.accessories
        CustomizationCategory.COLOR -> config.colors[option.slot] == option.value
        CustomizationCategory.STYLE -> config.styles[option.slot] == option.value
    }
    val minStage = option.minStage ?: 0
    val locked = config.stage < minStage
    // `owned || balance >= cost` — an owned item is always "affordable", because
    // re-equipping it is free.
    val affordable = owned || profile.balance >= option.cost
    val trying = cartId == option.id
    val removable = equipped && option.category == CustomizationCategory.ACCESSORY

    val stateLabel = when {
        equipped ->
            if (removable) Copy.Shop.ItemState.EQUIPPED_REMOVABLE else Copy.Shop.ItemState.EQUIPPED

        owned -> Copy.Shop.ItemState.OWNED
        locked -> Copy.Shop.ItemState.lockedCost(option.cost, minStage + 1)
        trying -> Copy.Shop.ItemState.tryingCost(option.cost)
        affordable -> Copy.Shop.ItemState.cost(option.cost)
        else -> Copy.Shop.ItemState.cannotAfford(option.cost)
    }

    return ShopItemSurface(
        owned = owned,
        equipped = equipped,
        locked = locked,
        affordable = affordable,
        trying = trying,
        removable = removable,
        stateLabel = stateLabel,
        contentDescription = Copy.Shop.ItemState.label(option.name, stateLabel),
        sticker = when {
            !owned -> ShopSticker.None
            removable -> ShopSticker.Remove
            else -> ShopSticker.Check(filled = equipped)
        },
        pricePanel = when {
            owned -> ShopPricePanel.None
            locked -> ShopPricePanel.Locked(
                Copy.Shop.ItemState.lockedBadge(minStage + 1, option.cost),
            )

            affordable -> ShopPricePanel.Affordable(Copy.Shop.ItemState.priceBadge(option.cost))
            else -> ShopPricePanel.Unaffordable(Copy.Shop.ItemState.priceBadge(option.cost))
        },
    )
}

// --- The factory-look tile's state (pure, host-tested) ----------------------

/**
 * `DefaultTile` in `Shop.tsx`: the mascot's factory look for one slot, shown as
 * an ordinary ALREADY-OWNED tile — never "reset/default" jargon in front of a
 * child. Selecting it clears the slot, so the rig falls back to this exact look.
 */
data class DefaultLookSurface(
    /**
     * `config[kind][slot] === undefined` — the slot is unwritten, so the factory
     * look is what the rig shows.
     */
    val active: Boolean,
    val locked: Boolean,
    /** « seedling niv. N » / « Équipé ✓ » / « À toi ». */
    val badge: String,
    val stateLabel: String,
    val contentDescription: String,
)

/** The factory-look surface for (look, config) — the fake constructor again. */
@Suppress("FunctionNaming")
fun DefaultLookSurface(look: DefaultLook, config: MascotConfig): DefaultLookSurface {
    val minStage = look.minStage ?: 0
    val locked = config.stage < minStage
    val active = if (look.category == CustomizationCategory.COLOR) {
        config.colors[look.slot] == null
    } else {
        config.styles[look.slot] == null
    }

    val stateLabel = when {
        locked -> Copy.Shop.DefaultLook.lockedState(minStage + 1)
        active -> Copy.Shop.DefaultLook.EQUIPPED_STATE
        else -> Copy.Shop.DefaultLook.OWNED_STATE
    }

    return DefaultLookSurface(
        active = active,
        locked = locked,
        badge = when {
            locked -> Copy.Shop.DefaultLook.lockedBadge(minStage + 1)
            active -> Copy.Shop.DefaultLook.EQUIPPED_BADGE
            else -> Copy.Shop.DefaultLook.OWNED_BADGE
        },
        stateLabel = stateLabel,
        contentDescription = Copy.Shop.ItemState.label(look.name, stateLabel),
    )
}

// --- Shared tile metrics (the Tailwind classes, verbatim) -------------------

object ShopTileMetrics {

    /**
     * `min-h-[96px]`.
     *
     * Invariant 6's floor for a tile is 92 dp (`Tile.kt`) and the platform
     * minimum is 44 dp; 96 clears both, and it is the authored number, so it is
     * NOT expressed as `max(96, floor)` — a metric that quietly differs from the
     * web is how the two apps stop agreeing.
     */
    val MIN_HEIGHT: Dp = 96.dp

    /** `rounded-2xl p-3 gap-1`. */
    val CORNER_RADIUS: Dp = 16.dp
    val PADDING: Dp = 12.dp
    val GAP: Dp = 4.dp

    /** `opacity: locked ? 0.55 : 1` — on the whole tile, art included. */
    const val LOCKED_OPACITY = 0.55f

    /** `0 0 0 4px` — the equipped / trying ring. */
    val RING_WIDTH: Dp = 4.dp

    /** `0 8px 16px rgba(0,0,0,0.12)` under a ringed tile. */
    val RINGED_SHADOW = CssShadow(y = 8.dp, blur = 16.dp, opacity = 0.12f)

    /** `0 6px 14px rgba(0,0,0,0.10)` under a plain tile. */
    val PLAIN_SHADOW = CssShadow(y = 6.dp, blur = 14.dp, opacity = 0.10f)

    /** The corner sticker: `h-7 w-7` at `-right-1.5 -top-1.5`. */
    val STICKER_SIDE: Dp = 28.dp
    val STICKER_OVERHANG: Dp = 6.dp

    /**
     * Tailwind's plain `shadow` under the sticker and the gold price chip —
     * taken from the shared table, never re-authored (two copies of a Tailwind
     * default is how they drift).
     */
    val CHIP_SHADOW: List<CssShadow> = Shadows.tailwind

    /** Price chips: `px-2.5 py-0.5` (gold/grey), `px-2 py-0.5` (locked). */
    val CHIP_PADDING_X: Dp = 10.dp
    val CHIP_PADDING_X_LOCKED: Dp = 8.dp
    val CHIP_PADDING_Y: Dp = 2.dp

    /** `rgba(90,58,30,0.08)` (locked) / `0.10` (unaffordable) — ink brown. */
    const val LOCKED_CHIP_OPACITY = 0.08f
    const val UNAFFORDABLE_CHIP_OPACITY = 0.10f

    /** The savings meter under an unaffordable tile takes the TSX default. */
    val METER_HEIGHT: Dp = 10.dp
}

// --- The buyable tile -------------------------------------------------------

/**
 * `<ShopItem option owned equipped locked affordable trying balance
 * sinceBalance onTap />` — every flag folded into [ShopItemSurface].
 *
 * @param onTap the parent's handler. Tapping an UNOWNED tile is always a free
 *   TRY-ON; only the dialog's buy button spends. Nothing in this file decides
 *   that — see `ShopModel.tapItem`.
 */
@Composable
fun ShopItemView(
    option: CustomizationOption,
    surface: ShopItemSurface,
    balance: Int,
    sinceBalance: Int,
    reduceMotion: ReduceMotionSource,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberShopTileMotion(reduceMotion)
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(ShopTileMetrics.CORNER_RADIUS)

    val background = when {
        surface.equipped -> Palette.shopEquipped.color
        surface.trying -> Palette.shopTrying.color
        else -> Color.White.copy(alpha = Palette.White.o90)
    }
    val ring = when {
        surface.equipped -> Palette.green.color
        surface.trying -> Palette.tryRing.color
        else -> null
    }
    val shadow = if (surface.equipped || surface.trying) {
        ShopTileMetrics.RINGED_SHADOW
    } else {
        ShopTileMetrics.PLAIN_SHADOW
    }

    Box(
        modifier = modifier
            .shopMotion(motion)
            // The whole tile is ONE accessible element with ONE label; the
            // preview, the name and the price chip are `aria-hidden` in the TSX.
            .clearAndSetSemantics {
                contentDescription = surface.contentDescription
                role = Role.Button
                selected = surface.equipped
            }
            .shopTilePress(enabled = !surface.locked, motion = motion, onTap = onTap)
            .graphicsLayer {
                alpha = if (surface.locked) ShopTileMetrics.LOCKED_OPACITY else 1f
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = ShopTileMetrics.MIN_HEIGHT)
                .cssShadow(shadow, shape)
                .background(background, shape)
                .then(if (ring != null) Modifier.border(ShopTileMetrics.RING_WIDTH, ring, shape) else Modifier)
                .padding(ShopTileMetrics.PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ShopTileMetrics.GAP, Alignment.CenterVertically),
        ) {
            ItemPreview(
                species = option.species,
                category = option.category,
                slot = option.slot,
                value = option.value,
                reduceMotion = reduceMotion,
            )

            BasicText(
                text = option.name,
                // `text-center` — a two-word item name wraps under itself, and
                // `BasicText` centres through the STYLE, not through a modifier.
                style = tileNameStyle(fontScale),
                modifier = Modifier.fillMaxWidth(),
            )

            PricePanel(
                panel = surface.pricePanel,
                cost = option.cost,
                balance = balance,
                sinceBalance = sinceBalance,
                reduceMotion = reduceMotion,
                fontScale = fontScale,
            )
        }

        Sticker(
            sticker = surface.sticker,
            fontScale = fontScale,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** The "yours" sticker — icon, not words. */
@Composable
private fun Sticker(sticker: ShopSticker, fontScale: Float, modifier: Modifier = Modifier) {
    val glyph: String
    val filled: Boolean
    when (sticker) {
        ShopSticker.None -> return
        ShopSticker.Remove -> {
            glyph = Copy.Shop.ItemState.REMOVE_GLYPH
            filled = true
        }

        is ShopSticker.Check -> {
            glyph = Copy.Shop.ItemState.OWNED_GLYPH
            filled = sticker.filled
        }
    }

    Box(
        modifier = modifier
            // `-right-1.5 -top-1.5` — the chip overhangs the tile's corner.
            .offset(x = ShopTileMetrics.STICKER_OVERHANG, y = -ShopTileMetrics.STICKER_OVERHANG)
            .size(ShopTileMetrics.STICKER_SIDE)
            .cssShadow(ShopTileMetrics.CHIP_SHADOW, CircleShape)
            .background(if (filled) Palette.green.color else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = Typography.style(
                size = Typography.Size.sm,
                weight = Typography.Weight.black,
                color = if (filled) Color.White else Palette.equippedInk.color,
                fontScale = fontScale,
            ),
        )
    }
}

/** Price panel — only until owned; a bought item never shows a number. */
@Composable
private fun PricePanel(
    panel: ShopPricePanel,
    cost: Int,
    balance: Int,
    sinceBalance: Int,
    reduceMotion: ReduceMotionSource,
    fontScale: Float,
) {
    when (panel) {
        ShopPricePanel.None -> Unit

        is ShopPricePanel.Locked -> BasicText(
            text = panel.badge,
            style = Typography.style(
                size = Typography.Size.xs,
                weight = Typography.Weight.black,
                color = Palette.inkFaint.color,
                fontScale = fontScale,
            ),
            modifier = Modifier
                .background(
                    Palette.ink.color.copy(alpha = ShopTileMetrics.LOCKED_CHIP_OPACITY),
                    CircleShape,
                )
                .padding(
                    horizontal = ShopTileMetrics.CHIP_PADDING_X_LOCKED,
                    vertical = ShopTileMetrics.CHIP_PADDING_Y,
                ),
        )

        is ShopPricePanel.Affordable -> BasicText(
            text = panel.badge,
            style = Typography.style(
                size = Typography.Size.sm,
                weight = Typography.Weight.black,
                color = Palette.goldInk.color,
                fontScale = fontScale,
            ),
            modifier = Modifier
                .cssShadow(ShopTileMetrics.CHIP_SHADOW, CircleShape)
                .background(Palette.wallet.color, CircleShape)
                .padding(
                    horizontal = ShopTileMetrics.CHIP_PADDING_X,
                    vertical = ShopTileMetrics.CHIP_PADDING_Y,
                ),
        )

        is ShopPricePanel.Unaffordable -> {
            BasicText(
                text = panel.badge,
                style = Typography.style(
                    size = Typography.Size.sm,
                    weight = Typography.Weight.black,
                    color = Palette.inkUnaffordable.color,
                    fontScale = fontScale,
                ),
                modifier = Modifier
                    .background(
                        Palette.ink.color.copy(alpha = ShopTileMetrics.UNAFFORDABLE_CHIP_OPACITY),
                        CircleShape,
                    )
                    .padding(
                        horizontal = ShopTileMetrics.CHIP_PADDING_X,
                        vertical = ShopTileMetrics.CHIP_PADDING_Y,
                    ),
            )
            // "Keep saving" — the countdown, with no arithmetic asked of a
            // six-year-old.
            SavingsMeter(
                cost = cost,
                balance = balance,
                since = sinceBalance,
                reduceMotion = reduceMotion,
                height = ShopTileMetrics.METER_HEIGHT,
            )
        }
    }
}

// --- The factory-look tile --------------------------------------------------

/** `<DefaultTile look species active locked onTap />`. */
@Composable
fun DefaultTileView(
    look: DefaultLook,
    species: Species,
    surface: DefaultLookSurface,
    reduceMotion: ReduceMotionSource,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberShopTileMotion(reduceMotion)
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(ShopTileMetrics.CORNER_RADIUS)
    val shadow = if (surface.active) {
        ShopTileMetrics.RINGED_SHADOW
    } else {
        ShopTileMetrics.PLAIN_SHADOW
    }

    Column(
        modifier = modifier
            .shopMotion(motion)
            .clearAndSetSemantics {
                contentDescription = surface.contentDescription
                role = Role.Button
                selected = surface.active
            }
            .shopTilePress(enabled = !surface.locked, motion = motion, onTap = onTap)
            .graphicsLayer {
                alpha = if (surface.locked) ShopTileMetrics.LOCKED_OPACITY else 1f
            }
            .fillMaxWidth()
            .defaultMinSize(minHeight = ShopTileMetrics.MIN_HEIGHT)
            .cssShadow(shadow, shape)
            .background(
                if (surface.active) Palette.shopEquipped.color else Color.White.copy(alpha = Palette.White.o90),
                shape,
            )
            .then(
                if (surface.active) {
                    Modifier.border(ShopTileMetrics.RING_WIDTH, Palette.green.color, shape)
                } else {
                    Modifier
                },
            )
            .padding(ShopTileMetrics.PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ShopTileMetrics.GAP, Alignment.CenterVertically),
    ) {
        ItemPreview(
            species = species,
            category = look.category,
            slot = look.slot,
            value = look.value,
            reduceMotion = reduceMotion,
        )

        BasicText(
            text = look.name,
            style = tileNameStyle(fontScale),
            modifier = Modifier.fillMaxWidth(),
        )

        // `color: active ? "#3E7B3E" : "#9A7A5A"` — keyed on `active` ALONE, so
        // a LOCKED slot that is still unwritten shows its seedling badge in the
        // green. Faithful to the TSX, odd as it looks.
        BasicText(
            text = surface.badge,
            style = Typography.style(
                size = Typography.Size.xs,
                weight = Typography.Weight.black,
                color = if (surface.active) Palette.equippedInk.color else Palette.inkFaint.color,
                fontScale = fontScale,
            ),
        )
    }
}

/**
 * `text-sm font-bold leading-tight text-center` — the item name on BOTH tiles.
 * One function so the two call sites cannot drift.
 */
private fun tileNameStyle(fontScale: Float): TextStyle = Typography.style(
    size = Typography.Size.sm,
    weight = Typography.Weight.bold,
    color = Palette.ink.color,
    ratio = Typography.LineHeight.tight,
    fontScale = fontScale,
).copy(textAlign = TextAlign.Center)
