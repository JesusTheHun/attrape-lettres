package fr.dappit.attrapelettres.core.vo

import fr.dappit.attrapelettres.core.content.BASIC_SOUNDS
import fr.dappit.attrapelettres.core.content.GRID_CONSONANTS
import fr.dappit.attrapelettres.core.content.GRID_VOWELS
import fr.dappit.attrapelettres.core.content.LETTER_MATCH_ALPHABET
import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.content.SOUND_TARGETS
import fr.dappit.attrapelettres.core.content.SYLLABLE_WORDS
import fr.dappit.attrapelettres.core.content.TWIN_FAMILIES
import fr.dappit.attrapelettres.core.levels.LetterMatchPrompts
import fr.dappit.attrapelettres.core.levels.READ_IMAGE_PROMPT
import fr.dappit.attrapelettres.core.levels.findSoundPrompt
import fr.dappit.attrapelettres.core.levels.findSoundSuccess
import fr.dappit.attrapelettres.core.levels.gridPrompt
import fr.dappit.attrapelettres.core.levels.gridSuccess
import fr.dappit.attrapelettres.core.levels.gridSyllable
import fr.dappit.attrapelettres.core.levels.letterMatchSuccess
import fr.dappit.attrapelettres.core.levels.soundPrompt
import fr.dappit.attrapelettres.core.levels.soundSuccess
import fr.dappit.attrapelettres.core.levels.twinPrompt
import fr.dappit.attrapelettres.core.levels.twinSuccess
import fr.dappit.attrapelettres.core.mascot.CATALOG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// New coverage — `src/vo/utterances.ts` had no TypeScript suite; the manifest
// was only ever validated by baking it and listening. Here the bake itself is
// the oracle: see VoManifest.kt for what a hit in `vo-manifest.txt` proves.

class UtterancesBakeCoverageTest {

    /**
     * Guards the guard. An empty or unfound manifest would make every
     * assertion below vacuously true. The count is pinned EXACTLY: a re-bake
     * that grows or shrinks the bank must come back and update this number,
     * which is the point — the staged assets and this port move together.
     */
    @Test
    fun `the manifest is where the test thinks it is, and it is full`() {
        assertEquals(845, VoManifest.keys.size)
    }

    @Test
    fun `all 723 enumerated utterances have a staged clip`() {
        val all = enumerateUtterances()
        assertEquals(723, all.size)
        val missing = all.filter { voKey(it) !in VoManifest.keys }
        assertTrue(missing.isEmpty(), "not baked: ${missing.take(5)}")
    }

    /**
     * The strongest form of the proof: the staged bank is EXACTLY the union of
     * the sentence manifest and the preview catalog — nothing this port speaks
     * is missing, and nothing on disk is unreachable. A subset check would let
     * a silently shrunken enumeration pass; equality cannot.
     */
    @Test
    fun `the staged bank is exactly the two catalogs, no more, no less`() {
        val sentenceKeys = enumerateUtterances().map { voKey(it) }.toSet()
        val previewKeys = enumeratePreviewUtterances().map { voKey(it.text) }.toSet()

        // Disjoint by construction (sentences vs bare uppercase tiles), so the
        // union's size proves neither catalog leans on the other's clips.
        assertTrue(sentenceKeys.intersect(previewKeys).isEmpty())
        assertEquals(VoManifest.keys, sentenceKeys + previewKeys)
    }

    @Test
    fun `the manifest is deduped and holds no empty or untrimmed line`() {
        val all = enumerateUtterances()
        assertEquals(all.size, all.toSet().size)
        for (t in all) {
            assertTrue(t.isNotEmpty())
            assertEquals(t.trim(), t)
            assertFalse(t.contains("  "), t)
        }
    }

    /**
     * Enumeration order is not correctness — the lookup is by hash — but a
     * stable order keeps a re-bake diffable, so it is pinned at both ends.
     */
    @Test
    fun `enumeration order is stable and starts with the celebration lines`() {
        val all = enumerateUtterances()
        assertEquals("Bravo ! Tu as tout réussi !", all[0])
        assertEquals("Bravo ! Tu as tout trouvé !", all[1])
        assertEquals("Oh non ! On recommence.", all[2])
        assertEquals("Trouve la première lettre de Avion.", all[3])
        assertEquals("Oui ! A. Avion.", all[4])
        // The shop block is appended last, in first-appearance order of the
        // catalog's costs — 70 is the dragon's « Petit trésor », the last new
        // price the catalog introduces.
        assertEquals("Ça coûte 70 étoiles.", all[all.size - 2])
        assertEquals("Ça coûte 70 étoiles. Il te manque des étoiles.", all[all.size - 1])
        assertEquals(all, enumerateUtterances())
    }
}

class UtterancesShopTest {

    @Test
    fun `the three fixed shop lines are byte-exact and baked`() {
        assertEquals("C'est à toi !", SHOP_BOUGHT) // U+0027, not U+2019
        assertEquals("Tu as grandi !", SHOP_GREW)
        assertEquals("Il te manque des étoiles.", SHOP_NEED_MORE)
        // ON DISK, cross-checked against the real filenames.
        assertEquals("1lly6oa", voKey(SHOP_BOUGHT))
        assertEquals("1f8gzb0", voKey(SHOP_GREW))
        assertTrue(voKey(SHOP_BOUGHT) in VoManifest.keys)
        assertTrue(voKey(SHOP_GREW) in VoManifest.keys)
    }

    @Test
    fun `shopCostLine singularises at 1 and pluralises everywhere else`() {
        assertEquals("Ça coûte 1 étoile.", shopCostLine(1))
        // NB: French would write « 0 étoile », but the TS tests `cost === 1`
        // and nothing else. No catalog row costs 0 or 1, so this reading is
        // never spoken; ported as written rather than "fixed".
        assertEquals("Ça coûte 0 étoiles.", shopCostLine(0))
        assertEquals("Ça coûte 18 étoiles.", shopCostLine(18))
        assertEquals("Ça coûte 200 étoiles.", shopCostLine(200))
        assertEquals("1yra4yx", voKey(shopCostLine(18))) // ON DISK
        assertEquals("1xpd2gi", voKey(shopCostLine(200))) // ON DISK
    }

    /**
     * Both readings a try-on can trigger are baked: the plain price, and price
     * + "not enough yet" as ONE clip, because `say()` is passed the combined
     * string and lookup is by exact utterance.
     */
    @Test
    fun `every distinct catalog cost gets both a plain and a combined line`() {
        val all = enumerateUtterances().toSet()
        val costs = CATALOG.map { it.cost }.toSet()
        assertEquals(
            setOf(16, 18, 20, 22, 24, 32, 40, 45, 48, 50, 55, 60, 65, 70, 75, 95, 200),
            costs,
        )
        for (cost in costs) {
            val plain = shopCostLine(cost)
            assertTrue(plain in all, "missing $plain")
            assertTrue("$plain $SHOP_NEED_MORE" in all, "missing combined $cost")
            assertTrue(voKey(plain) in VoManifest.keys)
            assertTrue(voKey("$plain $SHOP_NEED_MORE") in VoManifest.keys)
        }
        // 17 costs × 2 readings and nothing else priced.
        assertEquals(costs.size * 2, all.count { it.startsWith("Ça coûte") })
    }

    /**
     * `SHOP_NEED_MORE` alone is deliberately NOT in the manifest — it is only
     * ever spoken glued to a price, so baking it alone would be a wasted clip.
     * Same for `shopCostLine(1)`: no row costs 1.
     */
    @Test
    fun `the manifest holds no clip nothing can speak`() {
        val all = enumerateUtterances().toSet()
        assertFalse(SHOP_NEED_MORE in all)
        assertFalse(shopCostLine(1) in all)
    }
}

class UtterancesExerciseTest {

    /**
     * Every per-round line comes from the `levels` declaration the exercise
     * itself calls, so the manifest cannot describe a sentence the game never
     * says. Re-authoring any of these inline is the failure mode.
     */
    @Test
    fun `prompts and success lines come from the level builders`() {
        val all = enumerateUtterances().toSet()
        assertTrue(READ_IMAGE_PROMPT in all)
        for (p in LetterMatchPrompts.all) assertTrue(p in all, p)
        for (base in LETTER_MATCH_ALPHABET) assertTrue(letterMatchSuccess(base) in all, base)
        for (t in SOUND_TARGETS.flatten()) {
            assertTrue(soundPrompt(t) in all)
            assertTrue(soundSuccess(t) in all)
        }
        for (s in BASIC_SOUNDS.flatten()) {
            assertTrue(s.sound in all)
            assertTrue(findSoundPrompt(s) in all)
            assertTrue(findSoundSuccess(s) in all)
        }
        for (f in TWIN_FAMILIES.flatten()) {
            assertTrue(f.sound in all)
            assertTrue(twinPrompt(f) in all)
            for (g in f.graphies) assertTrue(twinSuccess(g) in all)
        }
    }

    /**
     * The syllable grid is enumerated over the WHOLE tableau, not the level
     * pools, so a baked run covers it exactly once whatever a level draws.
     */
    @Test
    fun `the whole consonant times vowel tableau is covered, both drills`() {
        val all = enumerateUtterances().toSet()
        var cells = 0
        for (c in GRID_CONSONANTS) {
            for (v in GRID_VOWELS) {
                val s = gridSyllable(c, v)
                assertTrue(gridPrompt(s) in all, s.text)
                assertTrue(gridSuccess(s) in all, s.text)
                cells += 1
            }
        }
        assertEquals(GRID_CONSONANTS.size * GRID_VOWELS.size, cells)
        assertTrue(cells > 60, "only $cells grid cells — the tableau shrank?")
    }

    @Test
    fun `every word is spoken bare and in its success line, both exercise families`() {
        val all = enumerateUtterances().toSet()
        for (w in LETTER_WORDS) {
            assertTrue("Trouve la première lettre de ${w.word}." in all, w.word)
            assertTrue("Oui ! ${w.letter}. ${w.word}." in all, w.word)
            assertTrue(w.word in all, w.word) // auditioned on a picture tile
            assertTrue("Oui ! ${w.word}." in all, w.word)
        }
        for (w in SYLLABLE_WORDS) {
            assertTrue(w.word in all, w.word)
            assertTrue("Oui ! ${w.word}." in all, w.word)
        }
    }

    @Test
    fun `the three fixed sentence lines are present, byte for byte`() {
        val all = enumerateUtterances().toSet()
        assertTrue("Bravo ! Tu as tout réussi !" in all)
        assertTrue("Bravo ! Tu as tout trouvé !" in all)
        assertTrue("Oh non ! On recommence." in all)
        // ON DISK vectors, shared with VoKeyTest.
        assertEquals("upd4kb", voKey("Bravo ! Tu as tout réussi !"))
        assertEquals("68c1mf", voKey("Oh non ! On recommence."))
    }

    /**
     * Invariant 10, adjacent: the manifest is a shipping artefact but it is
     * also the only place a child's own words could leak into a bake. There
     * are none — every line is authored content.
     */
    @Test
    fun `the manifest holds only authored copy — no name, no device id`() {
        for (t in enumerateUtterances()) {
            assertFalse(t.contains("undefined"), t)
            assertFalse(t.contains("null"), t)
            assertFalse(t.contains("{"), t)
        }
    }
}
