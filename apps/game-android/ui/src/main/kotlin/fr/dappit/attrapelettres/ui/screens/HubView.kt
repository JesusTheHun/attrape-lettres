package fr.dappit.attrapelettres.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.art.icons.ExerciseIcon
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.ExerciseMeta
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.levels.MATCH_HINT
import fr.dappit.attrapelettres.core.levels.MIXED_HINT
import fr.dappit.attrapelettres.core.levels.MODE_HINT
import fr.dappit.attrapelettres.core.levels.SPELL_HINT
import fr.dappit.attrapelettres.core.licensing.Entitlement
import fr.dappit.attrapelettres.core.licensing.trialNotice
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.TEXT_LG_LEADING
import fr.dappit.attrapelettres.ui.components.WrapRowLines
import fr.dappit.attrapelettres.ui.components.aspectSquare
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.pageScroll
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.fixedSp
import fr.dappit.attrapelettres.ui.interaction.rememberTileMotion
import fr.dappit.attrapelettres.ui.interaction.tileMotion
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ===========================================================================
// The hub half of `src/App.tsx` (lines 156-263) — the first thing a child sees.
//
// Header chips, the mascot door, the title, the trial pill, then one section per
// catalog row: icon + name + hint chips, and a five-column grid of level buttons
// with their reward badges. `screens/Router.kt` owns the routing half; this file
// owns the screen.
//
// -- INVARIANT 5 LIVES ON THIS SCREEN ---------------------------------------
// Every level of every exercise is reachable, always. [hubLevelCells] renders
// `1..meta.levelCount` with no condition at all — its inputs are the catalog row
// and the reward PREVIEW function, and nothing else. There is no profile to
// consult, no progress flag to read, no "finish level 2 first"; the tap handler
// forwards every (exercise, level) pair straight to the router's `open`. A gate
// cannot be added here without changing a signature, which is the point.
//
// The catalog is rendered in its AUTHORED order and is never sorted. That order
// is the difficulty progression, and the reward gradient is monotone with it
// (root CLAUDE.md invariant 8: the best a training row can pay is the worst a
// paying row can pay), so re-ordering the hub silently re-prices the game.
//
// -- INVARIANT 7 -------------------------------------------------------------
// Each section header renders :art's `ExerciseIcon(id)` — the drawn catalog,
// keyed by `ExerciseId` with an exhaustive `when` and no `else`, so a new
// exercise cannot compile without an icon. `ExerciseMeta` also carries a
// decorative glyph field which this screen must never read; `HubSourceScanTest`
// asserts it does not. The chrome glyphs in `Copy.Hub` (the player chip, the
// speaker, the star and the coin) are the WEB'S OWN copy and stay: they are
// strings the PWA renders, not a substitute for a drawn icon.
//
// -- INVARIANT 8, THE DISPLAY HALF -------------------------------------------
// This screen PREVIEWS points and never mints one. `ProfileStore.preview` is a
// pure read of the folded ledger — it must not write, and nothing here adds,
// multiplies or floors a number that ends up in a child's balance. The one
// earner is `core.rewards`, reached only through `ProfileStore`.
//
// `profiles.preview(id, level)` is called once per level button per composition
// (~75 calls), exactly as the TSX calls it per render.
//
// -- INVARIANT 1 AND THE SCROLL ----------------------------------------------
// 17 sections never fit 620 dp, so the hub scrolls, as the web page does. That
// puts a scrollable ancestor over the level buttons — allowed HERE, because a
// level button is navigation, not an exercise tile (`ARCHITECTURE` §3 row 1
// forbids the scroller over a tile GRID, where a wobbly press must still count).
// `Modifier.touchDown` takes its down on `PointerEventPass.Initial`, so the
// press squish is never delayed by the scroller, and it cancels on a consumed
// change, so a flick that happens to lift over a level is correctly not a tap.
// ===========================================================================

// --- Authored metrics (the App.tsx Tailwind classes, verbatim) ---------------

object HubMetrics {

    // The stage: `min-h-[620px] w-full ... overflow-hidden rounded-3xl px-5
    // pb-10 pt-8`.
    val stagePaddingX: Dp = 20.dp
    val stagePaddingTop: Dp = 32.dp
    val stagePaddingBottom: Dp = 40.dp
    val cornerRadius: Dp = 24.dp

    /** `max-w-md` — the header row and every section. */
    val rowMaxWidth: Dp = 448.dp

    // Header: `mb-4` under the row, `gap-2` between the speaker and the star.
    val headerBottomMargin: Dp = 16.dp
    val headerActionGap: Dp = 8.dp

    // Chips: `px-4 py-2` (player, balance) / `px-3 py-2` (speaker), `text-lg`.
    val chipPaddingX: Dp = 16.dp
    val listenPaddingX: Dp = 12.dp
    val chipPaddingY: Dp = 8.dp

    /** `max-w-[55%]` on the player chip — 55 % of the header ROW, not the window. */
    const val PLAYER_CHIP_FRACTION: Float = 0.55f

    // Mascot + title.
    /** `<Mascot size={112}>`, `mb-1` on its button. */
    val mascotSize: Dp = 112.dp
    val mascotBottomMargin: Dp = 4.dp

    /** `fontSize: clamp(28px,8vw,44px)` on « Attrape-Lettres ». */
    val titleSize = FluidSpec(min = 28f, vw = 8f, max = 44f)

    /** `mb-2 mt-1` on the subtitle. */
    val subtitleTopMargin: Dp = 4.dp
    val subtitleBottomMargin: Dp = 8.dp

    // The trial pill: `mb-6 ... px-4 py-1.5 text-sm font-semibold`, and the bare
    // `mb-6` spacer that replaces it when there is no notice.
    val trialPaddingX: Dp = 16.dp
    val trialPaddingY: Dp = 6.dp
    val trialBottomMargin: Dp = 24.dp

    /**
     * Tailwind `shadow-sm` — `0 1px 2px rgba(0,0,0,0.05)`. The trial pill is the
     * only surface in the app that wears it, so it lives here and not in
     * `Shadows`, which carries the plain `shadow` utility both layers of.
     */
    val trialShadow = CssShadow(y = 1.dp, blur = 2.dp, opacity = 0.05f)

    // Sections: `mb-6` each, `mb-2` under the chip row, `gap-2` inside it.
    val sectionBottomMargin: Dp = 24.dp
    val chipRowBottomMargin: Dp = 8.dp
    val chipRowGap: Dp = 8.dp

    // The level grid: `grid-cols-5 gap-2`, `rounded-2xl`, `text-2xl`.
    const val GRID_COLUMNS: Int = 5
    val gridGap: Dp = 8.dp
    val levelCornerRadius: Dp = 16.dp

    // The reward badges. Both: `gap-0.5 py-0.5 leading-none`.
    val badgeGap: Dp = 2.dp
    val badgePaddingY: Dp = 2.dp

    /** Jackpot: `-right-2 -top-2 ... px-2 text-sm ... ring-2 ring-white`. */
    val jackpotPaddingX: Dp = 8.dp
    val jackpotOffset: Dp = 8.dp
    val jackpotRing: Dp = 2.dp
    val jackpotFontSize: Dp = Typography.Size.sm

    /** Coin: `-right-1 -top-1 ... px-1.5 text-[11px] ... ring-1 ring-[#FFE08A]`. */
    val coinPaddingX: Dp = 6.dp
    val coinOffset: Dp = 4.dp
    val coinRing: Dp = 1.dp
    val coinFontSize: Dp = Typography.Size.xxs

    // Tailwind's own line boxes for the sizes this screen wears. `leading-none`
    // is authored only on the two badges; everywhere else the SIZE utility
    // carries a line height and the layout depends on it (a `text-lg` chip is
    // 28 + 8 + 8 = 44 dp tall without an explicit height anywhere).
    /** `text-lg` -> 28 px. */
    const val LG_RATIO: Float = TEXT_LG_LEADING

    /** `text-base` -> 24 px. */
    const val BASE_RATIO: Float = 24f / 16f

    /** `text-sm` -> 20 px. */
    const val SM_RATIO: Float = 20f / 14f

    /** `text-xl` -> 28 px. */
    const val XL_RATIO: Float = 28f / 20f

    /** `text-2xl` -> 32 px. */
    const val XXL_RATIO: Float = 32f / 24f

    /**
     * `audio.say(String(profile.balance), { rate: 0.85 })` — the ONE call site
     * in the app that overrides the 0.94 default rate. The pitch is left at its
     * 1.1 default.
     */
    const val LISTEN_RATE: Double = 0.85

    /**
     * The header row's real width: `max-w-md` inside the `min(480, vw - 32)`
     * card, minus the stage's `px-5`. Needed because `max-w-[55%]` is 55 % of
     * THIS, not of the window.
     */
    fun headerWidth(viewport: Dp): Dp =
        minOf(rowMaxWidth, Shell.cardWidth(viewport) - stagePaddingX * 2)

    /** `max-w-[55%]` on the player chip, resolved. */
    fun playerChipMaxWidth(viewport: Dp): Dp = headerWidth(viewport) * PLAYER_CHIP_FRACTION

    /**
     * A level cell's side: the column width a `grid-cols-5 gap-2` row yields
     * inside [headerWidth]. Pinned by a test at phone and tablet widths, because
     * the cells are square and a square cell that falls under the platform's
     * touch-target floor is invariant 6 broken by arithmetic nobody can see.
     *
     * It clears Android's 48 dp floor from 360 dp of window width up, which is
     * every shipping phone. At 320 dp it resolves to 43.2 dp — the PWA's own
     * cell at that width, recorded by `HubTapTargetTest` rather than papered
     * over: the honest fix is fewer columns on a narrow window, and that is a
     * layout change the web has not made.
     */
    fun levelCellSide(viewport: Dp): Dp =
        (headerWidth(viewport) - gridGap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
}

// --- The pure rules (host-tested in HubTest) --------------------------------

/**
 * « First clear is the jackpot (10) — a big gold star badge; repeats decay to a
 * small coin badge » (`App.tsx:226-231`). `jackpot == (points == 10)`, nothing
 * smarter, because the TSX is `pts === 10` and nothing smarter.
 */
data class HubLevelReward(val points: Int, val jackpot: Boolean)

/**
 * One level button, resolved.
 *
 * `reward == null` ⇒ no badge at all — a state no shipped row reaches any more
 * (every row promises the curve, training rows included), kept because this is a
 * pure function of `preview` and a level that promises nothing must not show a
 * « +0 ».
 */
data class HubLevelCell(val level: Int, val reward: HubLevelReward?, val label: String)

/**
 * INVARIANT 5, as a function. The whole level row for one catalog entry:
 * `1..levelCount`, with no condition.
 *
 * The only inputs are the catalog row and the preview function. There is no
 * profile, no entitlement and no progress parameter to consult — which is what
 * makes "every level is tappable" structural rather than a review note.
 */
fun hubLevelCells(meta: ExerciseMeta, preview: (ExerciseId, Int) -> Int): List<HubLevelCell> {
    if (meta.levelCount <= 0) return emptyList()
    return (1..meta.levelCount).map { level ->
        val points = preview(meta.id, level)
        HubLevelCell(
            level = level,
            reward = if (points > 0) HubLevelReward(points, jackpot = points == 10) else null,
            label = if (points > 0) {
                Copy.Hub.levelPaying(level, points)
            } else {
                Copy.Hub.levelTraining(level)
            },
        )
    }
}

/**
 * The hint chips after an exercise's name, in the TSX's order — `mode`, then
 * `spell` (where `mixed` swaps in `MIXED_HINT`), then `match`, then the free
 * `hint`.
 *
 * No row sets more than one today, but the markup allows all four and the port
 * keeps all four conditionals: a future row that wanted two would render both on
 * the web, and a screen that quietly dropped one would be a content bug nobody
 * could see in the data. The « · » prefix is added at the call site, from
 * `Copy.Hub.HINT_SEPARATOR`; the hint TEXT belongs to `core.levels` and carries
 * the typographic apostrophe, which `Copy.kt` deliberately never spells.
 */
fun hubHintChips(meta: ExerciseMeta): List<String> {
    val chips = mutableListOf<String>()
    meta.mode?.let { mode -> MODE_HINT[mode]?.let(chips::add) }
    meta.spell?.let { spell ->
        if (meta.mixed) chips.add(MIXED_HINT) else SPELL_HINT[spell]?.let(chips::add)
    }
    meta.match?.let { match -> MATCH_HINT[match]?.let(chips::add) }
    meta.hint?.let(chips::add)
    return chips
}

/**
 * `children.find((c) => c.id === activeId)?.name ?? ""` — the player chip's text.
 *
 * INVARIANT 10: this is a six-year-old's first name and it is DEVICE-LOCAL. It
 * is displayed and nothing else; no transport is reachable from this screen, and
 * `sync/Wire.kt` has no field that could carry it anyway.
 */
fun hubPlayerName(children: List<ChildProfile>, activeId: String?): String =
    children.firstOrNull { it.id == activeId }?.name ?: ""

/**
 * « Écouter » the score (`App.tsx:42-45`).
 *
 * fr-FR TTS reads the bare digits as the whole number ("42" → « quarante-deux »),
 * so a child ties the shape to the word — lowkey reading practice for big
 * numbers. No baked clip exists for an arbitrary number; `say` falls back to
 * speech synthesis, at rate 0.85 (the app's ONE override) and the default pitch.
 *
 * `unlock()` runs SYNCHRONOUSLY, before the coroutine is launched, exactly as the
 * TSX calls it before `void audio.say(...)`: warming the engine is the gesture's
 * job and must not be queued behind a dispatch.
 */
fun hubListenBalance(scope: CoroutineScope, audio: AudioEngine, balance: Int): Job {
    audio.unlock()
    return scope.launch { audio.say(balance.toString(), rate = HubMetrics.LISTEN_RATE) }
}

// --- The screen --------------------------------------------------------------

/**
 * The hub.
 *
 * @param profiles the roster. Read only: the balance and the reward preview come
 *   out of it, and `switchChild` is the one mutation, which changes who is
 *   playing and never what they own.
 * @param entitlement the licence VALUE, not the model — this screen reads
 *   `trialNotice` and must not be able to change a licence. The decision about
 *   whether a tap opens an exercise or the paywall belongs to `RootView`'s
 *   `open`, through `openOutcome`; there is no money check on this screen.
 * @param onOpen a level tap: `(exercise, level)`, forwarded verbatim.
 * @param onDashboard the star chip and the mascot door.
 * @param onPaywall the trial pill. It lands on the child-safe « demande à un
 *   grand » step, so the price still sits behind the parental gate (guideline
 *   1.3: no purchase may sit in front of a child).
 */
@Composable
fun HubView(
    profiles: ProfileStore,
    entitlement: Entitlement,
    audio: AudioEngine,
    reduceMotion: ReduceMotionSource,
    onOpen: (ExerciseId, Int) -> Unit,
    onDashboard: () -> Unit,
    onPaywall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The roster is the observable half of the store; reading it here is what
    // re-runs this body after a run banks its stars, so the balance chip and
    // every reward badge are current.
    val roster by profiles.rosterFlow.collectAsState()
    val profile = remember(roster) { profiles.profile }
    val viewport = LocalViewportWidth.current
    val scope = rememberCoroutineScope()
    val notice = trialNotice(entitlement)

    Box(
        modifier = modifier
            .fillMaxSize()
            // `overflow-hidden rounded-3xl`, then `background: STAGE` inside the
            // clip. The wash is OUTSIDE the scroller on purpose: painted on the
            // scrolling content it would be pinned to the content, so the top of
            // the screen would go bare and the gradient would slide away under
            // the finger.
            .clip(RoundedCornerShape(HubMetrics.cornerRadius))
            .drawBehind { drawRect(Palette.stage.brush(size)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pageScroll()
                .padding(
                    start = HubMetrics.stagePaddingX,
                    end = HubMetrics.stagePaddingX,
                    top = HubMetrics.stagePaddingTop,
                    bottom = HubMetrics.stagePaddingBottom,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HubHeader(
                name = hubPlayerName(roster.children, roster.activeId),
                balance = profile.balance,
                viewport = viewport,
                onSwitch = profiles::switchChild,
                onListen = { hubListenBalance(scope, audio, profile.balance) },
                onDashboard = onDashboard,
            )

            MascotDoor(
                config = profile.config,
                reduceMotion = reduceMotion,
                onDashboard = onDashboard,
            )

            HubTitle(viewport = viewport)

            // The trial countdown — parent-facing, and the only route to the
            // paywall that is not a blocked exercise tap. No notice ⇒ the bare
            // `mb-6` spacer, so the sections do not jump when the trial ends.
            if (notice != null) {
                TrialPill(notice = notice, onPaywall = onPaywall)
            } else {
                Spacer(Modifier.height(HubMetrics.trialBottomMargin))
            }

            // The catalog, in its authored order. Never sorted, never filtered.
            EXERCISES.forEach { meta ->
                HubSection(
                    meta = meta,
                    cells = hubLevelCells(meta) { id, level -> profiles.preview(id, level) },
                    onOpen = onOpen,
                )
            }
        }
    }
}

// --- The header ---------------------------------------------------------------

@Composable
private fun HubHeader(
    name: String,
    balance: Int,
    viewport: Dp,
    onSwitch: () -> Unit,
    onListen: () -> Unit,
    onDashboard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = HubMetrics.rowMaxWidth)
            .fillMaxWidth()
            .padding(bottom = HubMetrics.headerBottomMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipButton(
            label = Copy.Hub.SWITCH_PLAYER,
            onAct = onSwitch,
            modifier = Modifier.widthIn(max = HubMetrics.playerChipMaxWidth(viewport)),
            paddingX = HubMetrics.chipPaddingX,
        ) { fontScale ->
            Text(
                text = Copy.Hub.PLAYER_CHIP_PREFIX + name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = chipStyle(fontScale),
            )
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(HubMetrics.headerActionGap)) {
            ChipButton(
                label = Copy.Hub.LISTEN_BALANCE,
                onAct = onListen,
                paddingX = HubMetrics.listenPaddingX,
            ) { fontScale ->
                // The speaker is an emoji glyph, so it gets no `leading-none`
                // line box: a line box the exact height of the em crops an
                // emoji, whose ascent runs past it (the same reason
                // `GameFrame`'s star strip styles its glyphs by hand).
                Text(text = Copy.Hub.LISTEN_ICON, style = glyphStyle(Typography.Size.lg, fontScale))
            }

            ChipButton(
                label = Copy.Hub.OPEN_DASHBOARD,
                onAct = onDashboard,
                paddingX = HubMetrics.chipPaddingX,
            ) { fontScale ->
                Text(text = Copy.Hub.BALANCE_CHIP_PREFIX + balance, style = chipStyle(fontScale))
            }
        }
    }
}

private fun chipStyle(fontScale: Float): TextStyle = Typography.style(
    size = Typography.Size.lg,
    weight = Typography.Weight.black,
    color = Palette.ink.color,
    ratio = HubMetrics.LG_RATIO,
    fontScale = fontScale,
)

/**
 * A glyph whose line box is left to the font — see the note at the speaker chip.
 */
private fun glyphStyle(size: Dp, fontScale: Float): TextStyle = TextStyle(
    color = Palette.ink.color,
    fontSize = fixedSp(size.value, fontScale),
    fontFamily = Typography.appFamily,
)

/**
 * One frosted header chip: `rounded-full bg-white/80 px-N py-2 shadow
 * active:scale-95`.
 *
 * NAVIGATION, so it fires on the LIFT and only inside its own bounds — the TSX
 * uses `onClick` here, and leaving the hub by accident costs a child their
 * place. That is `touchDown`'s `onUp(inside)`, not a second gesture primitive;
 * the app has exactly one touch path.
 *
 * The press squish at DOWN is the deviation: CSS `active:scale-95` holds a
 * transform for as long as the finger is down, which Compose has no free
 * equivalent for. Rather than invent a second press mechanism, this reuses the
 * app's one audited press animation (`Anim.PRESS`, 1 → 0.9 → 1 over 130 ms,
 * driven by an `Animatable` read inside a `graphicsLayer` — invariant 2), which
 * reads the same to a six-year-old: the chip answers the finger at the instant
 * it lands.
 */
@Composable
private fun ChipButton(
    label: String,
    onAct: () -> Unit,
    paddingX: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (fontScale: Float) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberTileMotion()
    Box(
        modifier = modifier
            // One accessible element carrying the web's `aria-label`, plus a real
            // click ACTION so TalkBack's double tap activates it: `touchDown`
            // adds no semantics of its own, by design.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick {
                    onAct()
                    true
                }
            }
            .tileMotion(motion)
            .cssShadow(Shadows.tailwind, CircleShape)
            .background(Color.White.copy(alpha = Palette.White.o80), CircleShape)
            .touchDown(onUp = { inside -> if (inside) onAct() }) { motion.press() }
            .padding(horizontal = paddingX, vertical = HubMetrics.chipPaddingY),
        contentAlignment = Alignment.Center,
    ) {
        content(fontScale)
    }
}

// --- Mascot, title, trial pill ------------------------------------------------

/**
 * The mascot is a door to the dashboard. `mood = idle`, `size = 112`, and the
 * bob is gated by reduced motion inside `Ollie` (invariant 6's table).
 */
@Composable
private fun MascotDoor(
    config: MascotConfig,
    reduceMotion: ReduceMotionSource,
    onDashboard: () -> Unit,
) {
    val motion = rememberTileMotion()
    Box(
        modifier = Modifier
            // `mb-1` is a MARGIN, so it goes outermost: inside the touch
            // modifier it would quietly grow the hit area by 4 dp.
            .padding(bottom = HubMetrics.mascotBottomMargin)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = Copy.Hub.SEE_COMPANION
                onClick {
                    onDashboard()
                    true
                }
            }
            .tileMotion(motion)
            .touchDown(onUp = { inside -> if (inside) onDashboard() }) { motion.press() },
    ) {
        Ollie(
            config = config,
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            size = HubMetrics.mascotSize,
        )
    }
}

@Composable
private fun HubTitle(viewport: Dp) {
    val density = LocalDensity.current
    Text(
        text = Copy.Hub.TITLE,
        style = TextStyle(
            color = Palette.ink.color,
            fontSize = HubMetrics.titleSize.resolveSp(viewport, density),
            fontWeight = Typography.Weight.black,
            fontFamily = Typography.appFamily,
        ),
    )
    Text(
        text = Copy.Hub.SUBTITLE,
        modifier = Modifier.padding(
            top = HubMetrics.subtitleTopMargin,
            bottom = HubMetrics.subtitleBottomMargin,
        ),
        // A `<p>` with no weight class: the browser's normal, which is not in
        // `Typography.Weight` because nothing else in the app authors it.
        style = Typography.style(
            size = Typography.Size.base,
            weight = FontWeight.Normal,
            color = Palette.inkSoft.color,
            ratio = HubMetrics.BASE_RATIO,
            fontScale = density.fontScale,
        ),
    )
}

@Composable
private fun TrialPill(notice: String, onPaywall: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberTileMotion()
    Box(
        modifier = Modifier
            .padding(bottom = HubMetrics.trialBottomMargin)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = notice
                onClick {
                    onPaywall()
                    true
                }
            }
            .tileMotion(motion)
            .cssShadow(HubMetrics.trialShadow, CircleShape)
            .background(Color.White.copy(alpha = Palette.White.o70), CircleShape)
            .touchDown(onUp = { inside -> if (inside) onPaywall() }) { motion.press() }
            .padding(
                horizontal = HubMetrics.trialPaddingX,
                vertical = HubMetrics.trialPaddingY,
            ),
    ) {
        Text(
            text = notice,
            style = Typography.style(
                size = Typography.Size.sm,
                weight = Typography.Weight.semibold,
                color = Palette.inkQuiet.color,
                ratio = HubMetrics.SM_RATIO,
                fontScale = fontScale,
            ),
        )
    }
}

// --- One catalog row -----------------------------------------------------------

@Composable
private fun HubSection(
    meta: ExerciseMeta,
    cells: List<HubLevelCell>,
    onOpen: (ExerciseId, Int) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    Column(
        modifier = Modifier
            .widthIn(max = HubMetrics.rowMaxWidth)
            .fillMaxWidth()
            .padding(bottom = HubMetrics.sectionBottomMargin),
    ) {
        // `flex flex-wrap items-center gap-2` — icon, name, then the hints.
        HubWrapRow(
            spacing = HubMetrics.chipRowGap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = HubMetrics.chipRowBottomMargin),
        ) {
            // INVARIANT 7: the DRAWN icon, keyed by id. Never a glyph out of the
            // catalog row.
            ExerciseIcon(id = meta.id, size = 30.dp)
            Text(
                text = meta.name,
                style = Typography.style(
                    size = Typography.Size.xl,
                    weight = Typography.Weight.extrabold,
                    color = Palette.ink.color,
                    ratio = HubMetrics.XL_RATIO,
                    fontScale = fontScale,
                ),
            )
            hubHintChips(meta).forEach { hint ->
                Text(
                    text = Copy.Hub.HINT_SEPARATOR + hint,
                    style = Typography.style(
                        size = Typography.Size.sm,
                        weight = FontWeight.Normal,
                        color = Palette.inkFaint.color,
                        ratio = HubMetrics.SM_RATIO,
                        fontScale = fontScale,
                    ),
                )
            }
        }

        LevelGrid(id = meta.id, cells = cells, onOpen = onOpen)
    }
}

/**
 * `grid-cols-5 gap-2`.
 *
 * Rows of five, laid out by hand rather than with a `LazyVerticalGrid`: the hub
 * is already inside a vertical scroller, where a lazy grid has no bounded height
 * to measure against, and 17 short rows are not a list that needs recycling.
 * A short final row is padded with weightless holes so its cells keep the
 * column width, exactly as CSS grid does.
 */
@Composable
private fun LevelGrid(id: ExerciseId, cells: List<HubLevelCell>, onOpen: (ExerciseId, Int) -> Unit) {
    cells.chunked(HubMetrics.GRID_COLUMNS).forEachIndexed { rowIndex, rowCells ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (rowIndex == 0) 0.dp else HubMetrics.gridGap),
            horizontalArrangement = Arrangement.spacedBy(HubMetrics.gridGap),
        ) {
            rowCells.forEach { cell ->
                LevelButton(id = id, cell = cell, onOpen = onOpen, modifier = Modifier.weight(1f))
            }
            repeat(HubMetrics.GRID_COLUMNS - rowCells.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One level button. `aspect-square rounded-2xl bg-white/80 text-2xl shadow`.
 *
 * INVARIANT 5's tap handler: it forwards `(id, level)` and asks nothing. There
 * is no state that can make it inert, and no `enabled` parameter to grow one.
 *
 * The reward badge is `position: absolute` in the TSX — out of flow, sized to its
 * content and overhanging the corner — so it is a sibling in an outer box that
 * the square drives, offset outside the square's bounds. It takes no pointer
 * (nothing on it is hittable) and is hidden from the accessibility tree, exactly
 * as `aria-hidden` hides it: the button's own label already speaks the reward.
 */
@Composable
private fun LevelButton(
    id: ExerciseId,
    cell: HubLevelCell,
    onOpen: (ExerciseId, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberTileMotion()
    val shape = RoundedCornerShape(HubMetrics.levelCornerRadius)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectSquare()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = cell.label
                    onClick {
                        onOpen(id, cell.level)
                        true
                    }
                }
                .tileMotion(motion)
                .cssShadow(Shadows.tailwind, shape)
                .background(Color.White.copy(alpha = Palette.White.o80), shape)
                .touchDown(onUp = { inside -> if (inside) onOpen(id, cell.level) }) {
                    motion.press()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = cell.level.toString(),
                style = Typography.style(
                    size = Typography.Size.xxl,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    ratio = HubMetrics.XXL_RATIO,
                    fontScale = fontScale,
                ),
            )
        }
        cell.reward?.let { reward ->
            RewardBadge(
                reward = reward,
                fontScale = fontScale,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = if (reward.jackpot) HubMetrics.jackpotOffset else HubMetrics.coinOffset,
                        y = if (reward.jackpot) -HubMetrics.jackpotOffset else -HubMetrics.coinOffset,
                    ),
            )
        }
    }
}

/**
 * The corner badge: `+N ⭐` (jackpot, gold) or `+N 🪙` (a repeat, white).
 *
 * It DISPLAYS a number the store previewed and computes nothing (invariant 8).
 *
 * The web's `ring-N` sits outside the box; Compose's `border` draws inside it, so
 * the badge is a hair smaller than the browser's at the same padding. Recorded
 * rather than fudged with an extra 2 dp of padding, which would move the glyphs.
 */
@Composable
private fun RewardBadge(reward: HubLevelReward, fontScale: Float, modifier: Modifier = Modifier) {
    val size = if (reward.jackpot) HubMetrics.jackpotFontSize else HubMetrics.coinFontSize
    val ink = if (reward.jackpot) Palette.goldInk.color else Palette.coinInk.color
    Row(
        modifier = modifier
            // aria-hidden: the level button's own label already says
            // « gagne 10 étoiles ».
            .clearAndSetSemantics {}
            .cssShadow(Shadows.tailwind, CircleShape)
            .background(
                if (reward.jackpot) Palette.jackpot.color else Color.White,
                CircleShape,
            )
            .border(
                width = if (reward.jackpot) HubMetrics.jackpotRing else HubMetrics.coinRing,
                color = if (reward.jackpot) Color.White else Palette.coinRing.color,
                shape = CircleShape,
            )
            .padding(
                horizontal = if (reward.jackpot) {
                    HubMetrics.jackpotPaddingX
                } else {
                    HubMetrics.coinPaddingX
                },
                vertical = HubMetrics.badgePaddingY,
            ),
        horizontalArrangement = Arrangement.spacedBy(HubMetrics.badgeGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Copy.Hub.rewardBadge(reward.points),
            style = Typography.style(
                size = size,
                weight = Typography.Weight.black,
                color = ink,
                ratio = Typography.LineHeight.none,
                fontScale = fontScale,
            ),
        )
        // The star and the coin are emoji: no `leading-none` line box, or the
        // glyph is cropped.
        Text(
            text = if (reward.jackpot) Copy.Hub.JACKPOT_GLYPH else Copy.Hub.COIN_GLYPH,
            style = glyphStyle(size, fontScale).copy(color = ink),
        )
    }
}

// --- The wrapping chip row -----------------------------------------------------

/**
 * `flex flex-wrap items-center gap-2`, LEFT aligned — the CSS default
 * `justify-start`.
 *
 * `components/WrapRow` justify-CENTRES its lines, which is right for the end
 * buttons and wrong here, so only the placement differs: the line breaking is
 * `WrapRowLines.lines`, shared and already host-tested. Same split iOS made.
 */
@Composable
private fun HubWrapRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    lineSpacing: Dp = spacing,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        // Unbounded: wrapping never shrinks a child to make it fit, it moves the
        // child down.
        val placeables = measurables.map { it.measure(Constraints()) }
        val gap = spacing.roundToPx()
        val lineGap = lineSpacing.roundToPx()
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val rows = WrapRowLines.lines(placeables.map { IntSize(it.width, it.height) }, limit, gap)
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else rows.maxOfOrNull { it.width } ?: 0
        val height = rows.sumOf { it.height } + maxOf(0, rows.size - 1) * lineGap
        layout(width, height) {
            var y = 0
            for (row in rows) {
                var x = 0 // justify-start
                for (index in row.items) {
                    val placeable = placeables[index]
                    placeable.place(x, y + (row.height - placeable.height) / 2) // items-center
                    x += placeable.width + gap
                }
                y += row.height + lineGap
            }
        }
    }
}
