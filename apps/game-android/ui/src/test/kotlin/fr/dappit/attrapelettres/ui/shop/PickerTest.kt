package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.ProfileView
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.StarCounters
import fr.dappit.attrapelettres.core.persistence.blankSpeciesMap
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.screens.PickerVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// `shop/Picker.kt` against `src/shop/Picker.tsx`.
//
// ONE component, two variants. The shell renders `PickerVariant.FIRST_RUN` as
// the whole-app gate; the dashboard's « Changer de copain » renders
// `PickerVariant.SWITCH` with a cancel handler. Both are exercised here.
//
//   const CHOICES = [unicorn Licorne, cat Chat, fox Renard, rabbit Lapin,
//                    dragon Dragon];
//   const progress  = profile.species[species];
//   const isCurrent = profile.chosen && profile.current === species;
//   const grown     = progress.config.stage > 0 || progress.owned.length > 0;
//   aria-label={`Choisir ${name}`}
//   {grown ? `Niveau ${stage + 1}/${GROWTH_STAGES}` : "Tout neuf"}
// ---------------------------------------------------------------------------

/** The TSX's `CHOICES`, transcribed. Order is render order. */
private val tsChoices: List<Pair<Species, String>> = listOf(
    Species.UNICORN to "Licorne",
    Species.CAT to "Chat",
    Species.FOX to "Renard",
    Species.RABBIT to "Lapin",
    Species.DRAGON to "Dragon",
)

/** `GROWTH_STAGES` in `src/types.ts`. */
private const val TS_GROWTH_STAGES = 10

private fun profile(
    chosen: Boolean = true,
    current: Species = Species.UNICORN,
    stages: Map<Species, Int> = emptyMap(),
    owned: Map<Species, List<String>> = emptyMap(),
): ProfileView {
    val map = blankSpeciesMap().mapValues { (species, progress) ->
        progress.copy(
            config = progress.config.copy(stage = stages[species] ?: 0),
            owned = owned[species] ?: emptyList(),
        )
    }
    val persisted = PersistedProfile(
        chosen = chosen,
        current = current,
        currentRev = Rev.ZERO,
        species = map,
        stars = StarCounters(earned = emptyMap(), spent = emptyMap()),
        clears = emptyMap(),
    )
    return ProfileView(persisted, balance = 0, ledger = emptyMap())
}

private fun surface(
    variant: PickerVariant,
    canCancel: Boolean = false,
    chosen: Boolean = true,
    current: Species = Species.UNICORN,
    stages: Map<Species, Int> = emptyMap(),
    owned: Map<Species, List<String>> = emptyMap(),
): PickerSurface = pickerSurface(
    profile = profile(chosen, current, stages, owned),
    variant = variant,
    canCancel = canCancel,
)

class PickerChoicesTest {

    /**
     * INVARIANT 5, at its bluntest: five cards, always, in the authored order,
     * with no branch that could shorten the list.
     */
    @Test
    fun `all five friends are offered in the authored order`() {
        for (variant in PickerVariant.entries) {
            val cards = surface(variant).cards
            assertEquals(5, cards.size)
            assertEquals(tsChoices.map { it.first }, cards.map { it.species })
            assertEquals(tsChoices.map { it.second }, cards.map { it.name })
        }
    }

    /**
     * No gating: a brand-new profile at stage 0 with nothing owned still gets
     * every species, and none of them is marked unavailable in any way the
     * surface can express.
     */
    @Test
    fun `a brand-new profile can pick any species`() {
        val cards = surface(PickerVariant.FIRST_RUN, chosen = false).cards
        assertEquals(5, cards.size)
        assertTrue(cards.all { it.caption == "Tout neuf" })
        assertTrue(cards.none { it.isCurrent })
    }

    /** `aria-label={`Choisir ${name}`}`. */
    @Test
    fun `every card carries its accessibility label`() {
        assertEquals(
            listOf(
                "Choisir Licorne",
                "Choisir Chat",
                "Choisir Renard",
                "Choisir Lapin",
                "Choisir Dragon",
            ),
            surface(PickerVariant.SWITCH).cards.map { it.accessibilityLabel },
        )
    }

    /**
     * `grown = stage > 0 || owned.length > 0`, and the caption is
     * `Niveau ${stage + 1}/${GROWTH_STAGES}` — ONE-BASED, so a stage-3 mascot
     * reads « Niveau 4/10 ».
     */
    @Test
    fun `the caption is one-based against GROWTH_STAGES`() {
        val cards = surface(
            PickerVariant.SWITCH,
            stages = mapOf(Species.FOX to 3, Species.DRAGON to TS_GROWTH_STAGES - 1),
        ).cards
        assertEquals("Niveau 4/10", cards[2].caption)
        assertEquals("Niveau 10/10", cards[4].caption)
        assertEquals("Tout neuf", cards[0].caption)
        assertEquals(TS_GROWTH_STAGES, GROWTH_STAGES)
    }

    /**
     * « Owning an item counts as played even at stage 0 » — the `||` half of
     * `grown`, which a stage-only port would miss entirely.
     */
    @Test
    fun `owned items make a stage-zero mascot grown`() {
        val cards = surface(
            PickerVariant.SWITCH,
            owned = mapOf(Species.CAT to listOf("cat.accessory.bow")),
        ).cards
        assertEquals("Niveau 1/10", cards[1].caption)
        assertEquals("Tout neuf", cards[0].caption)
    }

    /**
     * `isCurrent = profile.chosen && profile.current === species` — TWO
     * conditions. Before a species has ever been chosen, `current` still points
     * at the default and no card may wear the badge.
     */
    @Test
    fun `current needs both chosen and a match`() {
        val picked = surface(PickerVariant.SWITCH, chosen = true, current = Species.RABBIT).cards
        assertEquals(listOf(false, false, false, true, false), picked.map { it.isCurrent })

        val unchosen =
            surface(PickerVariant.FIRST_RUN, chosen = false, current = Species.RABBIT).cards
        assertTrue(unchosen.none { it.isCurrent })
    }

    /**
     * « Each card shows that mascot at ITS real current look » — never the
     * active species' config. The fox card must carry the fox's stage even while
     * the unicorn is current.
     */
    @Test
    fun `each card carries its own species config`() {
        val cards = surface(
            PickerVariant.SWITCH,
            current = Species.UNICORN,
            stages = mapOf(Species.UNICORN to 1, Species.FOX to 7, Species.DRAGON to 4),
        ).cards
        assertEquals(tsChoices.map { it.first }, cards.map { it.config.species })
        assertEquals(listOf(1, 0, 7, 0, 4), cards.map { it.config.stage })
    }
}

class PickerVariantTest {

    /** `switching ? "🔄" : "🥚"`, and the two titles/subtitles, byte for byte. */
    @Test
    fun `first-run is the egg and the gate copy`() {
        val s = surface(PickerVariant.FIRST_RUN)
        assertEquals("🥚", s.glyph)
        assertEquals("Choisis ton copain", s.title)
        assertEquals("Il grandira avec toi.", s.subtitle)
    }

    @Test
    fun `switch is the cycles glyph and the reassuring copy`() {
        val s = surface(PickerVariant.SWITCH)
        assertEquals("🔄", s.glyph)
        assertEquals("Change de copain", s.title)
        // ASCII apostrophe U+0027 in « l'as », as authored.
        assertEquals("Tu retrouveras chacun comme tu l'as laissé.", s.subtitle)
    }

    /**
     * `{switching && onCancel && …}` is TWO conditions. The first-run gate has
     * nowhere to go back to and must never grow an exit — that is the one place
     * this screen could turn into a locked door.
     */
    @Test
    fun `only the switch variant with a cancel handler shows the way back`() {
        assertTrue(surface(PickerVariant.SWITCH, canCancel = true).showsBack)
        assertFalse(surface(PickerVariant.SWITCH, canCancel = false).showsBack)
        assertFalse(surface(PickerVariant.FIRST_RUN, canCancel = true).showsBack)
        assertFalse(surface(PickerVariant.FIRST_RUN, canCancel = false).showsBack)
    }

    /**
     * The variant changes four strings and one glyph — and nothing about what a
     * card shows.
     */
    @Test
    fun `the variant does not change any card`() {
        val stages = mapOf(Species.CAT to 2)
        val owned = mapOf(Species.DRAGON to listOf("dragon.accessory.cape"))
        val first = surface(
            PickerVariant.FIRST_RUN,
            chosen = true,
            current = Species.CAT,
            stages = stages,
            owned = owned,
        )
        val second = surface(
            PickerVariant.SWITCH,
            canCancel = true,
            chosen = true,
            current = Species.CAT,
            stages = stages,
            owned = owned,
        )
        assertEquals(first.cards, second.cards)
    }

    /** « ← Retour » with a plain « Retour » as the label TalkBack reads. */
    @Test
    fun `the back chip copy`() {
        assertEquals("← Retour", Copy.Picker.BACK_LABEL)
        assertEquals("Retour", Copy.Picker.BACK)
    }

    /** « Actuel ✓ » — U+2713, not an ASCII "v". */
    @Test
    fun `the current badge copy`() {
        assertEquals("Actuel ✓", Copy.Picker.CURRENT)
    }
}

class PickerMetricsTest {

    /** `min-h-[620px] … gap-5 rounded-3xl px-6 pb-10 pt-8`. */
    @Test
    fun `root metrics are the Tailwind classes`() {
        assertEquals(620f, PickerMetrics.MIN_HEIGHT.value)
        assertEquals(20f, PickerMetrics.ROOT_SPACING.value)
        assertEquals(24f, PickerMetrics.CORNER_RADIUS.value)
        assertEquals(24f, PickerMetrics.PADDING_X.value)
        assertEquals(32f, PickerMetrics.PADDING_TOP.value)
        assertEquals(40f, PickerMetrics.PADDING_BOTTOM.value)
        assertEquals(4f, PickerMetrics.SUBTITLE_BOTTOM_MARGIN.value)
    }

    /**
     * `clamp(44px,14vw,72px)` on the glyph and `clamp(26px,8vw,40px)` on the
     * title — asserted at viewports where each end of the clamp bites, so a
     * swapped min/max cannot pass.
     */
    @Test
    fun `fluid type matches the clamps`() {
        assertEquals(44f, PickerMetrics.GLYPH_SIZE.min)
        assertEquals(14f, PickerMetrics.GLYPH_SIZE.vw)
        assertEquals(72f, PickerMetrics.GLYPH_SIZE.max)
        assertEquals(26f, PickerMetrics.TITLE_SIZE.min)
        assertEquals(8f, PickerMetrics.TITLE_SIZE.vw)
        assertEquals(40f, PickerMetrics.TITLE_SIZE.max)

        assertEquals(44f, PickerMetrics.GLYPH_SIZE.resolve(200f))
        assertEquals(72f, PickerMetrics.GLYPH_SIZE.resolve(900f))
        assertEquals(26f, PickerMetrics.TITLE_SIZE.resolve(200f))
        assertEquals(40f, PickerMetrics.TITLE_SIZE.resolve(900f))
        // …and the middle term wins in between: 14 % of a 400 dp window.
        assertEquals(56f, PickerMetrics.GLYPH_SIZE.resolve(400f))
        assertEquals(32f, PickerMetrics.TITLE_SIZE.resolve(400f))
    }

    /**
     * INVARIANT 6: the card is a whole-row target — 84 dp of mascot with 20 dp
     * of padding either side, which is 124 dp, far past the 92 dp floor.
     */
    @Test
    fun `a card is a generous tap target`() {
        assertEquals(84f, PickerMetrics.MASCOT_SIZE.value)
        assertEquals(20f, PickerMetrics.CARD_PADDING.value)
        assertEquals(124f, PickerMetrics.CARD_MIN_HEIGHT.value)
        assertTrue(PickerMetrics.CARD_MIN_HEIGHT.value >= 92f)
    }

    /**
     * `gap-4` between cards, `gap-5` inside one, `3px` border,
     * `0 8px 18px rgba(0,0,0,0.10)`.
     */
    @Test
    fun `card metrics are the Tailwind classes`() {
        assertEquals(16f, PickerMetrics.CARD_SPACING.value)
        assertEquals(20f, PickerMetrics.CARD_SPACING_INNER.value)
        assertEquals(3f, PickerMetrics.CARD_BORDER_WIDTH.value)
        assertEquals(8f, PickerMetrics.CARD_SHADOW.y.value)
        assertEquals(18f, PickerMetrics.CARD_SHADOW.blur.value)
        assertEquals(0.10f, PickerMetrics.CARD_SHADOW.opacity)
    }

    /** The back chip: `px-4 py-2`, Tailwind `shadow` = two layers. */
    @Test
    fun `back chip metrics`() {
        assertEquals(16f, PickerMetrics.BACK_PADDING_X.value)
        assertEquals(8f, PickerMetrics.BACK_PADDING_Y.value)
        assertEquals(2, PickerMetrics.BACK_SHADOW.size)
        assertEquals(1f, PickerMetrics.BACK_SHADOW[0].y.value)
        assertEquals(3f, PickerMetrics.BACK_SHADOW[0].blur.value)
        assertEquals(0.1f, PickerMetrics.BACK_SHADOW[0].opacity)
    }

    /**
     * « Actuel ✓ »: `px-3 py-1`, `#E6F4E6` on `#2E7D32`; the current card's
     * border is `3px solid #66BB6A`.
     */
    @Test
    fun `badge and border colours`() {
        assertEquals(12f, PickerMetrics.BADGE_PADDING_X.value)
        assertEquals(4f, PickerMetrics.BADGE_PADDING_Y.value)
        assertEquals("#E6F4E6", Palette.currentBadge.hex)
        assertEquals("#2E7D32", Palette.currentBadgeInk.hex)
        assertEquals("#66BB6A", Palette.green.hex)
    }
}
