package fr.dappit.attrapelettres.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.mascot.CATALOG
import fr.dappit.attrapelettres.core.mascot.DEFAULT_LOOKS
import fr.dappit.attrapelettres.core.mascot.DefaultLook
import fr.dappit.attrapelettres.core.persistence.ProfileStorage
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.persistence.ProfileView
import fr.dappit.attrapelettres.core.persistence.applyOption
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.KVStore
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.vo.SHOP_BOUGHT
import fr.dappit.attrapelettres.core.vo.SHOP_GREW
import fr.dappit.attrapelettres.core.vo.SHOP_NEED_MORE
import fr.dappit.attrapelettres.core.vo.shopCostLine
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.pageScroll
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

// ===========================================================================
// `src/shop/Shop.tsx` — the shop / dressing-room, the spending area. The twin
// of iOS's `Shop/ShopView.swift`.
//
// Buying is a two-step ceremony a 6yo can follow: tapping an unowned tile opens
// a TRY-ON DIALOG (the mascot wears it in the card, nothing is spent, the VO
// says the price), and only its big « Acheter · ⭐ N » button spends. Owned
// items equip on tap, with no dialog at all.
//
// INVARIANT 8, the other side of it — the shop SPENDS points and never mints
// them. Every wallet mutation below is `ProfileStore.buy` / `ProfileStore.spend`
// (the latter through `performGrow`), and both only ever bump the `spent`
// counter. No arithmetic in this file increases a balance, and there is no
// award path here at all: `sessionReward` in `:core` is the only earner in the
// app and it is not reachable from this package.
//
// INVARIANT 9 — nothing here writes a bare running total. The stars themselves
// are counters inside `ProfileStore`; what a purchase writes is an LWW VALUE
// plus its `Rev` stamp (`SpeciesProgress.config` / `.owned`, stamped in
// `ProfileStore.buy`), which is exactly the class invariant 9 reserves for
// cosmetics: losing which hat a mascot wears costs nothing, losing a star costs
// trust. The one thing this file persists itself is `shop-seen` — what the
// child SAW, per child id, purely so the savings meters animate from last
// visit. It is never merged, summed or read back as money.
//
// INVARIANT 3 — a child can never wedge here. A wallet that raced empty makes
// `buy` a quiet no-op with the dialog still open, nothing locks, nothing is
// lost, and the cross / backdrop always leads out.
//
// PRICES. `:core`'s `spend()` does not reject a negative cost, exactly as
// `useProfile.tsx` does not (an inherited hole, deliberately not widened here).
// It stays unreachable because every cost this file can hand to the store comes
// from the authored `CATALOG` row or from `growthPrice`, and this package
// declares no other spend path. Do not add one.
//
// ── THE SIBLING CONTRACT ────────────────────────────────────────────────────
// Four things in this package are somebody else's file, and these signatures
// are what this one is written against (the same convention `ui/RootView.kt`
// uses for the seven screens). A wider signature breaks the build; any added
// parameter must have a default.
//
//   package fr.dappit.attrapelettres.ui.shop            // Shop/ItemPreview.kt
//     @Composable fun ItemPreview(
//         species: Species, category: CustomizationCategory,
//         slot: String, value: String,
//         reduceMotion: ReduceMotionSource, modifier: Modifier = Modifier)
//
//   package fr.dappit.attrapelettres.ui.shop            // Shop/Meter.kt
//     @Composable fun SavingsMeter(
//         cost: Int, balance: Int, since: Int,
//         reduceMotion: ReduceMotionSource, modifier: Modifier = Modifier,
//         height: Dp = …)
//
//   package fr.dappit.attrapelettres.ui.shop            // Shop/GrowthCard.kt
//     fun growthPrice(stage: Int): Int                  //  = 30 * (stage + 1)
//     fun performGrow(store: ProfileStore): Int?        //  the price, or null
//     @Composable fun GrowthCardView(
//         profile: ProfileView, sinceBalance: Int,
//         reduceMotion: ReduceMotionSource, onGrow: () -> Unit,
//         modifier: Modifier = Modifier)
//
// `performGrow` is the growth SPEND and it lives with the card, not here, for
// the same reason the web keeps `grow()` inside `GrowthCard.tsx`: the price
// curve and the spend that uses it are one thing, and splitting them is how a
// price ends up computed in two places.
// ===========================================================================

// --- Star-flight sizing -----------------------------------------------------

/**
 * `flightSize(cost)` — how many stars fly wallet → mascot on a purchase:
 * pricier = more of a shower. `Math.min(8, Math.max(3, Math.round(cost / 10)))`.
 *
 * JS `Math.round` rounds HALF TOWARD +infinity (45 / 10 = 4.5 goes to 5), which
 * Kotlin's `roundToInt` also does for positives but `Math.round` on a negative
 * would not — the floor-of-plus-a-half spelling is the JS rule verbatim and
 * costs nothing.
 */
fun shopFlightSize(cost: Int): Int {
    val rounded = floor(cost / 10.0 + 0.5).toInt()
    return min(8, maxOf(3, rounded))
}

// --- The wallet count (pure, host-tested) -----------------------------------

/**
 * `AnimatedNumber` — the wallet figure counts down (or up) instead of jumping,
 * so a child watches the price actually LEAVE the purse.
 *
 * This is the math of one count: `dur = Math.min(900, 350 + |delta| * 12)` ms,
 * eased `1 - (1-k)^2`, and the figure is `Math.round`ed each frame.
 */
data class WalletCount(val from: Int, val to: Int) {

    /** `if (from === value) return` — nothing to animate. */
    val animates: Boolean get() = from != to

    /** Milliseconds. */
    val durationMillis: Long get() = min(900L, 350L + abs(to - from) * 12L)

    /** The figure shown [elapsedMillis] into the count (clamped at both ends). */
    fun valueAt(elapsedMillis: Long): Int {
        val k = (elapsedMillis.toDouble() / durationMillis.toDouble()).coerceIn(0.0, 1.0)
        val eased = 1 - (1 - k) * (1 - k)
        // JS Math.round — half toward +infinity.
        return floor(from + (to - from) * eased + 0.5).toInt()
    }
}

// --- Zone grouping (pure, host-tested) --------------------------------------

/** One tile — a factory look, or a catalog item. */
sealed interface ShopTile {

    /** The web's React key: `default.<category>.<slot>` / the option id. */
    val id: String

    data class Factory(val look: DefaultLook) : ShopTile {
        override val id: String get() = "default.${look.category.wire}.${look.slot}"
    }

    data class Item(val option: CustomizationOption) : ShopTile {
        override val id: String get() = option.id
    }
}

/**
 * Tiles bucketed under one body-part label (« Queue », « Corps », …) — the way a
 * child thinks about dressing, not colour-vs-style — with accessories pooled
 * under their own label.
 */
data class ShopTileGroup(val label: String, val tiles: List<ShopTile>)

/**
 * `labelOf(slot, category)`.
 *
 * An unknown slot falls back to its RAW KEY, exactly as `SLOT_LABEL[slot] ?? slot`
 * does — a catalogue addition shows something rather than nothing. That silent
 * fallback is also why `ShopCopyCoverageTest` walks every slot in `:core` and
 * fails if `Copy.Shop.SLOT_LABEL` has no French for it: nothing else would.
 */
fun shopSlotLabel(slot: String, category: CustomizationCategory): String =
    Copy.Shop.groupLabel(category == CustomizationCategory.ACCESSORY, slot)

/** `grouped(entries)` — first-seen label order, exactly the TSX fold. */
fun shopGrouped(entries: List<Pair<String, ShopTile>>): List<ShopTileGroup> {
    val order = mutableListOf<String>()
    val byLabel = mutableMapOf<String, MutableList<ShopTile>>()
    for ((label, tile) in entries) {
        val bucket = byLabel[label]
        if (bucket == null) {
            order.add(label)
            byLabel[label] = mutableListOf(tile)
        } else {
            bucket.add(tile)
        }
    }
    return order.map { ShopTileGroup(it, byLabel.getValue(it)) }
}

/** The wardrobe: every factory look + everything bought. No prices in here. */
fun shopArmoireGroups(profile: ProfileView): List<ShopTileGroup> {
    val species = profile.config.species
    val defaults = DEFAULT_LOOKS[species] ?: emptyList()
    val owned = CATALOG.filter { it.species == species && it.id in profile.owned }
    return shopGrouped(
        defaults.map { shopSlotLabel(it.slot, it.category) to ShopTile.Factory(it) } +
            owned.map { shopSlotLabel(it.slot, it.category) to ShopTile.Item(it) },
    )
}

/** The store: only what is NOT yet owned — a bought item moves to the wardrobe. */
fun shopStoreGroups(profile: ProfileView): List<ShopTileGroup> {
    val species = profile.config.species
    val forSale = CATALOG.filter { it.species == species && it.id !in profile.owned }
    return shopGrouped(forSale.map { shopSlotLabel(it.slot, it.category) to ShopTile.Item(it) })
}

// --- The try-on dialog's surface (pure, host-tested) ------------------------

/**
 * What the dialog shows for (option, balance) — the ONE place a purchase is
 * decided.
 */
data class TryOnSurface(
    /** `aria-label={`Essayer ${option.name}`}` on the dialog itself. */
    val title: String,
    val name: String,
    val affordable: Boolean,
    /** « Acheter · ⭐ N » / « ⭐ N · pas encore ». */
    val buyLabel: String,
    /** The buy button's `aria-label`. */
    val buyContentDescription: String,
    /** `!affordable` — the gap meter under the buttons. */
    val showsMeter: Boolean,
)

/** The dialog surface for (option, balance) — a "fake constructor", as in ShopItem.kt. */
@Suppress("FunctionNaming")
fun TryOnSurface(option: CustomizationOption, balance: Int): TryOnSurface {
    // `balance >= cost`, not `>`: buying with exactly enough must succeed.
    val affordable = balance >= option.cost
    return TryOnSurface(
        title = Copy.Shop.TryOn.title(option.name),
        name = option.name,
        affordable = affordable,
        buyLabel = if (affordable) {
            Copy.Shop.TryOn.buyLabel(option.cost)
        } else {
            Copy.Shop.TryOn.notYetLabel(option.cost)
        },
        buyContentDescription = if (affordable) {
            Copy.Shop.TryOn.buy(option.name, option.cost)
        } else {
            Copy.Shop.TryOn.cannotAfford(option.name)
        },
        showsMeter = !affordable,
    )
}

// --- The celebration seam ---------------------------------------------------

/**
 * The purchase / growth particle ceremony: stars flying from the tapped button
 * to the mascot, and the growth "poof".
 *
 * A SEAM over `ShopAnim.kt`'s [ShopCelebrations], and deliberately so: the
 * particles need LAID-OUT GEOMETRY (« the stars fly from the tapped button to
 * the HEADER mascot »), which only the renderer has. Putting the model's hooks
 * behind this interface means the whole buy / equip / grow surface is
 * host-testable — a test installs a recorder and asserts the ORDER the web
 * fires things in — while the geometry stays in the composable.
 *
 * The live implementation is [rememberLiveCelebration]; [None] is a shop with
 * no particles, which is also exactly what reduced motion produces on the web
 * (`shop/anim.ts` returns before spawning anything). A missing celebration is
 * never a stall: nothing in the buy path awaits it.
 */
interface ShopCelebration {

    /** A purchase landed: stars fly from the buy button to the header mascot. */
    fun purchased(option: CustomizationOption)

    /** An equip / toggle / factory-look tap: the preview bounces. */
    fun equipped()

    /** Growth: stars fly from the wallet (fires BEFORE the success SFX). */
    fun grewFlight(price: Int)

    /** Growth: the preview pops and the cloud burst blows (fires after the line). */
    fun grewBurst()

    /** No particles. The reduced-motion behaviour, and the safe default. */
    object None : ShopCelebration {
        override fun purchased(option: CustomizationOption) = Unit
        override fun equipped() = Unit
        override fun grewFlight(price: Int) = Unit
        override fun grewBurst() = Unit
    }
}

// --- The model --------------------------------------------------------------

/**
 * The `Shop` component's logic, extracted whole so the host suite drives the
 * buy / equip / grow surface without a renderer (A11).
 *
 * Every method below is one TSX handler, in its statement order — the order
 * matters, because it is what a child hears: the pop before the dialog, the
 * flight before the success chime, the spoken line before the burst.
 *
 * @param scope the screen's coroutine scope. `say` is fire-and-forget (the web
 *   dangles the promise, `void audio.say(...)`), and the shop never gates on
 *   speech; [speechJob] exists only so a test can join it.
 */
@Stable
class ShopModel(
    val store: ProfileStore,
    private val audio: AudioEngine,
    private val kv: KVStore,
    private val scope: CoroutineScope,
    var celebration: ShopCelebration = ShopCelebration.None,
) {

    /**
     * The try-on "cart": at most ONE unowned option, shown worn in the dialog
     * but NOT bought. Any real config change clears it.
     */
    var cart: CustomizationOption? by mutableStateOf(null)
        private set

    /**
     * Balance this child last SAW here — the savings meters animate from it, so
     * stars earned since the previous visit land as motion rather than as a
     * static bar. Captured ONCE per mount, exactly like
     * `useState(() => loadShopSeen()[activeId] ?? 0)`.
     */
    val sinceBalance: Int = ProfileStorage.loadShopSeen(kv)[store.activeId ?: ""] ?: 0

    /** The in-flight `void audio.say(...)`; a test may join it, the app never does. */
    var speechJob: Job? = null
        private set

    /** `cartAffordable` — the buy button's enabled flag, read live. */
    val cartAffordable: Boolean
        get() = cart?.let { store.profile.balance >= it.cost } ?: false

    /**
     * A catalogue tile's tap: owned = equip / toggle right away (free); unowned
     * = try-on, never a spend.
     */
    fun tapItem(option: CustomizationOption) {
        if (option.id in store.profile.owned) {
            audio.unlock()
            audio.pop()
            cart = null
            if (option.category == CustomizationCategory.ACCESSORY) {
                toggleAccessory(option)
            } else {
                applyVariant(option)
            }
        } else {
            tryOn(option)
        }
    }

    /** Free try-on: open the dialog, say the price. Nothing is spent here. */
    fun tryOn(option: CustomizationOption) {
        audio.unlock()
        audio.pop()
        cart = option
        val line = if (store.profile.balance >= option.cost) {
            shopCostLine(option.cost)
        } else {
            "${shopCostLine(option.cost)} $SHOP_NEED_MORE"
        }
        say(line)
    }

    /**
     * The ONLY place a shop item is paid for: the dialog's buy button.
     *
     * A wallet that raced empty is a soft no-op — no error, no lock, and the
     * dialog stays open so the cross still leads out (invariant 3).
     */
    fun confirmBuy() {
        val option = cart ?: return
        audio.unlock()
        if (!store.buy(option)) return
        celebration.purchased(option)
        audio.success()
        say(SHOP_BOUGHT)
        cart = null
    }

    /** Backdrop tap / the cross — « non merci ». */
    fun cancelTryOn() {
        audio.unlock()
        audio.pop()
        cart = null
    }

    /** Colours and style variants just apply (a free re-apply once owned). */
    private fun applyVariant(option: CustomizationOption) {
        store.buy(option)
        celebration.equipped()
    }

    /** Accessories toggle: tapping the equipped one takes it off; else equip. */
    private fun toggleAccessory(option: CustomizationOption) {
        if (option.id in store.profile.config.accessories) {
            store.setConfig { c -> c.copy(accessories = c.accessories.filter { it != option.id }) }
        } else {
            store.buy(option)
        }
        celebration.equipped()
    }

    /**
     * Return one colour/style slot to its factory look. Clearing the slot IS the
     * revert: the rig falls back to the value the tile shows.
     */
    fun clearSlot(look: DefaultLook) {
        audio.unlock()
        audio.pop()
        cart = null
        store.setConfig { c ->
            if (look.category == CustomizationCategory.COLOR) {
                c.copy(colors = c.colors - look.slot)
            } else {
                c.copy(styles = c.styles - look.slot)
            }
        }
        celebration.equipped()
    }

    /**
     * The growth spend — `GrowthCard.grow()` plus the shop's `onGrew`, in the
     * web's exact order: flight, success SFX, spoken line, pop + burst.
     */
    fun grow() {
        val price = performGrow(store) ?: return
        celebration.grewFlight(price)
        audio.success()
        say(SHOP_GREW)
        celebration.grewBurst()
    }

    /**
     * The `useEffect([activeId, balance])` half of shop-seen: remember what this
     * child saw, so next visit's meters animate from here. Merges OVER the
     * stored map — a sibling's entry survives.
     */
    fun recordSeen() {
        val id = store.activeId ?: return
        ProfileStorage.saveShopSeen(ProfileStorage.loadShopSeen(kv) + (id to store.profile.balance), kv)
    }

    /** `void audio.say(...)` — fire and forget; the shop never gates on speech. */
    private fun say(text: String) {
        speechJob = scope.launch { audio.say(text) }
    }
}

// --- Metrics (the Tailwind classes and inline styles, verbatim) -------------

object ShopMetrics {

    /** Root: `min-h-[620px] gap-4 rounded-3xl pb-10`. */
    val MIN_HEIGHT: Dp = Shell.minimumScreenHeight
    val ROOT_GAP: Dp = 16.dp
    val CORNER_RADIUS: Dp = 24.dp
    val PADDING_BOTTOM: Dp = 40.dp

    /** Header: `gap-2 rounded-b-3xl px-5 pb-4 pt-4` over the 82 % cream. */
    val HEADER_GAP: Dp = 8.dp
    val HEADER_PADDING_X: Dp = 20.dp
    val HEADER_PADDING_Y: Dp = 16.dp

    /** `<Mascot size={128} />` — the header friend, the REAL look. */
    val HEADER_MASCOT_SIZE: Dp = 128.dp

    /** Chips: `px-4 py-2`, back `text-base`, wallet `text-lg`. */
    val CHIP_PADDING_X: Dp = 16.dp
    val CHIP_PADDING_Y: Dp = 8.dp
    val CHIP_SHADOW: List<CssShadow> = Shadows.tailwind

    /** Zones column: `gap-5 px-5`; a zone is `rounded-3xl p-4`. */
    val ZONE_GAP: Dp = 20.dp
    val ZONE_PADDING_X: Dp = 20.dp
    val ZONE_PADDING: Dp = 16.dp
    val ZONE_HEADING_GAP: Dp = 12.dp
    val ZONE_CONTENT_GAP: Dp = 16.dp
    val ZONE_SHADOW = CssShadow(y = 8.dp, blur = 18.dp, opacity = 0.07f)

    /** The heading chip: `h-10 w-10 rounded-2xl text-xl`, white 85 %. */
    val ZONE_CHIP_SIDE: Dp = 40.dp
    val ZONE_CHIP_RADIUS: Dp = 16.dp
    val ZONE_HEADING_SPACING: Dp = 10.dp

    /** Group grids: `mb-1` under the label, `grid-cols-2 gap-2.5`. */
    val GROUP_LABEL_GAP: Dp = 4.dp
    val GRID_SPACING: Dp = 10.dp
    const val GRID_COLUMNS = 2

    /**
     * Dialog: `p-5` around, card `max-w-sm gap-3 rounded-3xl p-6`,
     * `0 24px 60px rgba(0,0,0,0.35)`, mascot 150, meter `w-4/5` x 12 high.
     */
    val DIALOG_PADDING: Dp = 20.dp
    val CARD_MAX_WIDTH: Dp = 384.dp
    val CARD_GAP: Dp = 12.dp
    val CARD_PADDING: Dp = 24.dp
    val CARD_SHADOW = CssShadow(y = 24.dp, blur = 60.dp, opacity = 0.35f)
    val CARD_MASCOT_SIZE: Dp = 150.dp
    const val CARD_METER_FRACTION = 0.8f
    val CARD_METER_HEIGHT: Dp = 12.dp

    /** Buttons row `gap-2`; the cancel cross is `h-12 w-12`. */
    val CARD_BUTTON_GAP: Dp = 8.dp
    val CANCEL_SIDE: Dp = 48.dp

    /** Buy: `px-6 py-3 text-lg`, lip `0 6px 0 rgba(0,0,0,0.12)`. */
    val BUY_PADDING_X: Dp = 24.dp
    val BUY_PADDING_Y: Dp = 12.dp
    val BUY_LIP = CssShadow(y = 6.dp, blur = 0.dp, opacity = 0.12f)
}

// --- The view ---------------------------------------------------------------

/**
 * `<Shop onBack />` — the shop screen the router mounts on `AppRoute.Shop`.
 *
 * @param profiles the roster; the ONLY thing that moves money.
 * @param kv read for `shop-seen` and written back on every balance change. Not
 *   used for anything else: `:ui` never touches storage for a profile.
 * @param celebration overrides the particle seam; null takes the live one. See
 *   [ShopCelebration].
 */
@Composable
fun ShopView(
    profiles: ProfileStore,
    audio: AudioEngine,
    kv: KVStore,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    celebration: ShopCelebration? = null,
) {
    val scope = rememberCoroutineScope()
    val model = remember(profiles, audio, kv, scope) { ShopModel(profiles, audio, kv, scope) }
    val density = LocalDensity.current

    // The particle layer and the three rects it flies between. A rect is null
    // until its element has laid out, which `StarFlightSpec` treats exactly as
    // the web's `if (a.width === 0) return` does — no flight, no crash.
    val celebrations = rememberShopCelebrations(reduceMotion)
    val origin = remember { mutableStateOf(Offset.Zero) }
    val walletRect = remember { mutableStateOf<ShopRect?>(null) }
    val previewRect = remember { mutableStateOf<ShopRect?>(null) }
    val buyRect = remember { mutableStateOf<ShopRect?>(null) }
    // The header friend's equip bounce — `pop(previewRef.current)`.
    val previewMotion = rememberShopTileMotion(reduceMotion)

    val live = rememberLiveCelebration(celebrations, previewMotion, walletRect, previewRect, buyRect)
    // Not assigned in the composable body: a write during composition is a side
    // effect Compose may run more than once.
    SideEffect { model.celebration = celebration ?: live }

    // The roster is the observable half of `ProfileStore`; reading it here is
    // what makes a purchase repaint the tiles, the wallet and the zones.
    val roster by profiles.rosterFlow.collectAsState()
    val profile = remember(roster) { profiles.profile }

    // `useEffect([activeId, balance])` — mount, and every balance change after.
    LaunchedEffect(profile.balance, profiles.activeId) { model.recordSeen() }

    // `fillMaxSize`, not wrap-content: the shell hands this screen a bounded
    // height, and the scroller below needs it. A wrap-content root would give
    // the scrolling column infinite space to measure into, which does not throw
    // — it silently never scrolls, and the bottom of the store becomes
    // unreachable on a phone. `min-h-[620px]` stays as the floor underneath.
    Box(
        modifier = modifier
            .fillMaxSize()
            .defaultMinSize(minHeight = ShopMetrics.MIN_HEIGHT)
            .clip(RoundedCornerShape(ShopMetrics.CORNER_RADIUS))
            .onGloballyPositioned { origin.value = it.positionInRoot() }
            .drawBehind { drawRect(Palette.stageAdult.brush(size)) },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // `sticky top-0 z-10` becomes a header ABOVE the scroller: Compose
            // has no sticky, and a header that scrolls away would take the
            // wallet with it — the one number a child watching a purchase is
            // looking at.
            ShopHeader(
                profile = profile,
                reduceMotion = reduceMotion,
                previewMotion = previewMotion,
                walletModifier = Modifier.captureShopRect(origin, density, walletRect),
                previewModifier = Modifier.captureShopRect(origin, density, previewRect),
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pageScroll()
                    .padding(
                        top = ShopMetrics.ROOT_GAP,
                        bottom = ShopMetrics.PADDING_BOTTOM,
                    )
                    .padding(horizontal = ShopMetrics.ZONE_PADDING_X),
                verticalArrangement = Arrangement.spacedBy(ShopMetrics.ZONE_GAP),
            ) {
                // Yours first: dressing what you own is the everyday action.
                ShopZone(
                    icon = Copy.Shop.WARDROBE_GLYPH,
                    title = Copy.Shop.WARDROBE_TITLE,
                    tint = { size -> Palette.wardrobeZone.brush(size) },
                ) {
                    TileGroups(
                        groups = shopArmoireGroups(profile),
                        profile = profile,
                        model = model,
                        reduceMotion = reduceMotion,
                    )
                }

                // Then the store: growth (the headline spend) + everything unowned.
                ShopZone(
                    icon = Copy.Shop.STORE_GLYPH,
                    title = Copy.Shop.STORE_TITLE,
                    tint = { size -> Palette.storeZone.brush(size) },
                ) {
                    GrowthCardView(
                        profile = profile,
                        sinceBalance = model.sinceBalance,
                        reduceMotion = reduceMotion,
                        onGrow = { model.grow() },
                    )

                    val groups = shopStoreGroups(profile)
                    if (groups.isEmpty()) {
                        BasicText(
                            text = Copy.Shop.STORE_EMPTY,
                            style = Typography.style(
                                size = Typography.Size.base,
                                weight = Typography.Weight.bold,
                                color = Palette.inkSoft.color,
                                fontScale = LocalDensity.current.fontScale,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        TileGroups(
                            groups = groups,
                            profile = profile,
                            model = model,
                            reduceMotion = reduceMotion,
                        )
                    }
                }
            }
        }

        // `fixed inset-0 z-50` — the dialog sits OVER the header, which is why
        // it is a sibling of the whole column and not a child of the scroller.
        val cart = model.cart
        if (cart != null) {
            TryOnDialog(
                option = cart,
                profile = profile,
                sinceBalance = model.sinceBalance,
                reduceMotion = reduceMotion,
                buyModifier = Modifier.captureShopRect(origin, density, buyRect),
                onBuy = { model.confirmBuy() },
                onCancel = { model.cancelTryOn() },
            )
        }

        // `z-index: 60` — the throwaway particle layer, over the dialog's
        // `z-50`, taking no touch and invisible to TalkBack (both of which are
        // ShopCelebrationOverlay's own doing).
        ShopCelebrationOverlay(celebrations, Modifier.fillMaxSize())
    }
}

/**
 * The live [ShopCelebration] — the four `anim.ts` calls, at the four points the
 * TSX makes them, with the geometry the renderer alone knows.
 *
 * Held in a `remember` so the model is not handed a new object every frame; the
 * rect states are read at FIRE time, not captured, so a chip that laid out
 * after this was built still contributes.
 */
@Composable
private fun rememberLiveCelebration(
    celebrations: ShopCelebrations,
    previewMotion: ShopTileMotion,
    walletRect: MutableState<ShopRect?>,
    previewRect: MutableState<ShopRect?>,
    buyRect: MutableState<ShopRect?>,
): ShopCelebration = remember(celebrations, previewMotion) {
    object : ShopCelebration {
        override fun purchased(option: CustomizationOption) {
            // « the stars fly from the tapped button to the HEADER mascot » —
            // the wallet stands in if the button never laid out.
            celebrations.starFlight(
                from = buyRect.value ?: walletRect.value,
                to = previewRect.value,
                count = shopFlightSize(option.cost),
            )
            previewMotion.pop()
        }

        override fun equipped() {
            previewMotion.pop()
        }

        override fun grewFlight(price: Int) {
            celebrations.starFlight(
                from = walletRect.value,
                to = previewRect.value,
                count = shopFlightSize(price),
            )
        }

        override fun grewBurst() {
            previewMotion.pop()
            celebrations.growBurst(previewRect.value)
        }
    }
}

/**
 * Report this element's box, in DP, in the shop root's own space — the space
 * [ShopCelebrations] spawns into.
 *
 * `boundsInRoot()` is in raw pixels against the COMPOSITION root, which is the
 * window, not this screen; subtracting the shop root's own position is what
 * makes a star land on the mascot rather than 16 dp of shell gutter away. The
 * px → dp conversion is the other half of that: `ShopRect` is dp by type,
 * precisely so a px rect cannot be handed to a dp spec and silently scale the
 * whole flight by the display density.
 */
private fun Modifier.captureShopRect(
    origin: MutableState<Offset>,
    density: Density,
    into: MutableState<ShopRect?>,
): Modifier = this.onGloballyPositioned { coordinates ->
    val bounds = coordinates.boundsInRoot()
    val at = origin.value
    into.value = with(density) {
        ShopRect(
            x = (bounds.left - at.x).toDp().value,
            y = (bounds.top - at.y).toDp().value,
            width = bounds.width.toDp().value,
            height = bounds.height.toDp().value,
        )
    }
}

// --- Header -----------------------------------------------------------------

@Composable
private fun ShopHeader(
    profile: ProfileView,
    reduceMotion: ReduceMotionSource,
    previewMotion: ShopTileMotion,
    walletModifier: Modifier,
    previewModifier: Modifier,
    onBack: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Palette.shopHeader,
                RoundedCornerShape(
                    bottomStart = ShopMetrics.CORNER_RADIUS,
                    bottomEnd = ShopMetrics.CORNER_RADIUS,
                ),
            )
            .padding(
                horizontal = ShopMetrics.HEADER_PADDING_X,
                vertical = ShopMetrics.HEADER_PADDING_Y,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ShopMetrics.HEADER_GAP),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = Copy.Shop.BACK_LABEL,
                style = Typography.style(
                    size = Typography.Size.base,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
                modifier = Modifier
                    .cssShadow(ShopMetrics.CHIP_SHADOW, CircleShape)
                    .background(Color.White.copy(alpha = Palette.White.o85), CircleShape)
                    // `onClick`, not `onPointerDown`: leaving the shop is the
                    // one action here that must survive a scroll that started
                    // on the chip (the shop is the app's one long scroller).
                    .touchDown(onUp = { inside -> if (inside) onBack() }) { }
                    .padding(
                        horizontal = ShopMetrics.CHIP_PADDING_X,
                        vertical = ShopMetrics.CHIP_PADDING_Y,
                    )
                    .clearAndSetSemantics {
                        contentDescription = Copy.Shop.BACK
                        role = Role.Button
                    },
            )

            Wallet(
                balance = profile.balance,
                reduceMotion = reduceMotion,
                modifier = walletModifier,
            )
        }

        // The header friend is the REAL look — what is owned and worn. Trying on
        // happens in the dialog, so this never flickers with maybes.
        Ollie(
            config = profile.config,
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            // `pop(previewRef.current)` on every equip, and the target of every
            // star flight — hence both a motion and a rect capture.
            modifier = previewModifier.shopMotion(previewMotion),
            size = ShopMetrics.HEADER_MASCOT_SIZE,
        )

        BasicText(
            text = Copy.Shop.TAGLINE,
            style = Typography.style(
                size = Typography.Size.sm,
                weight = Typography.Weight.bold,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
        )
    }
}

/** The wallet chip. One accessible element, one label: « 60 points ». */
@Composable
private fun Wallet(
    balance: Int,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val style = Typography.style(
        size = Typography.Size.lg,
        weight = Typography.Weight.black,
        color = Palette.goldInk.color,
        fontScale = fontScale,
    )

    Row(
        modifier = modifier
            .cssShadow(ShopMetrics.CHIP_SHADOW, CircleShape)
            .background(Palette.wallet.color, CircleShape)
            .padding(
                horizontal = ShopMetrics.CHIP_PADDING_X,
                vertical = ShopMetrics.CHIP_PADDING_Y,
            )
            .clearAndSetSemantics { contentDescription = Copy.Shop.wallet(balance) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = "${Copy.Shop.WALLET_GLYPH} ", style = style)
        WalletCountText(value = balance, reduceMotion = reduceMotion, style = style)
    }
}

/**
 * `AnimatedNumber` — the count that lets a child SEE the price leave the purse.
 *
 * INVARIANT 2, with the one honest deviation in this file. The web writes
 * `el.textContent` from a rAF loop, off React's render path entirely; a
 * `graphicsLayer` cannot do that, because the thing changing is TEXT, not a
 * transform. So the frame loop drives one `mutableIntStateOf` read by this leaf
 * and by nothing else: the recomposition is a single `BasicText` with no
 * children, no layout above it depends on the value (the chip is sized by its
 * padding and the widest digit run), and the shop's zones — the expensive part
 * of the tree — never see it. That is the smallest possible Compose analogue of
 * `textContent`, and it is confined to this 20-line composable.
 *
 * Under reduced motion the figure simply jumps, which is `if (reducedMotion())`
 * in the TSX.
 */
@Composable
private fun WalletCountText(
    value: Int,
    reduceMotion: ReduceMotionSource,
    style: TextStyle,
) {
    var shown by remember { mutableIntStateOf(value) }

    LaunchedEffect(value) {
        // A change landing mid-count rewinds from the figure currently ON
        // SCREEN (`shownRef.current`), not from the previous target.
        val count = WalletCount(from = shown, to = value)
        if (!count.animates) return@LaunchedEffect
        if (!shopMotionAllowed(reduceMotion)) {
            shown = value
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsed = (nowNanos - startNanos) / 1_000_000
            shown = count.valueAt(elapsed)
            if (elapsed >= count.durationMillis) break
        }
        shown = value
    }

    BasicText(text = shown.toString(), style = style)
}

// --- Zones ------------------------------------------------------------------

/**
 * A zone is a ROOM, not a heading: the wardrobe (what is yours, no prices) and
 * the store (what is for sale). Distinct tints plus an icon chip make the split
 * SPATIAL, so ownership is a place a pre-reader can see, not a text badge.
 */
@Composable
private fun ShopZone(
    icon: String,
    title: String,
    tint: (Size) -> Brush,
    content: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(ShopMetrics.CORNER_RADIUS)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cssShadow(ShopMetrics.ZONE_SHADOW, shape)
            .clip(shape)
            .drawBehind { drawRect(tint(size)) }
            .padding(ShopMetrics.ZONE_PADDING),
    ) {
        Row(
            modifier = Modifier.padding(bottom = ShopMetrics.ZONE_HEADING_GAP),
            horizontalArrangement = Arrangement.spacedBy(ShopMetrics.ZONE_HEADING_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ShopMetrics.ZONE_CHIP_SIDE)
                    .cssShadow(
                        ShopMetrics.CHIP_SHADOW,
                        RoundedCornerShape(ShopMetrics.ZONE_CHIP_RADIUS),
                    )
                    .background(
                        Color.White.copy(alpha = Palette.White.o85),
                        RoundedCornerShape(ShopMetrics.ZONE_CHIP_RADIUS),
                    )
                    // `aria-hidden` — the heading next to it says the same thing.
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = icon,
                    style = Typography.style(size = Typography.Size.xl, fontScale = fontScale),
                )
            }

            BasicText(
                text = title,
                style = Typography.style(
                    size = Typography.Size.xl,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
                modifier = Modifier.semantics { heading() },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ShopMetrics.ZONE_CONTENT_GAP)) {
            content()
        }
    }
}

/**
 * `grid-cols-2 gap-2.5` under a `text-sm` body-part label.
 *
 * Rows of two with `weight(1f)` cells, NOT a `LazyVGrid`: the whole shop is
 * already inside one vertical scroller, and a lazy grid nested in a scrolling
 * column has no bounded height to measure against. It is also what makes the
 * grid EXACT — a weight is CSS's `1fr`, so the two columns are equal whatever
 * the card width is, with no viewport arithmetic to get wrong.
 *
 * The trailing odd tile keeps its half width, exactly as a CSS grid leaves the
 * second cell of the last row empty. A `Spacer` would be another node in the
 * semantics tree, so the empty cell is a weighted `Box` with nothing in it.
 */
@Composable
private fun TileGroups(
    groups: List<ShopTileGroup>,
    profile: ProfileView,
    model: ShopModel,
    reduceMotion: ReduceMotionSource,
) {
    val fontScale = LocalDensity.current.fontScale

    for (group in groups) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = group.label,
                style = Typography.style(
                    size = Typography.Size.sm,
                    weight = Typography.Weight.bold,
                    color = Palette.inkSoft.color,
                    fontScale = fontScale,
                ),
                modifier = Modifier.padding(bottom = ShopMetrics.GROUP_LABEL_GAP),
            )

            Column(verticalArrangement = Arrangement.spacedBy(ShopMetrics.GRID_SPACING)) {
                for (row in group.tiles.chunked(ShopMetrics.GRID_COLUMNS)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ShopMetrics.GRID_SPACING),
                    ) {
                        for (tile in row) {
                            Box(modifier = Modifier.weight(1f)) {
                                ShopTileView(
                                    tile = tile,
                                    profile = profile,
                                    model = model,
                                    reduceMotion = reduceMotion,
                                )
                            }
                        }
                        repeat(ShopMetrics.GRID_COLUMNS - row.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopTileView(
    tile: ShopTile,
    profile: ProfileView,
    model: ShopModel,
    reduceMotion: ReduceMotionSource,
) {
    when (tile) {
        is ShopTile.Factory -> DefaultTileView(
            look = tile.look,
            species = profile.config.species,
            surface = DefaultLookSurface(tile.look, profile.config),
            reduceMotion = reduceMotion,
            onTap = { model.clearSlot(tile.look) },
        )

        is ShopTile.Item -> ShopItemView(
            option = tile.option,
            surface = ShopItemSurface(tile.option, profile, model.cart?.id),
            balance = profile.balance,
            sinceBalance = model.sinceBalance,
            reduceMotion = reduceMotion,
            onTap = { model.tapItem(tile.option) },
        )
    }
}

// --- The try-on dialog ------------------------------------------------------

/**
 * The ONE place a purchase is decided. The mascot wears the item IN the card
 * (the shop behind stays as it is), the price sits on the buy button, and the
 * meter shows the gap when the wallet is short. Backdrop tap = « non merci ».
 *
 * Nothing here spends; `ShopModel.confirmBuy` does.
 */
@Composable
private fun TryOnDialog(
    option: CustomizationOption,
    profile: ProfileView,
    sinceBalance: Int,
    reduceMotion: ReduceMotionSource,
    buyModifier: Modifier,
    onBuy: () -> Unit,
    onCancel: () -> Unit,
) {
    val surface = TryOnSurface(option, profile.balance)
    val fontScale = LocalDensity.current.fontScale
    val cardMotion = rememberShopTileMotion(reduceMotion)
    val buyMotion = rememberShopTileMotion(reduceMotion)
    val shape = RoundedCornerShape(ShopMetrics.CORNER_RADIUS)

    // Mount ceremony: the card pops in. Gated, like every `shop/anim.ts` call.
    LaunchedEffect(option.id) { cardMotion.pop() }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // The scrim is a SIBLING of the card, not its parent: Compose delivers a
        // pointer event to the topmost sibling under the finger, so a tap on the
        // card never reaches this node. That is `e.stopPropagation()` on the
        // card, expressed as z-order instead of as a handler.
        // `rgba(74,48,24,0.45)`; cancelling on the LIFT, like the web's click.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.tryOnScrim)
                .touchDown(onUp = { inside -> if (inside) onCancel() }) { }
                .clearAndSetSemantics { },
        )

        Column(
            modifier = Modifier
                .shopMotion(cardMotion)
                .padding(ShopMetrics.DIALOG_PADDING)
                .fillMaxWidth()
                .widthIn(max = ShopMetrics.CARD_MAX_WIDTH)
                .cssShadow(ShopMetrics.CARD_SHADOW, shape)
                .clip(shape)
                .drawBehind { drawRect(Palette.stageAdult.brush(size)) }
                .padding(ShopMetrics.CARD_PADDING)
                .semantics { contentDescription = surface.title },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ShopMetrics.CARD_GAP),
        ) {
            // The friend wears the item HERE — trying it on is the whole point.
            Ollie(
                config = applyOption(profile.config, option),
                mood = Mood.IDLE,
                reduceMotion = reduceMotion,
                size = ShopMetrics.CARD_MASCOT_SIZE,
            )

            BasicText(
                text = surface.name,
                style = Typography.style(
                    size = Typography.Size.xl,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    ShopMetrics.CARD_BUTTON_GAP,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // « ✕ » — 48 dp, the platform tap-target floor, and the way out
                // of every state this dialog can be in (invariant 3).
                Box(
                    modifier = Modifier
                        .size(ShopMetrics.CANCEL_SIDE)
                        .cssShadow(ShopMetrics.CHIP_SHADOW, CircleShape)
                        .background(Color.White.copy(alpha = Palette.White.o90), CircleShape)
                        .touchDown(onUp = { inside -> if (inside) onCancel() }) { }
                        .clearAndSetSemantics {
                            contentDescription = Copy.Shop.TryOn.CANCEL
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = Copy.Shop.TryOn.CANCEL_GLYPH,
                        style = Typography.style(
                            size = Typography.Size.xl,
                            weight = Typography.Weight.black,
                            color = Palette.inkFaint.color,
                            fontScale = fontScale,
                        ),
                    )
                }

                // The buy button. `disabled={!affordable}` — no press, no click,
                // but still drawn and still LABELLED, so "not yet" is announced
                // rather than hidden.
                BasicText(
                    text = surface.buyLabel,
                    style = Typography.style(
                        size = Typography.Size.lg,
                        weight = Typography.Weight.black,
                        color = if (surface.affordable) {
                            Palette.goldInk.color
                        } else {
                            Palette.disabled.color
                        },
                        fontScale = fontScale,
                    ),
                    modifier = buyModifier
                        .shopMotion(buyMotion)
                        .then(
                            if (surface.affordable) {
                                Modifier.cssShadow(ShopMetrics.BUY_LIP, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .background(
                            if (surface.affordable) {
                                Palette.wallet.color
                            } else {
                                Color.White.copy(alpha = Palette.White.o70)
                            },
                            CircleShape,
                        )
                        .shopTilePress(
                            enabled = surface.affordable,
                            motion = buyMotion,
                            onTap = onBuy,
                        )
                        .padding(
                            horizontal = ShopMetrics.BUY_PADDING_X,
                            vertical = ShopMetrics.BUY_PADDING_Y,
                        )
                        .clearAndSetSemantics {
                            contentDescription = surface.buyContentDescription
                            role = Role.Button
                        },
                )
            }

            // Saving up: the meter shows how close the wallet is — no maths.
            if (surface.showsMeter) {
                Box(modifier = Modifier.fillMaxWidth(ShopMetrics.CARD_METER_FRACTION)) {
                    SavingsMeter(
                        cost = option.cost,
                        balance = profile.balance,
                        since = sinceBalance,
                        reduceMotion = reduceMotion,
                        height = ShopMetrics.CARD_METER_HEIGHT,
                    )
                }
            }
        }
    }
}
