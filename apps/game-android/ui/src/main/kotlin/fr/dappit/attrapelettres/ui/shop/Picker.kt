package fr.dappit.attrapelettres.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.persistence.ProfileView
import fr.dappit.attrapelettres.core.persistence.blankProgress
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.pageScroll
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown
import fr.dappit.attrapelettres.ui.screens.PickerVariant

// ---------------------------------------------------------------------------
// `src/shop/Picker.tsx` — choose / switch mascot.
//
//   variant "first-run": the whole-app gate — « Choisis ton copain ».
//   variant "switch"   : reachable from the dashboard. Switching is
//                        NON-DESTRUCTIVE — each species keeps its own growth,
//                        look and items, so you can switch back anytime.
//
// Each card shows that mascot at ITS real current look (grown, dressed).
//
//   const CHOICES = [unicorn Licorne, cat Chat, fox Renard, rabbit Lapin,
//                    dragon Dragon];
//
//   function Choice({ species, name, onPick }) {
//     const progress  = profile.species[species];
//     const isCurrent = profile.chosen && profile.current === species;
//     const grown     = progress.config.stage > 0 || progress.owned.length > 0;
//     … <button aria-label={`Choisir ${name}`} onPointerDown={press}
//               onClick={onPick} border={isCurrent ? 3px #66BB6A : transparent}>
//          <Mascot config={progress.config} mood="idle" size={84} />
//          <span>{name}</span>
//          <span>{grown ? `Niveau ${stage+1}/${GROWTH_STAGES}` : "Tout neuf"}</span>
//          {isCurrent && <span>Actuel ✓</span>}
//   }
//
// ONE component, two variants — the TSX is one component and stays one. The
// variant changes four strings, one glyph and whether a « ← Retour » chip is
// offered; it changes nothing about what a card shows or what picking does.
//
// INVARIANT 5 — no gating. Every species is pickable, always. There is no
// "unlock the dragon at level 4" branch here and there must never be one.
//
// INVARIANT 6 — each card carries the TSX's `aria-label` (« Choisir Licorne »)
// and is a whole-row target: 84 dp of mascot plus 20 dp of padding on each side
// is 124 dp of button, far past the 92 dp floor.
//
// INVARIANT 1 — the squish is at pointer-DOWN and the pick is on the LIFT,
// because the TSX authors exactly that split (`onPointerDown={press}` /
// `onClick={onPick}`). This and the shop tile are the only two surfaces in the
// app that act on touch-up, and `TouchDown.kt` treats a scroll's consumption as
// the web's `pointercancel` so a flick over a card is not a pick.
// ---------------------------------------------------------------------------

// --- The surface (pure, host-tested) ------------------------------------------

/** One row. Everything a card shows, decided without a renderer. */
data class PickerCard(
    val species: Species,
    /** `CHOICES[i].name` — view copy; `core.domain.Species` carries no display name. */
    val name: String,
    /** `grown ? "Niveau {stage+1}/{GROWTH_STAGES}" : "Tout neuf"`. */
    val caption: String,
    /**
     * `profile.chosen && profile.current === species` — the green border and the
     * « Actuel ✓ » badge.
     */
    val isCurrent: Boolean,
    /** `aria-label={`Choisir ${name}`}`. */
    val accessibilityLabel: String,
    /**
     * THIS species' own look — « Each card shows that mascot at ITS real current
     * look ». Never the active species' config.
     */
    val config: MascotConfig,
)

/** The whole screen, as data. */
data class PickerSurface(
    /** `switching ? "🔄" : "🥚"`. */
    val glyph: String,
    val title: String,
    val subtitle: String,
    /**
     * `switching && onCancel` — the « ← Retour » chip. The first-run gate has
     * nowhere to go back to, so it offers no exit and must not grow one.
     */
    val showsBack: Boolean,
    /** The five friends, in the authored order. */
    val cards: List<PickerCard>,
)

/**
 * Fold the active child's profile into the screen.
 *
 * @param profile the ACTIVE child's flattened profile. Read for `chosen`,
 *   `current` and each species' own `config`/`owned` — never mutated.
 * @param variant which of the two screens this is.
 * @param canCancel did the caller pass an `onCancel`? (`switching && onCancel &&`
 *   in the TSX is TWO conditions, not one.)
 */
fun pickerSurface(
    profile: ProfileView,
    variant: PickerVariant,
    canCancel: Boolean,
): PickerSurface {
    val switching = variant == PickerVariant.SWITCH
    return PickerSurface(
        glyph = if (switching) Copy.Picker.SWITCH_GLYPH else Copy.Picker.EGG_GLYPH,
        title = if (switching) Copy.Picker.SWITCH_TITLE else Copy.Picker.FIRST_RUN_TITLE,
        subtitle = if (switching) Copy.Picker.SWITCH_SUBTITLE else Copy.Picker.FIRST_RUN_SUBTITLE,
        showsBack = switching && canCancel,
        cards = Copy.Picker.SPECIES_ORDER.map { species ->
            val progress = profile.species[species] ?: blankProgress(species)
            val name = Copy.Picker.name(species)
            // « A mascot that's been played shows real growth; a fresh one is a
            // stage-0 baby. » Owning an item counts as played even at stage 0 —
            // that is the `||` half, which a stage-only port silently drops.
            val grown = progress.config.stage > 0 || progress.owned.isNotEmpty()
            PickerCard(
                species = species,
                name = name,
                caption = if (grown) {
                    Copy.Picker.level(progress.config.stage + 1, GROWTH_STAGES)
                } else {
                    Copy.Picker.BRAND_NEW
                },
                isCurrent = profile.chosen && profile.current == species,
                accessibilityLabel = Copy.Picker.choose(name),
                config = progress.config,
            )
        },
    )
}

// --- Metrics (the TSX's Tailwind classes and inline styles, verbatim) ---------

object PickerMetrics {

    /** Root: `min-h-[620px] w-full flex-col items-center gap-5 rounded-3xl px-6 pb-10 pt-8`. */
    val MIN_HEIGHT: Dp = Shell.minimumScreenHeight
    val ROOT_SPACING: Dp = 20.dp // gap-5
    val CORNER_RADIUS: Dp = 24.dp // rounded-3xl
    val PADDING_X: Dp = 24.dp // px-6
    val PADDING_TOP: Dp = 32.dp // pt-8
    val PADDING_BOTTOM: Dp = 40.dp // pb-10

    /** `clamp(44px,14vw,72px)` — 🥚 / 🔄. */
    val GLYPH_SIZE = FluidSpec(min = 44f, vw = 14f, max = 72f)

    /** `clamp(26px,8vw,40px)` — the title. */
    val TITLE_SIZE = FluidSpec(min = 26f, vw = 8f, max = 40f)

    /** `mb-1` under the subtitle. */
    val SUBTITLE_BOTTOM_MARGIN: Dp = 4.dp

    /** Back chip: `rounded-full bg-white/80 px-4 py-2 text-base font-black shadow`. */
    val BACK_PADDING_X: Dp = 16.dp
    val BACK_PADDING_Y: Dp = 8.dp
    val BACK_SHADOW: List<CssShadow> = Shadows.tailwind

    /** The card column: `flex w-full flex-col gap-4`. */
    val CARD_SPACING: Dp = 16.dp

    /**
     * A card: `items-center gap-5 rounded-3xl p-5 text-left`,
     * `rgba(255,255,255,0.9)`, `0 8px 18px rgba(0,0,0,0.10)`,
     * `border: 3px solid …`.
     */
    val CARD_PADDING: Dp = 20.dp
    val CARD_SPACING_INNER: Dp = 20.dp
    val CARD_SHADOW = CssShadow(y = 8.dp, blur = 18.dp, opacity = 0.10f)
    val CARD_BORDER_WIDTH: Dp = 3.dp

    /** `<Mascot … size={84} />`. */
    val MASCOT_SIZE: Dp = 84.dp

    /** « Actuel ✓ »: `rounded-full px-3 py-1 text-sm font-black`. */
    val BADGE_PADDING_X: Dp = 12.dp
    val BADGE_PADDING_Y: Dp = 4.dp

    /**
     * The whole card's tap height: mascot plus padding either side. Recorded so
     * invariant 6's floor is asserted against the authored numbers rather than
     * against a measured pixel nobody can measure on the host.
     */
    val CARD_MIN_HEIGHT: Dp = MASCOT_SIZE + CARD_PADDING * 2
}

// --- The view -----------------------------------------------------------------

/**
 * `<Picker onDone onCancel variant />`.
 *
 * @param onCancel the « ← Retour » handler. NULLABLE rather than defaulted to an
 *   empty lambda, because `{switching && onCancel && …}` is two conditions in
 *   the TSX and the first-run gate must be able to say « I have no way back ».
 *   The root passes a handler only for [PickerVariant.SWITCH], which is exactly
 *   the shape this type expresses.
 * @param audio accepted for the shell's uniform screen signature and
 *   deliberately unused: the TSX plays nothing here, and a sound the web does
 *   not make is a divergence, not a polish.
 */
@Composable
fun PickerView(
    variant: PickerVariant,
    profiles: ProfileStore,
    @Suppress("UNUSED_PARAMETER") audio: AudioEngine,
    reduceMotion: ReduceMotionSource,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Subscribing to the flow is what makes the « Actuel ✓ » badge move the
    // instant `chooseSpecies` lands, without this screen owning any state.
    val roster by profiles.rosterFlow.collectAsState()
    val surface = remember(roster, variant, onCancel != null) {
        pickerSurface(profiles.profile, variant, canCancel = onCancel != null)
    }
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale

    Box(
        modifier
            .fillMaxWidth()
            // OUTSIDE the scroll (iOS D51): a wash inside scrolling content is
            // pinned to the content, not the window, so the top of the screen
            // would stay page-cream and the wash would slide away under the
            // finger.
            .clip(RoundedCornerShape(PickerMetrics.CORNER_RADIUS))
            .drawBehind { drawRect(Palette.stageAdult.brush(size)) },
    ) {
        // iOS D46. The stage is pinned to `minHeight` and the card list grows
        // with the roster: on a phone the FIFTH companion sat below the screen
        // with no way to reach it, so one animal could not be chosen at all. On
        // the web the document scrolls and the question never arises.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pageScroll()
                .defaultMinSize(minHeight = PickerMetrics.MIN_HEIGHT)
                .padding(
                    start = PickerMetrics.PADDING_X,
                    end = PickerMetrics.PADDING_X,
                    top = PickerMetrics.PADDING_TOP,
                    bottom = PickerMetrics.PADDING_BOTTOM,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PickerMetrics.ROOT_SPACING),
        ) {
            if (surface.showsBack && onCancel != null) {
                // `flex w-full` — the chip sits hard left in its own row.
                Row(Modifier.fillMaxWidth()) { BackChip(onCancel) }
            }

            BasicText(
                text = surface.glyph,
                style = Typography.style(
                    size = PickerMetrics.GLYPH_SIZE.resolve(viewport),
                    fontScale = fontScale,
                ),
                modifier = Modifier.clearAndSetSemantics { }, // aria-hidden
            )

            BasicText(
                text = surface.title,
                style = Typography.style(
                    size = PickerMetrics.TITLE_SIZE.resolve(viewport),
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ).copy(textAlign = TextAlign.Center),
            )

            BasicText(
                text = surface.subtitle,
                style = Typography.style(
                    size = Typography.Size.base,
                    // No weight class on the TSX's `<p>` — it inherits normal,
                    // and `Typography.style` defaults to black.
                    weight = FontWeight.Normal,
                    color = Palette.inkSoft.color,
                    fontScale = fontScale,
                ).copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(bottom = PickerMetrics.SUBTITLE_BOTTOM_MARGIN),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PickerMetrics.CARD_SPACING),
            ) {
                for (card in surface.cards) {
                    ChoiceRow(
                        card = card,
                        reduceMotion = reduceMotion,
                        // `const pick = (s) => { chooseSpecies(s); onDone(); }`
                        // — in that order, so the router's next screen already
                        // sees the chosen species.
                        onPick = {
                            profiles.chooseSpecies(card.species)
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Navigation, not gameplay: the TSX uses `onClick`, so this acts on the LIFT —
 * the same call `GameFrame`'s « ← Menu » makes, and for the same reason (A12).
 */
@Composable
private fun BackChip(onCancel: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BasicText(
        text = Copy.Picker.BACK_LABEL,
        style = Typography.style(
            size = Typography.Size.base,
            weight = Typography.Weight.black,
            color = Palette.ink.color,
            fontScale = fontScale,
        ),
        modifier = Modifier
            .cssShadow(PickerMetrics.BACK_SHADOW, CircleShape)
            .background(Color.White.copy(alpha = Palette.White.o80), CircleShape)
            .touchDown(onUp = { inside -> if (inside) onCancel() }) { }
            .padding(
                horizontal = PickerMetrics.BACK_PADDING_X,
                vertical = PickerMetrics.BACK_PADDING_Y,
            )
            .clearAndSetSemantics {
                contentDescription = Copy.Picker.BACK
                role = Role.Button
            },
    )
}

/** A single friend's row. */
@Composable
private fun ChoiceRow(
    card: PickerCard,
    reduceMotion: ReduceMotionSource,
    onPick: () -> Unit,
) {
    // `ShopItem.kt`'s vocabulary, not a second copy of it: the picker card and
    // the shop tile are the same authored squish (`shop/anim.ts`'s `press`,
    // 0.94 over 130 ms, reduced-motion GATED) on the same pointer contract
    // (squish at down, act on the lift).
    val motion = rememberShopTileMotion(reduceMotion)
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(PickerMetrics.CORNER_RADIUS)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PickerMetrics.CARD_MIN_HEIGHT)
            .shopMotion(motion)
            .cssShadow(PickerMetrics.CARD_SHADOW, shape)
            .background(Color.White.copy(alpha = Palette.White.o90), shape)
            // `border: 3px solid` — Tailwind's `box-sizing: border-box` puts it
            // INSIDE the box, so it is drawn on top of the fill and inset by
            // half its own width.
            .drawBehind {
                if (!card.isCurrent) return@drawBehind
                val stroke = PickerMetrics.CARD_BORDER_WIDTH.toPx()
                drawRoundRect(
                    color = Palette.green.color,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(PickerMetrics.CORNER_RADIUS.toPx()),
                    style = Stroke(width = stroke),
                )
            }
            // `onPointerDown={() => press(ref.current)}` is the squish only;
            // the pick itself is `onClick`, i.e. touch-UP inside.
            .shopTilePress(enabled = true, motion = motion, onTap = onPick)
            .padding(PickerMetrics.CARD_PADDING)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = card.accessibilityLabel
                onClick {
                    onPick()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PickerMetrics.CARD_SPACING_INNER),
    ) {
        Ollie(
            config = card.config,
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            size = PickerMetrics.MASCOT_SIZE,
        )

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = card.name,
                style = Typography.style(
                    size = Typography.Size.xxl,
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )
            BasicText(
                text = card.caption,
                style = Typography.style(
                    size = Typography.Size.sm,
                    weight = Typography.Weight.bold,
                    color = Palette.inkFaint.color,
                    fontScale = fontScale,
                ),
            )
        }

        if (card.isCurrent) {
            BasicText(
                text = Copy.Picker.CURRENT,
                style = Typography.style(
                    size = Typography.Size.sm,
                    weight = Typography.Weight.black,
                    color = Palette.currentBadgeInk.color,
                    fontScale = fontScale,
                ),
                modifier = Modifier
                    .background(Palette.currentBadge.color, CircleShape)
                    .padding(
                        horizontal = PickerMetrics.BADGE_PADDING_X,
                        vertical = PickerMetrics.BADGE_PADDING_Y,
                    ),
            )
        }
    }
}
