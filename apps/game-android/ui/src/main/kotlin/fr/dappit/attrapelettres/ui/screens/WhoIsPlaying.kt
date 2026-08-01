package fr.dappit.attrapelettres.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fr.dappit.attrapelettres.art.mascot.MascotRigView
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.liftedPill
import fr.dappit.attrapelettres.ui.components.pageScroll
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.KeyframeSpec
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ===========================================================================
// `src/components/WhoIsPlaying.tsx` — « Qui joue ? ».
// Worked example: `apps/game-ios/Sources/ALUI/Screens/WhoIsPlaying.swift`.
//
// Siblings share the device: pick who you are, or make a new profile. A child's
// avatar IS their current mascot (or the owl until they have chosen a species).
// Selecting sets the active player; creating drops the new child straight into
// the species picker (`chosen == false`, gate 4 in `shellGate`).
//
// -- INVARIANT 10 LIVES ON THIS SCREEN --------------------------------------
// `ChildProfile.name` is a six-year-old's first name. This screen DISPLAYS it
// in four places (the card, the two accessibility labels, and both dialog
// messages) and must never do anything else with it. It may not reach a
// transport, a log, a telemetry property, an analytics identifier or a crash
// report. The structural guards, which this file RELIES ON rather than
// re-implementing:
//
//   - `WireChild` (core/sync/Wire.kt) has NO name field, so the sync payload
//     cannot carry one — stripping is a property of the type, not of a
//     serialiser setting.
//   - `TelemetryProps` is eight optional Ints plus a closed `ExerciseId`, with
//     no `String` escape hatch, so `track(...)` has no parameter a name fits in.
//   - `Telemetry.reportError` attaches nothing from app state, deliberately.
//
// This file therefore imports no `Telemetry`, no `SyncClient` and no logger,
// and emits nothing. If a property is ever wanted here, that is a report, not
// an edit. `WhoIsPlayingTest` drives the whole roster flow with a recording
// sync transport and asserts the name appears in the display labels and in NO
// pushed byte.
//
// -- INVARIANT 5's sibling ---------------------------------------------------
// The roster is never gated either. Every child on the device is listed,
// unconditionally, in roster order — no filter, no sort, no cap.
//
// -- THE TWO BROWSER DIALOGS -------------------------------------------------
// Rename is `window.prompt(..., c.name)`, delete is `window.confirm(...)`.
// Neither exists natively. The messages are ported verbatim
// (`Copy.WhoIsPlaying.renamePrompt` / `deleteConfirm`); the presentation is a
// `androidx.compose.ui.window.Dialog` drawn with this app's own vocabulary.
//
// Material3's `AlertDialog` was the obvious alternative and was rejected: it
// brings `TextButton`, which is `Modifier.clickable` with a ripple, into the
// one module where A12 makes that API a build failure — not because the source
// scan would catch it (it would not, the call is inside the library) but
// because it would put two different touch models in one app for no gain. The
// dialog below is fifteen lines of Box and goes through `touchDown` like
// everything else.
//
// [RosterAction] is the whole decision, extracted so a host test can prove that
// CANCELLING calls nothing — `prompt` returning `null` is the one branch that
// is easy to get wrong and impossible to see.
//
// The dialog BUTTON TITLES do not exist in the PWA (they were browser chrome).
// « OK » / « Annuler » / « Supprimer » are chosen here and flagged for review;
// they are deliberately NOT in `Copy.kt`, which carries ported copy only, and
// the same call iOS made for the same reason.
// ===========================================================================

// --- The rename / delete decision (pure, host-tested) ------------------------

/** Which dialog is open, and for whom. */
data class RosterPrompt(val kind: Kind, val childId: String, val childName: String) {

    enum class Kind {
        /** `window.prompt("Nouveau prénom pour {name} ?", name)`. */
        RENAME,

        /** `window.confirm("Supprimer le profil de {name} ? Tout sera perdu.")`. */
        DELETE,
    }

    /** The exact string the browser dialog showed. */
    val message: String
        get() = when (kind) {
            Kind.RENAME -> Copy.WhoIsPlaying.renamePrompt(childName)
            Kind.DELETE -> Copy.WhoIsPlaying.deleteConfirm(childName)
        }
}

/** What a dismissed dialog asks the store to do. */
sealed interface RosterAction {

    /**
     * Cancelled. THE STORE IS NOT TOUCHED — `prompt` returning `null` means
     * `renameChild` is never called, and a declined `confirm` deletes nothing.
     */
    data object None : RosterAction

    data class Rename(val id: String, val name: String) : RosterAction

    data class Delete(val id: String) : RosterAction
}

/**
 * The dialog outcome -> the action. Pure.
 *
 * Note what is NOT here: emptiness. `window.prompt` returning `""` still calls
 * `renameChild(c.id, "")`, and the hook ignores it (`if (!trimmed) return`).
 * `ProfileStore.renameChild` does the same, so the empty case is deliberately
 * left to the store rather than second-guessed by a second implementation.
 */
fun rosterAction(prompt: RosterPrompt?, confirmed: Boolean, text: String): RosterAction {
    if (prompt == null || !confirmed) return RosterAction.None
    return when (prompt.kind) {
        RosterPrompt.Kind.RENAME -> RosterAction.Rename(prompt.childId, text)
        RosterPrompt.Kind.DELETE -> RosterAction.Delete(prompt.childId)
    }
}

/**
 * Runs an action through the single mutation choke point. Synchronous, like
 * every `ProfileStore` method (A3), and it calls the store's own trimming and
 * clamping rather than repeating them.
 */
fun applyRosterAction(action: RosterAction, store: ProfileStore) {
    when (action) {
        RosterAction.None -> Unit
        is RosterAction.Rename -> store.renameChild(action.id, action.name)
        is RosterAction.Delete -> store.deleteChild(action.id)
    }
}

// --- The three other decisions this screen makes ------------------------------

/**
 * `useState(children.length === 0)` — resolved from the roster on the FIRST
 * render and STICKY from then on. [pinned] is null until it has been resolved.
 *
 * Sticky matters: deleting the last child while in edit mode leaves the TSX's
 * `creating` at `false` and shows the grid with only the « Nouveau » card. A
 * plain `children.isEmpty()` would instead jump into the name form under the
 * parent's finger. Ported as-is.
 */
fun rosterCreating(pinned: Boolean?, rosterIsEmpty: Boolean): Boolean = pinned ?: rosterIsEmpty

/**
 * `aria-label={editing ? \`Renommer ${name}\` : \`Jouer avec ${name}\`}`.
 *
 * The child's name IS in this label, and that is correct — a screen reader runs
 * on the device. Invariant 10 is about what LEAVES it.
 */
fun childCardLabel(name: String, editing: Boolean): String =
    if (editing) Copy.WhoIsPlaying.rename(name) else Copy.WhoIsPlaying.play(name)

/** `disabled={!ok}` where `ok = name.trim().length > 0`. */
fun canCreateProfile(name: String): Boolean = name.trim().isNotEmpty()

/**
 * `maxLength={14}` on the name field.
 *
 * `take` counts UTF-16 code units, exactly like the DOM's `maxLength` and like
 * `ProfileStore.renameChild`'s `take(14)`. (The Swift port counts grapheme
 * clusters, which differs only where a grapheme would be split in half.)
 */
fun clampProfileName(input: String): String = input.take(Copy.WhoIsPlaying.NAME_MAX_LENGTH)

// --- The card's press ----------------------------------------------------------

/**
 * `src/shop/anim.ts`'s `press`, which `ChildCard` uses — and which is NOT
 * `Tile`'s.
 *
 * Two differences, both read off the TypeScript rather than assumed:
 *   - the scale bottoms out at 0.94, not `Tile`'s 0.9;
 *   - it IS reduced-motion gated (`anim.ts:32` returns early on
 *     `matchMedia("(prefers-reduced-motion: reduce)")`), where `Tile.tsx` has
 *     no `matchMedia` call at all. iOS D29's table lists "shop press/pop" under
 *     gated for exactly this reason.
 *
 * `Anim.kt` deliberately carries no shop press (it was written for the exercise
 * tiles and `MotionSurface` is that table's scope), so it lives here. If the
 * shop package lands its own, the two should be merged — reported, not
 * pre-empted, because the parallel port of `shop/` owns that file.
 */
object RosterPress {

    /** `[scale(1), scale(0.94), scale(1)]`, 130 ms, `ease-out`. */
    val SPEC = KeyframeSpec(
        values = listOf(1f, 0.94f, 1f),
        keyTimes = listOf(0f, 0.5f, 1f),
        durationMillis = 130,
        easing = Anim.EASE_OUT,
    )

    /** The gate, as one pure decision. `false` means "do not animate at all". */
    fun shouldAnimate(reduceMotion: ReduceMotionSource): Boolean = !reduceMotion.isReduced
}

/** One child card's squish. Read [scale] inside a `graphicsLayer` lambda only. */
@Stable
class RosterPressMotion(
    private val scope: CoroutineScope,
    private val reduceMotion: ReduceMotionSource,
) {

    val scale: Animatable<Float, AnimationVector1D> = Animatable(1f)

    private var job: Job? = null

    /** True when the setting is on: nothing will animate. */
    val gated: Boolean get() = !RosterPress.shouldAnimate(reduceMotion)

    /** Call synchronously inside the `touchDown` handler — invariant 1. */
    fun press() {
        if (gated) return
        job?.cancel()
        job = scope.launch {
            scale.snapTo(RosterPress.SPEC.start)
            scale.animateTo(RosterPress.SPEC.target, RosterPress.SPEC.toAnimationSpec())
        }
    }
}

@Composable
fun rememberRosterPress(reduceMotion: ReduceMotionSource): RosterPressMotion {
    val scope = rememberCoroutineScope()
    return remember(scope, reduceMotion) { RosterPressMotion(scope, reduceMotion) }
}

/** Apply the squish. A deferred read: it re-layers, it does not recompose. */
fun Modifier.rosterPress(motion: RosterPressMotion): Modifier = this.graphicsLayer {
    val s = motion.scale.value
    scaleX = s
    scaleY = s
}

// --- Authored metrics -----------------------------------------------------------

object WhoIsPlayingMetrics {

    /** `min-h-[620px] ... px-6 pb-10 pt-10`, `gap-6`, `rounded-3xl`. */
    val STAGE_PADDING_X: Dp = 24.dp
    val STAGE_PADDING_TOP: Dp = 40.dp
    val STAGE_PADDING_BOTTOM: Dp = 40.dp
    val STAGE_GAP: Dp = 24.dp
    val CORNER_RADIUS: Dp = 24.dp

    /** `max-w-md` on the grid, `max-w-sm` on the name form. */
    val GRID_MAX_WIDTH: Dp = 448.dp
    val FORM_MAX_WIDTH: Dp = 384.dp

    /** `grid-cols-2 gap-4`. */
    const val GRID_COLUMNS = 2
    val GRID_GAP: Dp = 16.dp

    /** `gap-5` inside the name form. */
    val FORM_GAP: Dp = 20.dp

    /** `rounded-3xl p-4` + `gap-2` inside a ChildCard; `h-24` avatar row. */
    val CARD_PADDING: Dp = 16.dp
    val CARD_GAP: Dp = 8.dp
    val AVATAR_ROW_HEIGHT: Dp = 96.dp
    val AVATAR_SIZE: Dp = 84.dp

    /** The owl stand-in before a species is chosen: `fontSize: size * 0.72`. */
    const val OWL_RATIO = 0.72f

    /** `0 8px 18px rgba(0,0,0,0.10)`. */
    val CARD_SHADOW = CssShadow(y = 8.dp, blur = 18.dp, opacity = 0.10f)

    /** `h-9 w-9` corner buttons at `-left-2 -top-2` / `-right-2 -top-2`. */
    val CORNER_BUTTON_SIDE: Dp = 36.dp
    val CORNER_BUTTON_OFFSET: Dp = 8.dp
    val CORNER_BUTTON_BORDER: Dp = 2.dp

    /** The dashed « Nouveau » card: `3px dashed #E4A15E`, `minHeight: 150`. */
    val NEW_CARD_BORDER: Dp = 3.dp
    val NEW_CARD_MIN_HEIGHT: Dp = 150.dp
    val NEW_CARD_GLYPH_SIZE: Dp = 46.dp
    val NEW_CARD_DASH_ON: Dp = 8.dp
    val NEW_CARD_DASH_OFF: Dp = 6.dp

    /** `clamp(48px,15vw,76px)` 👋 and `clamp(24px,7vw,36px)` headline. */
    val WAVE_SIZE = FluidSpec(48f, 15f, 76f)
    val ASK_NAME_SIZE = FluidSpec(24f, 7f, 36f)

    /** The name field: `rounded-3xl px-6 py-4 text-2xl`, `bg-white/95`. */
    val FIELD_PADDING_X: Dp = 24.dp
    val FIELD_PADDING_Y: Dp = 16.dp

    /**
     * « C'est parti ! 🎉 »: `px-8 py-4 text-2xl`, `0 8px 0 #43A047`,
     * `0 14px 24px rgba(0,0,0,0.2)`, `disabled:opacity-40`.
     */
    val GO_PADDING_X: Dp = 32.dp
    val GO_PADDING_Y: Dp = 16.dp
    val GO_LIP_DROP: Dp = 8.dp
    val GO_SOFT_SHADOW = CssShadow(y = 14.dp, blur = 24.dp, opacity = 0.2f)
    const val DISABLED_OPACITY = 0.4f

    /** `px-4 py-2` on the « Modifier » / « Terminé » toggle. */
    val TOGGLE_PADDING_X: Dp = 16.dp
    val TOGGLE_PADDING_Y: Dp = 8.dp

    /** The dialog card: not ported chrome, sized to the app's own vocabulary. */
    val DIALOG_PADDING: Dp = 24.dp
    val DIALOG_GAP: Dp = 16.dp
}

// --- The screen ------------------------------------------------------------------

/**
 * « Qui joue ? ».
 *
 * Needs no completion callback: `selectChild` / `createChild` move the roster,
 * `rosterFlow` recomposes the shell, and `shellGate` falls through by itself.
 *
 * @param profiles the single mutation choke point. Everything this screen does
 *   to a profile — create, select, rename, delete — goes through it.
 */
@Composable
fun WhoIsPlayingView(
    profiles: ProfileStore,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
) {
    val roster by profiles.rosterFlow.collectAsState()
    val children = roster.children

    // Null until pinned — see `rosterCreating`.
    var creatingPin: Boolean? by remember { mutableStateOf(null) }
    var editing by remember { mutableStateOf(false) }
    var prompt: RosterPrompt? by remember { mutableStateOf(null) }
    var renameText by remember { mutableStateOf("") }

    val creating = rosterCreating(creatingPin, children.isEmpty())
    LaunchedEffect(Unit) { if (creatingPin == null) creatingPin = children.isEmpty() }

    // `onCancel = children.length ? … : null` — an empty device has nowhere to
    // go back to, so there is no « Retour ». An anonymous function rather than a
    // brace-nested lambda: `else { { … } }` parses, but nobody should have to
    // decide that at a glance.
    val cancelCreate: (() -> Unit)? =
        if (children.isEmpty()) null else fun() { creatingPin = false }

    val finish: (Boolean) -> Unit = { confirmed ->
        applyRosterAction(rosterAction(prompt, confirmed, renameText), profiles)
        prompt = null
        renameText = ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Shell.minimumScreenHeight)
            .clip(RoundedCornerShape(WhoIsPlayingMetrics.CORNER_RADIUS))
            // The ADULT wash — the cream stops at 40 %, not the play surfaces'
            // 38 %. The two constants are load-bearing; do not unify them.
            .drawBehind { drawRect(Palette.stageAdult.brush(size)) }
            // The form branch autofocuses its name field, so a keyboard is up
            // from the moment this screen appears on an empty device — and the
            // stage cannot compress below 620 dp, so the inset would slide the
            // 👋 under the status bar. Scrolling absorbs it the way mobile
            // Safari does.
            //
            // Gated on `creating` and NOT hoisted to the whole screen: the grid
            // branch's cards are `touchDown` surfaces, and a scroller over them
            // would let a flick that lifts on a card cancel a legitimate press
            // (see PageScroll.kt's header). The two branches are mutually
            // exclusive, so the scroller never sees a card.
            .pageScroll(enabled = creating)
            .padding(
                start = WhoIsPlayingMetrics.STAGE_PADDING_X,
                end = WhoIsPlayingMetrics.STAGE_PADDING_X,
                top = WhoIsPlayingMetrics.STAGE_PADDING_TOP,
                bottom = WhoIsPlayingMetrics.STAGE_PADDING_BOTTOM,
            ),
        verticalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.STAGE_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (creating) {
            NewProfileForm(
                onCancel = cancelCreate,
                onSubmit = { name -> profiles.createChild(name) },
            )
        } else {
            RosterHeader(editing = editing, onToggle = { editing = !editing })
            RosterGrid(
                children = children,
                editing = editing,
                reduceMotion = reduceMotion,
                onPick = { child -> profiles.selectChild(child.id) },
                onRename = { child ->
                    // `window.prompt(msg, c.name)` — the field is SEEDED with
                    // the current name, which is why a parent fixing a typo does
                    // not retype it.
                    renameText = child.name
                    prompt = RosterPrompt(RosterPrompt.Kind.RENAME, child.id, child.name)
                },
                onDelete = { child ->
                    prompt = RosterPrompt(RosterPrompt.Kind.DELETE, child.id, child.name)
                },
                onNew = { creatingPin = true },
            )
        }
    }

    val open = prompt
    if (open != null) {
        RosterDialog(
            prompt = open,
            text = renameText,
            onTextChange = { renameText = clampProfileName(it) },
            onConfirm = { finish(true) },
            onCancel = { finish(false) },
        )
    }
}

// --- header ----------------------------------------------------------------------

@Composable
private fun RosterHeader(editing: Boolean, onToggle: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = Copy.WhoIsPlaying.HEADING,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.black,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
        )

        val label = if (editing) Copy.WhoIsPlaying.EDIT_DONE else Copy.WhoIsPlaying.EDIT
        Box(
            modifier = Modifier
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    onClick {
                        onToggle()
                        true
                    }
                }
                .cssShadow(Shadows.tailwind, CircleShape)
                .background(Color.White.copy(alpha = Palette.White.o80), CircleShape)
                .touchDown(onUp = { inside -> if (inside) onToggle() }) {}
                .padding(
                    horizontal = WhoIsPlayingMetrics.TOGGLE_PADDING_X,
                    vertical = WhoIsPlayingMetrics.TOGGLE_PADDING_Y,
                ),
        ) {
            BasicText(
                text = label,
                style = Typography.style(
                    size = Typography.Size.base,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )
        }
    }
}

// --- the grid ---------------------------------------------------------------------

/**
 * `grid-cols-2 gap-4`, as plain rows of two.
 *
 * Deliberately not a `LazyVerticalGrid`: a lazy grid is a scroller, and a
 * scroller over a `touchDown` card is what PageScroll.kt's header warns about.
 * A family's roster is two or three children plus one card; there is nothing to
 * virtualise and everything to lose.
 *
 * INVARIANT 5's sibling: [children] is iterated as it comes off the store — no
 * `filter`, no `sortedBy`, no `take`.
 */
@Composable
private fun RosterGrid(
    children: List<ChildProfile>,
    editing: Boolean,
    reduceMotion: ReduceMotionSource,
    onPick: (ChildProfile) -> Unit,
    onRename: (ChildProfile) -> Unit,
    onDelete: (ChildProfile) -> Unit,
    onNew: () -> Unit,
) {
    // `null` is the « Nouveau » card, which is the last cell of the grid.
    val cells: List<ChildProfile?> = children + listOf(null)
    Column(
        modifier = Modifier
            .widthIn(max = WhoIsPlayingMetrics.GRID_MAX_WIDTH)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.GRID_GAP),
    ) {
        for (row in cells.chunked(WhoIsPlayingMetrics.GRID_COLUMNS)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.GRID_GAP),
            ) {
                for (cell in row) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (cell == null) {
                            NewProfileCard(onNew = onNew)
                        } else {
                            ChildCard(
                                child = cell,
                                editing = editing,
                                reduceMotion = reduceMotion,
                                onPick = { onPick(cell) },
                                onRename = { onRename(cell) },
                                onDelete = { onDelete(cell) },
                            )
                        }
                    }
                }
                // A short last row still leaves its column empty, exactly as a
                // two-column CSS grid does.
                repeat(WhoIsPlayingMetrics.GRID_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// --- one child ---------------------------------------------------------------------

@Composable
private fun ChildCard(
    child: ChildProfile,
    editing: Boolean,
    reduceMotion: ReduceMotionSource,
    onPick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberRosterPress(reduceMotion)
    val label = childCardLabel(child.name, editing)
    val shape = RoundedCornerShape(WhoIsPlayingMetrics.CORNER_RADIUS)

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rosterPress(motion)
                .cssShadow(WhoIsPlayingMetrics.CARD_SHADOW, shape)
                .background(Color.White.copy(alpha = Palette.White.o92), shape)
                // `onPointerDown` fires the squish; `onClick` dispatches.
                // `touchDown`'s `onUp(inside)` IS the web's click test (pointer
                // lifted on the element), so the two halves land in the right
                // order without the tap being deferred to touch-up.
                .touchDown(
                    onUp = { inside ->
                        if (inside) {
                            if (editing) onRename() else onPick()
                        }
                    },
                ) {
                    // INVARIANT 1: the squish is fired here, synchronously
                    // inside the pointer-down handler, before anything commits.
                    motion.press()
                }
                .padding(WhoIsPlayingMetrics.CARD_PADDING)
                // One element, one label — the card is a button, not an avatar
                // followed by a name.
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = label
                    onClick {
                        if (editing) onRename() else onPick()
                        true
                    }
                },
            verticalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.CARD_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.height(WhoIsPlayingMetrics.AVATAR_ROW_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                RosterAvatar(
                    profile = child.profile,
                    size = WhoIsPlayingMetrics.AVATAR_SIZE,
                    reduceMotion = reduceMotion,
                )
            }

            BasicText(
                text = child.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Typography.style(
                    size = Typography.Size.xl,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )
        }

        if (editing) {
            CornerButton(
                glyph = Copy.WhoIsPlaying.RENAME_GLYPH,
                label = Copy.WhoIsPlaying.rename(child.name),
                glyphSize = Typography.Size.base,
                fill = Color.White,
                ink = Palette.ink.color,
                border = Palette.slotDashed.color,
                onTap = onRename,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = -WhoIsPlayingMetrics.CORNER_BUTTON_OFFSET,
                        y = -WhoIsPlayingMetrics.CORNER_BUTTON_OFFSET,
                    ),
            )
            CornerButton(
                glyph = Copy.WhoIsPlaying.DELETE_GLYPH,
                label = Copy.WhoIsPlaying.delete(child.name),
                glyphSize = Typography.Size.lg,
                fill = Palette.destructive.color,
                ink = Color.White,
                border = Color.White,
                onTap = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = WhoIsPlayingMetrics.CORNER_BUTTON_OFFSET,
                        y = -WhoIsPlayingMetrics.CORNER_BUTTON_OFFSET,
                    ),
            )
        }
    }
}

@Composable
private fun CornerButton(
    glyph: String,
    label: String,
    glyphSize: Dp,
    fill: Color,
    ink: Color,
    border: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .size(WhoIsPlayingMetrics.CORNER_BUTTON_SIDE)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick {
                    onTap()
                    true
                }
            }
            .cssShadow(Shadows.tailwind, CircleShape)
            .background(fill, CircleShape)
            .drawBehind {
                // `border: 2px solid …` — inset by half the stroke, because a
                // Compose stroke is centred on the path and a CSS border is not.
                val stroke = WhoIsPlayingMetrics.CORNER_BUTTON_BORDER.toPx()
                drawCircle(
                    color = border,
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
            }
            .touchDown(onUp = { inside -> if (inside) onTap() }) {},
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = Typography.style(
                size = glyphSize,
                weight = Typography.Weight.black,
                color = ink,
                fontScale = fontScale,
            ),
        )
    }
}

/**
 * `!chosen` -> 🦉 at `size * 0.72`; otherwise the child's CURRENT mascot.
 *
 * Reads `p.species[p.current].config`, not the flattened `ProfileView.config`:
 * the roster shows EVERY child, and only the active one has a `ProfileView`.
 */
@Composable
private fun RosterAvatar(
    profile: PersistedProfile,
    size: Dp,
    reduceMotion: ReduceMotionSource,
) {
    val fontScale = LocalDensity.current.fontScale
    val config = profile.species[profile.current]?.config
    if (profile.chosen && config != null) {
        MascotRigView(
            config = config,
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            size = size,
        )
    } else {
        BasicText(
            text = Copy.WhoIsPlaying.OWL_AVATAR,
            // `aria-hidden` — the card's own label already names the child.
            modifier = Modifier.clearAndSetSemantics {},
            style = Typography.style(
                size = size * WhoIsPlayingMetrics.OWL_RATIO,
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )
    }
}

// --- the « Nouveau » card ------------------------------------------------------------

@Composable
private fun NewProfileCard(onNew: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(WhoIsPlayingMetrics.CORNER_RADIUS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WhoIsPlayingMetrics.NEW_CARD_MIN_HEIGHT)
            .background(Color.White.copy(alpha = Palette.White.o55), shape)
            .dashedCardBorder(Palette.slotDashed.color)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = Copy.WhoIsPlaying.NEW_PROFILE
                onClick {
                    onNew()
                    true
                }
            }
            .touchDown(onUp = { inside -> if (inside) onNew() }) {}
            .padding(WhoIsPlayingMetrics.CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(
            WhoIsPlayingMetrics.CARD_GAP,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = Copy.WhoIsPlaying.NEW_PROFILE_GLYPH,
            style = Typography.style(
                size = WhoIsPlayingMetrics.NEW_CARD_GLYPH_SIZE,
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )
        BasicText(
            text = Copy.WhoIsPlaying.NEW_PROFILE_LABEL,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )
    }
}

// --- « Comment tu t'appelles ? » ---------------------------------------------------

@Composable
private fun NewProfileForm(onCancel: (() -> Unit)?, onSubmit: (String) -> Unit) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    var name by remember { mutableStateOf("") }
    val ok = canCreateProfile(name)
    val focus = remember { FocusRequester() }

    /**
     * `createChild(name)` — the UNTRIMMED value, even though the button is
     * gated on the trimmed one. `ProfileStore.createChild` trims and falls back
     * to « Joueur »; the asymmetry is in the TSX and is ported as-is.
     */
    val submit: () -> Unit = { if (canCreateProfile(name)) onSubmit(name) }

    Column(
        modifier = Modifier
            .widthIn(max = WhoIsPlayingMetrics.FORM_MAX_WIDTH)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.FORM_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = Copy.WhoIsPlaying.WAVE,
            modifier = Modifier.clearAndSetSemantics {},
            style = Typography.style(
                size = WhoIsPlayingMetrics.WAVE_SIZE.resolve(viewport),
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )

        BasicText(
            text = Copy.WhoIsPlaying.ASK_NAME,
            style = Typography.style(
                size = WhoIsPlayingMetrics.ASK_NAME_SIZE.resolve(viewport),
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ).copy(textAlign = TextAlign.Center),
        )

        NameField(
            value = name,
            onValueChange = { name = clampProfileName(it) },
            onSubmit = submit,
            focus = focus,
            fontScale = fontScale,
        )

        // `disabled:opacity-40`. The opacity is applied to the WHOLE button as
        // one group: fading the lifted pill's layers separately would let the
        // lip show through its own face and render one button as two.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = if (ok) 1f else WhoIsPlayingMetrics.DISABLED_OPACITY
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = Copy.WhoIsPlaying.GO
                    onClick {
                        submit()
                        true
                    }
                }
                .liftedPill(
                    fill = { SolidColor(Palette.green.color) },
                    lip = Palette.greenLip.color,
                    drop = WhoIsPlayingMetrics.GO_LIP_DROP,
                    soft = WhoIsPlayingMetrics.GO_SOFT_SHADOW,
                )
                // `disabled={!ok}` — the handler is not armed at all, so an
                // empty name cannot create a profile by a stray tap.
                .touchDown(enabled = ok, onUp = { inside -> if (inside) submit() }) {}
                .padding(
                    horizontal = WhoIsPlayingMetrics.GO_PADDING_X,
                    vertical = WhoIsPlayingMetrics.GO_PADDING_Y,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = Copy.WhoIsPlaying.GO,
                style = Typography.style(
                    size = Typography.Size.xxl,
                    weight = Typography.Weight.extrabold,
                    color = Color.White,
                    fontScale = fontScale,
                ),
            )
        }

        if (onCancel != null) {
            Box(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = Copy.WhoIsPlaying.BACK
                        onClick {
                            onCancel()
                            true
                        }
                    }
                    .touchDown(onUp = { inside -> if (inside) onCancel() }) {},
            ) {
                BasicText(
                    text = Copy.WhoIsPlaying.BACK,
                    style = Typography.style(
                        size = Typography.Size.base,
                        weight = Typography.Weight.bold,
                        color = Palette.inkSoft.color,
                        fontScale = fontScale,
                    ).copy(textDecoration = TextDecoration.Underline),
                )
            }
        }
    }

    // `autoFocus` on the name field.
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focus: FocusRequester,
    fontScale: Float,
) {
    val shape = RoundedCornerShape(WhoIsPlayingMetrics.CORNER_RADIUS)
    val style = Typography.style(
        size = Typography.Size.xxl,
        weight = Typography.Weight.black,
        color = Palette.ink.color,
        fontScale = fontScale,
    ).copy(textAlign = TextAlign.Center)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .cssShadow(Shadows.tailwind, shape)
            .background(Color.White.copy(alpha = Palette.White.o95), shape)
            .padding(
                horizontal = WhoIsPlayingMetrics.FIELD_PADDING_X,
                vertical = WhoIsPlayingMetrics.FIELD_PADDING_Y,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // `placeholder="Ton prénom"`. BasicTextField has no placeholder slot, so
        // it is a sibling that disappears on the first keystroke.
        if (value.isEmpty()) {
            BasicText(
                text = Copy.WhoIsPlaying.NAME_PLACEHOLDER,
                modifier = Modifier.clearAndSetSemantics {},
                style = style.copy(color = Palette.inkFaint.color),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                // `aria-label="Ton prénom"` — the same string as the placeholder.
                .semantics { contentDescription = Copy.WhoIsPlaying.NAME_PLACEHOLDER },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(Palette.ink.color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
    }
}

// --- the two dialogs -------------------------------------------------------------

/**
 * The rename prompt and the delete confirmation, in one composable because they
 * differ only in a text field and a button colour.
 *
 * The three button titles are NOT ported copy — the PWA never had them, they
 * were the browser's chrome — so they are spelled here and flagged for review
 * rather than added to `Copy.kt`, which carries the TSX's strings only.
 */
@Composable
private fun RosterDialog(
    prompt: RosterPrompt,
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(WhoIsPlayingMetrics.CORNER_RADIUS)
    val isRename = prompt.kind == RosterPrompt.Kind.RENAME
    val focus = remember { FocusRequester() }

    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.gateCard.color, shape)
                .padding(WhoIsPlayingMetrics.DIALOG_PADDING),
            verticalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.DIALOG_GAP),
        ) {
            BasicText(
                text = prompt.message,
                style = Typography.style(
                    size = Typography.Size.lg,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    ratio = Typography.LineHeight.snug,
                    fontScale = fontScale,
                ),
            )

            if (isRename) {
                NameField(
                    value = text,
                    onValueChange = onTextChange,
                    onSubmit = onConfirm,
                    focus = focus,
                    fontScale = fontScale,
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(WhoIsPlayingMetrics.DIALOG_GAP),
            ) {
                DialogButton(
                    label = RosterDialogChrome.CANCEL,
                    fill = Palette.adultSecondary.color,
                    ink = Palette.ink.color,
                    onTap = onCancel,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    label = if (isRename) {
                        RosterDialogChrome.CONFIRM
                    } else {
                        RosterDialogChrome.DELETE
                    },
                    fill = if (isRename) Palette.green.color else Palette.destructive.color,
                    ink = Color.White,
                    onTap = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Dialog button titles. NOT ported copy: the PWA's rename/delete dialogs were
 * `window.prompt` / `window.confirm`, whose buttons the browser drew. Chosen
 * here, flagged for review, and deliberately kept out of `Copy.kt` so that file
 * stays a transcription of the TSX. The same call iOS made.
 */
private object RosterDialogChrome {
    const val CONFIRM = "OK"
    const val CANCEL = "Annuler"
    const val DELETE = "Supprimer"
}

@Composable
private fun DialogButton(
    label: String,
    fill: Color,
    ink: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick {
                    onTap()
                    true
                }
            }
            .background(fill, CircleShape)
            .touchDown(onUp = { inside -> if (inside) onTap() }) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = Typography.style(
                size = Typography.Size.base,
                weight = Typography.Weight.black,
                color = ink,
                fontScale = fontScale,
            ),
        )
    }
}

// --- CSS's dashed border -----------------------------------------------------------

/**
 * `border: 3px dashed #E4A15E`, drawn inset so the stroke sits INSIDE the border
 * box the way a CSS border does (Compose strokes are centred on the path).
 *
 * Built as a `Path` and dashed with a `PathEffect` rather than passed to
 * `drawRoundRect(style = Stroke(pathEffect = …))`: a path effect on a primitive
 * shape has historically been ignored by the hardware-accelerated canvas, and a
 * silently-solid border would read as a design change rather than as a bug. The
 * same construction `AssembleView` uses for an empty slot.
 */
private fun Modifier.dashedCardBorder(color: Color): Modifier = this.drawBehind {
    val stroke = WhoIsPlayingMetrics.NEW_CARD_BORDER.toPx()
    val inset = stroke / 2f
    if (size.width <= stroke || size.height <= stroke) return@drawBehind
    val radius = (WhoIsPlayingMetrics.CORNER_RADIUS.toPx() - inset).coerceAtLeast(0f)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(
                    offset = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                ),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(
                    WhoIsPlayingMetrics.NEW_CARD_DASH_ON.toPx(),
                    WhoIsPlayingMetrics.NEW_CARD_DASH_OFF.toPx(),
                ),
                0f,
            ),
        ),
    )
}
