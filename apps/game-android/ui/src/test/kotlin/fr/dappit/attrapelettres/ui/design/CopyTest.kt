package fr.dappit.attrapelettres.ui.design

import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.levels.MATCH_HINT
import fr.dappit.attrapelettres.core.levels.MIXED_HINT
import fr.dappit.attrapelettres.core.levels.MODE_HINT
import fr.dappit.attrapelettres.core.levels.READ_IMAGE_PROMPT
import fr.dappit.attrapelettres.core.levels.SPELL_HINT
import fr.dappit.attrapelettres.core.licensing.UNLOCK_PRICE_EUR
import fr.dappit.attrapelettres.core.vo.SHOP_BOUGHT
import fr.dappit.attrapelettres.core.vo.SHOP_GREW
import fr.dappit.attrapelettres.core.vo.SHOP_NEED_MORE
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Copy tests exist for one reason: a one-character drift in a French string
// does not crash, log or fail anything. It changes what a six-year-old reads,
// and — where the same string is also spoken — silently re-keys a baked clip
// (`voKey` hashes UTF-16 code units exactly), so the line comes back in the
// robot text-to-speech voice mid-sentence.
//
// Every expected string here was copied out of the TSX, not out of Copy.kt.
// Port of `apps/game-ios/Tests/ALUITests/Design/CopyTests.swift`, plus the
// Android-only guard at the bottom: no string in :ui may promise family
// sharing, because Google Play Family Library does not share in-app purchases.
//
// Everything below runs as a plain JUnit test on the JVM. Nothing here builds
// a composition, and nothing touches android.* — `Dp` is a value class and is
// safe on the host.
// ---------------------------------------------------------------------------

private const val TYPOGRAPHIC_APOSTROPHE = '’'
private const val ASCII_APOSTROPHE = '\''

/**
 * Everything in [Copy] that is displayed as a whole string. Deliberately hand-
 * listed: there is no reflection over nested `object` members worth trusting,
 * and a hand list is also a checklist — a new string that nobody adds here gets
 * no hygiene coverage.
 */
private val allCopy: List<String> = listOf(
    // Hub
    Copy.Hub.TITLE, Copy.Hub.SUBTITLE, Copy.Hub.SWITCH_PLAYER, Copy.Hub.LISTEN_BALANCE,
    Copy.Hub.LISTEN_ICON, Copy.Hub.OPEN_DASHBOARD, Copy.Hub.SEE_COMPANION,
    Copy.Hub.JACKPOT_GLYPH, Copy.Hub.COIN_GLYPH,
    Copy.Hub.levelPaying(3, 10), Copy.Hub.levelPaying(1, 1),
    Copy.Hub.levelTraining(2), Copy.Hub.rewardBadge(10),
    // Dashboard
    Copy.Dashboard.BACK_TO_MENU, Copy.Dashboard.BACK_TO_MENU_LABEL, Copy.Dashboard.HEADING,
    Copy.Dashboard.balance(42), Copy.Dashboard.balance(1), Copy.Dashboard.BALANCE_GLYPH,
    Copy.Dashboard.BALANCE_CAPTION, Copy.Dashboard.GROWTH, Copy.Dashboard.GROWTH_BAR,
    Copy.Dashboard.SHOP_DOOR, Copy.Dashboard.SWITCH_COMPANION,
    // Frame / end of run
    Copy.Frame.BACK_TO_MENU, Copy.Frame.STAR, Copy.Frame.FUTURE_ROUND,
    Copy.Finished.CHEER_EMOJI, Copy.Finished.STAR,
    Copy.Finished.ALL_FOUND, Copy.Finished.ALL_SUCCEEDED, Copy.Finished.ALL_READ,
    Copy.EndButtons.MENU, Copy.EndButtons.NEXT,
    Copy.EarnBadge.label(7), Copy.EarnBadge.amount(7), Copy.EarnBadge.STAR,
    Copy.Tile.LISTEN_FALLBACK, Copy.Tile.LISTEN_GLYPH,
    // Roster
    Copy.WhoIsPlaying.HEADING, Copy.WhoIsPlaying.EDIT, Copy.WhoIsPlaying.EDIT_DONE,
    Copy.WhoIsPlaying.NEW_PROFILE, Copy.WhoIsPlaying.NEW_PROFILE_GLYPH,
    Copy.WhoIsPlaying.NEW_PROFILE_LABEL, Copy.WhoIsPlaying.ASK_NAME,
    Copy.WhoIsPlaying.NAME_PLACEHOLDER, Copy.WhoIsPlaying.GO, Copy.WhoIsPlaying.BACK,
    Copy.WhoIsPlaying.OWL_AVATAR, Copy.WhoIsPlaying.RENAME_GLYPH,
    Copy.WhoIsPlaying.DELETE_GLYPH,
    Copy.WhoIsPlaying.play("Léa"), Copy.WhoIsPlaying.rename("Léa"),
    Copy.WhoIsPlaying.delete("Léa"), Copy.WhoIsPlaying.renamePrompt("Léa"),
    Copy.WhoIsPlaying.deleteConfirm("Léa"),
    // Onboarding
    Copy.Onboarding.WAVE, Copy.Onboarding.TITLE, Copy.Onboarding.SCOPE,
    Copy.Onboarding.trialParagraph(14, "9,99 €"),
    Copy.Onboarding.pauseParagraph(14), Copy.Onboarding.NO_STORE_PARAGRAPH,
    Copy.Onboarding.startTrial(14), Copy.Onboarding.START,
    Copy.Onboarding.CONSENT_TITLE, Copy.Onboarding.CONSENT_BODY,
    // Parental gate
    Copy.ParentalGate.TITLE, Copy.ParentalGate.question(7, 4), Copy.ParentalGate.WRONG,
    Copy.ParentalGate.CANCEL, Copy.ParentalGate.CONFIRM, Copy.ParentalGate.PURCHASE_REASON,
    // Paywall
    Copy.Paywall.Child.MOON, Copy.Paywall.Child.TITLE, Copy.Paywall.Child.BODY,
    Copy.Paywall.Child.SEE_COMPANION, Copy.Paywall.Child.I_AM_AN_ADULT,
    Copy.Paywall.Parent.TITLE, Copy.Paywall.Parent.body("9,99 €"),
    Copy.Paywall.Parent.buy("9,99 €"), Copy.Paywall.Parent.BUSY,
    Copy.Paywall.Parent.RESTORE, Copy.Paywall.Parent.NO_STORE,
    Copy.Paywall.Parent.CONSENT_BODY, Copy.Paywall.Parent.BACK_TO_GAME,
    Copy.Paywall.Note.PURCHASE_FAILED, Copy.Paywall.Note.RESTORED,
    Copy.Paywall.Note.NOTHING_TO_RESTORE,
    // Picker
    Copy.Picker.name(Species.UNICORN), Copy.Picker.name(Species.CAT),
    Copy.Picker.name(Species.FOX), Copy.Picker.name(Species.RABBIT),
    Copy.Picker.name(Species.DRAGON), Copy.Picker.choose("Licorne"),
    Copy.Picker.EGG_GLYPH, Copy.Picker.SWITCH_GLYPH,
    Copy.Picker.FIRST_RUN_TITLE, Copy.Picker.SWITCH_TITLE,
    Copy.Picker.FIRST_RUN_SUBTITLE, Copy.Picker.SWITCH_SUBTITLE,
    Copy.Picker.level(3, 10), Copy.Picker.BRAND_NEW, Copy.Picker.CURRENT,
    Copy.Picker.BACK, Copy.Picker.BACK_LABEL,
    // Shop
    Copy.Shop.BACK, Copy.Shop.BACK_LABEL, Copy.Shop.wallet(42), Copy.Shop.WALLET_GLYPH,
    Copy.Shop.TAGLINE, Copy.Shop.WARDROBE_GLYPH, Copy.Shop.WARDROBE_TITLE,
    Copy.Shop.STORE_GLYPH, Copy.Shop.STORE_TITLE, Copy.Shop.STORE_EMPTY,
    Copy.Shop.ACCESSORY_LABEL,
    Copy.Shop.TryOn.title("Crinière corail"), Copy.Shop.TryOn.CANCEL,
    Copy.Shop.TryOn.CANCEL_GLYPH, Copy.Shop.TryOn.buy("Crinière corail", 22),
    Copy.Shop.TryOn.cannotAfford("Crinière corail"), Copy.Shop.TryOn.buyLabel(22),
    Copy.Shop.TryOn.notYetLabel(22),
    Copy.Shop.ItemState.EQUIPPED_REMOVABLE, Copy.Shop.ItemState.EQUIPPED,
    Copy.Shop.ItemState.OWNED, Copy.Shop.ItemState.lockedCost(22, 5),
    Copy.Shop.ItemState.tryingCost(22), Copy.Shop.ItemState.cost(22),
    Copy.Shop.ItemState.cannotAfford(22),
    Copy.Shop.ItemState.label("Crinière corail", Copy.Shop.ItemState.EQUIPPED),
    Copy.Shop.ItemState.REMOVE_GLYPH, Copy.Shop.ItemState.OWNED_GLYPH,
    Copy.Shop.ItemState.lockedBadge(5, 22), Copy.Shop.ItemState.priceBadge(22),
    Copy.Shop.DefaultLook.lockedBadge(5), Copy.Shop.DefaultLook.EQUIPPED_BADGE,
    Copy.Shop.DefaultLook.OWNED_BADGE, Copy.Shop.DefaultLook.lockedState(5),
    Copy.Shop.DefaultLook.EQUIPPED_STATE, Copy.Shop.DefaultLook.OWNED_STATE,
    Copy.Shop.Growth.TITLE, Copy.Shop.Growth.meter(3, 10), Copy.Shop.Growth.AT_MAX,
    Copy.Shop.Growth.grow(60), Copy.Shop.Growth.cannotAfford(60),
    Copy.Shop.Growth.AT_MAX_LABEL, Copy.Shop.Growth.growLabel(60),
    Copy.Shop.Growth.notYetLabel(60),
    Copy.Shop.savings(12, 22), Copy.Shop.SAVINGS_SPARKLE,
    // Exercise chrome
    Copy.Exercise.REPEAT_WORD, Copy.Exercise.REPEAT_INSTRUCTION, Copy.Exercise.REPLAY_WORD,
    Copy.Exercise.REPLAY_SOUND, Copy.Exercise.REPLAY_SYLLABLE,
    Copy.Exercise.LISTEN, Copy.Exercise.LISTEN_GLYPH, Copy.Exercise.listenWord("CHAT"),
    Copy.Exercise.listenTile("MA"), Copy.Exercise.letterTile("A"),
    Copy.Exercise.syllableTile("MA"), Copy.Exercise.soundTile("ou"),
    Copy.Exercise.imageTile("jupe"), Copy.Exercise.WORD_TO_COMPLETE,
    Copy.Exercise.remove("MA"), Copy.Exercise.syllableToComplete("V"),
    Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_EXACT),
    Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_EXTRA),
    Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_TWO),
    Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_EXACT),
    Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_EXTRA),
    Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_TWO),
    // The four engine consignes iOS had inlined. They carry U+0027 in
    // « s'écrit » / « s'écrire » and U+2014 in the twins line, which is exactly
    // what the apostrophe and dash checks below exist to hold still.
    Copy.Exercise.FIND_SOUND_HEADLINE, Copy.Exercise.READ_IMAGE_HEADLINE,
    Copy.Exercise.SPELL_SOUND_HEADLINE, Copy.Exercise.SOUND_TWINS_HEADLINE,
) + Copy.Shop.SLOT_LABEL.values.sorted()

/**
 * Gradle runs tests with the module directory as the working directory, so
 * `src/main/kotlin/...` resolves from there. The walk upwards is belt and
 * braces for an IDE runner rooted at the repo or at `apps/game-android`.
 */
private fun findUiMain(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        for (candidate in listOf(suffix, "ui/$suffix", "apps/game-android/ui/$suffix")) {
            val here = File(dir, candidate)
            if (here.isDirectory) return here
        }
        dir = dir.parentFile
    }
    return null
}

private val uiMainOrNull: File? = findUiMain()

class CopyTest {

    // -----------------------------------------------------------------------
    // The apostrophe
    // -----------------------------------------------------------------------

    /**
     * The shell's own copy uses ASCII `'` everywhere. Normalising one of these
     * to the typographic one is exactly the invisible edit a "clean up the
     * French" pass makes.
     */
    @Test
    fun `every apostrophe in the shell's copy is U+0027, never U+2019`() {
        for (s in allCopy) {
            assertFalse(s.contains(TYPOGRAPHIC_APOSTROPHE), "U+2019 found in « $s »")
        }

        // The strings that actually carry one, named so the check cannot pass
        // vacuously if the list above ever loses its apostrophe-bearing entries.
        val withApostrophes = listOf(
            Copy.Hub.levelTraining(2),
            Copy.WhoIsPlaying.ASK_NAME,
            Copy.WhoIsPlaying.GO,
            Copy.Onboarding.trialParagraph(14, "9,99 €"),
            Copy.Onboarding.CONSENT_BODY,
            Copy.ParentalGate.WRONG,
            Copy.Paywall.Child.BODY,
            Copy.Paywall.Parent.body("9,99 €"),
            Copy.Paywall.Parent.NO_STORE,
            Copy.Paywall.Note.PURCHASE_FAILED,
            Copy.Picker.SWITCH_SUBTITLE,
            Copy.Shop.TryOn.cannotAfford("Crinière corail"),
        )
        assertEquals(12, withApostrophes.size)
        for (s in withApostrophes) {
            assertTrue(s.contains(ASCII_APOSTROPHE), "expected an ASCII apostrophe in « $s »")
        }
    }

    /**
     * The other side of the same coin: :core's content DOES use the typographic
     * apostrophe, and both halves are baked into the clip bank. If a future
     * tidy-up normalised one direction or the other, this fails even though
     * every screen still looks right.
     */
    @Test
    fun `core's hub hints still carry the typographic apostrophe`() {
        val order = assertNotNull(MODE_HINT[SyllableMode.ORDER])
        assertEquals("Remets les syllabes dans l’ordre", order)
        assertTrue(order.contains(TYPOGRAPHIC_APOSTROPHE))

        val intruder = assertNotNull(MODE_HINT[SyllableMode.ORDER_DISTRACTOR])
        assertEquals("Range le mot… et évite l’intrus !", intruder)

        val script = assertNotNull(MATCH_HINT[LetterMatchKind.SCRIPT])
        assertEquals("Associe le script et l’attaché", script)
    }

    // -----------------------------------------------------------------------
    // The other scalars that look like ASCII and are not
    // -----------------------------------------------------------------------

    @Test
    fun `the parental gate multiplies with U+00D7, not the letter x`() {
        val q = Copy.ParentalGate.question(7, 4)
        assertEquals("Combien font 7 × 4 ?", q)
        assertTrue(q.contains('×'))
        assertFalse(q.lowercase().contains("x"))
    }

    @Test
    fun `the shop's separators are U+00B7 middle dots`() {
        assertEquals("Acheter · ⭐ 22", Copy.Shop.TryOn.buyLabel(22))
        assertEquals("⭐ 22 · pas encore", Copy.Shop.TryOn.notYetLabel(22))
        assertEquals("Grandir · ⭐ 60", Copy.Shop.Growth.growLabel(60))
        assertEquals("pas encore · ⭐ 60", Copy.Shop.Growth.notYetLabel(60))
        assertEquals("🌱 niv. 5 · ⭐ 22", Copy.Shop.ItemState.lockedBadge(5, 22))
        assertEquals("· ", Copy.Hub.HINT_SEPARATOR)
    }

    @Test
    fun `the adult copy uses em dashes and a one-character ellipsis`() {
        assertEquals("Débloquer — 9,99 €", Copy.Paywall.Parent.buy("9,99 €"))
        assertTrue(Copy.Paywall.Parent.CONSENT_BODY.contains('—'))
        assertTrue(Copy.Onboarding.NO_STORE_PARAGRAPH.contains('—'))
        assertTrue(
            Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_EXTRA).contains('—'),
        )
        assertTrue(
            Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_TWO).contains('—'),
        )

        // "…" is ONE character, not three dots — the busy button's whole label.
        assertEquals("…", Copy.Paywall.Parent.BUSY)
        assertEquals(1, Copy.Paywall.Parent.BUSY.length)
        assertEquals(
            "La bonne lettre… et la bonne écriture",
            Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_EXTRA),
        )
    }

    /**
     * `&nbsp;` before the exclamation mark — French typography, and the reason
     * « Demande à un grand ! » does not wrap onto its own line.
     */
    @Test
    fun `the paywall's child line keeps its no-break space`() {
        val body = Copy.Paywall.Child.BODY
        assertEquals(
            "Demande à un grand ! Tes étoiles et ta mascotte t'attendent.",
            body,
        )
        assertTrue(body.contains('\u00A0'), "the no-break space was normalised to a space")
        assertFalse(body.contains(" !"))  // an ordinary space would be the bug
    }

    /**
     * The roster's "new profile" card uses the FULLWIDTH plus, which is visibly
     * larger than "+" at the same font size — that is why it was chosen.
     */
    @Test
    fun `the new-profile glyph is U+FF0B`() {
        assertEquals("＋", Copy.WhoIsPlaying.NEW_PROFILE_GLYPH)
        assertTrue(Copy.WhoIsPlaying.NEW_PROFILE_GLYPH != "+")
    }

    // -----------------------------------------------------------------------
    // Hygiene
    // -----------------------------------------------------------------------

    @Test
    fun `no string is empty, and none carries a stray leading or trailing space`() {
        for (s in allCopy) {
            assertTrue(s.isNotEmpty(), "empty string in the copy list")
            assertEquals(s.trim(), s, "stray whitespace around « $s »")
            assertFalse(s.contains("  "), "double space in « $s »")
            assertFalse(s.contains("\n"), "newline in « $s »")
        }
        assertTrue(allCopy.size > 140, "the copy list shrank to ${allCopy.size}")
    }

    /**
     * Three strings DO end in a space, on purpose: they are glyph prefixes that
     * a number or a name is concatenated onto. Asserted separately so the
     * hygiene sweep above can stay strict — none of them is in [allCopy].
     */
    @Test
    fun `the three deliberate trailing spaces survive`() {
        assertEquals("👤 ", Copy.Hub.PLAYER_CHIP_PREFIX)
        assertEquals("⭐ ", Copy.Hub.BALANCE_CHIP_PREFIX)
        assertEquals("· ", Copy.Hub.HINT_SEPARATOR)
    }

    // -----------------------------------------------------------------------
    // Whole strings, verbatim
    // -----------------------------------------------------------------------

    @Test
    fun `hub`() {
        assertEquals("Attrape-Lettres", Copy.Hub.TITLE)
        assertEquals("Choisis un jeu et un niveau.", Copy.Hub.SUBTITLE)
        assertEquals("Changer de joueur", Copy.Hub.SWITCH_PLAYER)
        assertEquals("Écouter mes points", Copy.Hub.LISTEN_BALANCE)
        assertEquals("Mon copain et mes points", Copy.Hub.OPEN_DASHBOARD)
        assertEquals("Voir mon copain", Copy.Hub.SEE_COMPANION)
        assertEquals("+10", Copy.Hub.rewardBadge(10))
    }

    /**
     * The PWA pluralises on `n > 1`, so ZERO takes the singular. Ported as-is:
     * "gagne 1 étoile", "gagne 2 étoiles".
     */
    @Test
    fun `star pluralisation follows the JS rule, zero included`() {
        assertEquals("étoile", Copy.stars(0))
        assertEquals("étoile", Copy.stars(1))
        assertEquals("étoiles", Copy.stars(2))
        assertEquals("Niveau 4, gagne 1 étoile", Copy.Hub.levelPaying(4, 1))
        assertEquals("Niveau 4, gagne 10 étoiles", Copy.Hub.levelPaying(4, 10))
        assertEquals("Niveau 1, pour s'entraîner", Copy.Hub.levelTraining(1))
        assertEquals("Tu as 1 étoile", Copy.Dashboard.balance(1))
        assertEquals("Tu as 42 étoiles", Copy.Dashboard.balance(42))
        // EarnBadge is always plural in the source, even at +1. Not "fixed".
        assertEquals("Tu gagnes 1 étoiles", Copy.EarnBadge.label(1))
    }

    @Test
    fun `dashboard and end-of-run`() {
        assertEquals("Retour au menu", Copy.Dashboard.BACK_TO_MENU)
        assertEquals("← Menu", Copy.Dashboard.BACK_TO_MENU_LABEL)
        assertEquals("Mon copain", Copy.Dashboard.HEADING)
        assertEquals("étoiles à dépenser", Copy.Dashboard.BALANCE_CAPTION)
        assertEquals("🌱 Croissance", Copy.Dashboard.GROWTH)
        assertEquals("Croissance de ton copain", Copy.Dashboard.GROWTH_BAR)
        assertEquals("Boutique 🛍️", Copy.Dashboard.SHOP_DOOR)
        assertEquals("Changer de copain 🔄", Copy.Dashboard.SWITCH_COMPANION)

        assertEquals("← Menu", Copy.Frame.BACK_TO_MENU)
        assertEquals("🏠 Menu", Copy.EndButtons.MENU)
        assertEquals("🎉 Suivant", Copy.EndButtons.NEXT)
        assertEquals("Tu as tout trouvé !", Copy.Finished.ALL_FOUND)
        assertEquals("Tu as tout réussi !", Copy.Finished.ALL_SUCCEEDED)
        assertEquals("Tu as tout lu !", Copy.Finished.ALL_READ)
    }

    @Test
    fun `roster`() {
        assertEquals("Qui joue ?", Copy.WhoIsPlaying.HEADING)
        assertEquals("Modifier", Copy.WhoIsPlaying.EDIT)
        assertEquals("Terminé", Copy.WhoIsPlaying.EDIT_DONE)
        assertEquals("Comment tu t'appelles ?", Copy.WhoIsPlaying.ASK_NAME)
        assertEquals("Ton prénom", Copy.WhoIsPlaying.NAME_PLACEHOLDER)
        assertEquals(14, Copy.WhoIsPlaying.NAME_MAX_LENGTH)
        assertEquals("C'est parti ! 🎉", Copy.WhoIsPlaying.GO)
        assertEquals("Jouer avec Léa", Copy.WhoIsPlaying.play("Léa"))
        assertEquals("Renommer Léa", Copy.WhoIsPlaying.rename("Léa"))
        assertEquals("Supprimer Léa", Copy.WhoIsPlaying.delete("Léa"))
        assertEquals("Nouveau prénom pour Léa ?", Copy.WhoIsPlaying.renamePrompt("Léa"))
        assertEquals(
            "Supprimer le profil de Léa ? Tout sera perdu.",
            Copy.WhoIsPlaying.deleteConfirm("Léa"),
        )
    }

    /**
     * The disclosure has to be complete BEFORE the trial starts: duration, what
     * stops working, and the eventual charge. All three are asserted verbatim
     * rather than by keyword, because a paraphrase is what would ship.
     */
    @Test
    fun `onboarding — the trial disclosure, verbatim`() {
        assertEquals("Nous aussi, on est parents.", Copy.Onboarding.TITLE)
        assertEquals("sur vos appareils", Copy.Onboarding.SCOPE)
        assertEquals(
            "Attrape-Lettres est gratuit pendant 14 jours. Ensuite, un achat unique de " +
                "9,99 € débloque tout sur vos appareils, pour toujours. Pas d'abonnement, " +
                "pas de publicité, rien à acheter dans le jeu.",
            Copy.Onboarding.trialParagraph(14, "9,99 €"),
        )
        assertEquals(
            "Après 14 jours, les exercices se mettent en pause. Les progrès, les étoiles " +
                "et les mascottes sont gardés.",
            Copy.Onboarding.pauseParagraph(14),
        )
        assertEquals(
            "Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas " +
                "de compte, rien à acheter — et tout fonctionne sans connexion.",
            Copy.Onboarding.NO_STORE_PARAGRAPH,
        )
        assertEquals("Commencer les 14 jours", Copy.Onboarding.startTrial(14))
        assertEquals("Commencer", Copy.Onboarding.START)
    }

    /**
     * The three facts the disclosure must carry, checked as content rather than
     * as one long literal: the duration, the pause, and the price. A rewrite
     * that drops one of them still passes the verbatim test only by editing it,
     * which is the point where somebody has to think.
     */
    @Test
    fun `the trial paragraph discloses duration, charge and what stops`() {
        val trial = Copy.Onboarding.trialParagraph(14, "9,99 €")
        assertTrue(trial.contains("14 jours"), "the duration is missing")
        assertTrue(trial.contains("9,99 €"), "the charge is missing")
        assertTrue(trial.contains("achat unique"), "the one-off nature is missing")
        assertTrue(trial.contains("Pas d'abonnement"), "the no-subscription promise is missing")

        val pause = Copy.Onboarding.pauseParagraph(14)
        assertTrue(pause.contains("14 jours"))
        assertTrue(pause.contains("pause"), "what stops working is missing")
        assertTrue(pause.contains("gardés"), "what is kept is missing")
    }

    /**
     * The checkbox starts UNTICKED. Pre-ticked consent has been invalid since
     * CJEU Planet49, and the constant exists precisely so this can be asserted
     * without standing up a composition.
     */
    @Test
    fun `analytics consent is opt-in and says what is sent`() {
        assertFalse(Copy.Onboarding.CONSENT_INITIALLY_CHECKED)
        assertEquals("Nous aider à améliorer le jeu", Copy.Onboarding.CONSENT_TITLE)
        assertEquals(
            "On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le " +
                "prénom de votre enfant, jamais rien qui l'identifie. Vous pouvez changer " +
                "d'avis à tout moment.",
            Copy.Onboarding.CONSENT_BODY,
        )
        // Invariant 10, spelled out to the parent: it must promise no name.
        assertTrue(Copy.Onboarding.CONSENT_BODY.contains("Jamais le prénom"))
        assertTrue(Copy.Paywall.Parent.CONSENT_BODY.contains("Jamais le prénom"))
        // And it must say withdrawal is possible (GDPR Art. 7(3)).
        assertTrue(Copy.Onboarding.CONSENT_BODY.contains("changer d'avis"))
    }

    @Test
    fun `parental gate`() {
        assertEquals("Espace parents", Copy.ParentalGate.TITLE)
        assertEquals("Ce n'est pas le bon résultat.", Copy.ParentalGate.WRONG)
        assertEquals("Annuler", Copy.ParentalGate.CANCEL)
        assertEquals("Continuer", Copy.ParentalGate.CONFIRM)
        assertEquals(
            "Cette page contient un achat. Elle est réservée aux adultes.",
            Copy.ParentalGate.PURCHASE_REASON,
        )
    }

    @Test
    fun `paywall`() {
        assertEquals("Les jeux font une pause", Copy.Paywall.Child.TITLE)
        assertEquals("Voir ma mascotte", Copy.Paywall.Child.SEE_COMPANION)
        assertEquals("Je suis un adulte", Copy.Paywall.Child.I_AM_AN_ADULT)
        assertEquals("Débloquer Attrape-Lettres", Copy.Paywall.Parent.TITLE)
        assertEquals(
            "Un achat unique de 9,99 €. Pas d'abonnement, pas de publicité, rien d'autre " +
                "à acheter. Les progrès de vos enfants sont déjà enregistrés.",
            Copy.Paywall.Parent.body("9,99 €"),
        )
        assertEquals("Restaurer un achat", Copy.Paywall.Parent.RESTORE)
        assertEquals(
            "L'achat se fait depuis l'application installée sur le téléphone ou la tablette.",
            Copy.Paywall.Parent.NO_STORE,
        )
        assertEquals(
            "Nous aider à améliorer le jeu — exercice, niveau, réussi ou non. Jamais le " +
                "prénom de votre enfant.",
            Copy.Paywall.Parent.CONSENT_BODY,
        )
        assertEquals("Retour au jeu", Copy.Paywall.Parent.BACK_TO_GAME)
        assertEquals("L'achat n'a pas abouti. Rien n'a été débité.", Copy.Paywall.Note.PURCHASE_FAILED)
        assertEquals("Achat restauré.", Copy.Paywall.Note.RESTORED)
        assertEquals("Aucun achat trouvé sur ce compte.", Copy.Paywall.Note.NOTHING_TO_RESTORE)
    }

    /**
     * A failed purchase must never read as the child's fault, and the child step
     * must never show a price or a buy button (Kids Category 1.3, Play Families,
     * and invariants 3 and 11).
     */
    @Test
    fun `the child's paywall step names no money`() {
        val childStep = listOf(
            Copy.Paywall.Child.TITLE, Copy.Paywall.Child.BODY,
            Copy.Paywall.Child.SEE_COMPANION, Copy.Paywall.Child.I_AM_AN_ADULT,
        )
        for (s in childStep) {
            assertFalse(s.contains("€"), "a price reached the child's step: « $s »")
            assertFalse(s.lowercase().contains("achat"), "« $s »")
            assertFalse(s.lowercase().contains("acheter"), "« $s »")
            assertFalse(s.lowercase().contains("prix"), "« $s »")
        }
    }

    @Test
    fun `picker`() {
        assertEquals(
            listOf(Species.UNICORN, Species.CAT, Species.FOX, Species.RABBIT, Species.DRAGON),
            Copy.Picker.SPECIES_ORDER,
        )
        assertEquals("Licorne", Copy.Picker.name(Species.UNICORN))
        assertEquals("Chat", Copy.Picker.name(Species.CAT))
        assertEquals("Renard", Copy.Picker.name(Species.FOX))
        assertEquals("Lapin", Copy.Picker.name(Species.RABBIT))
        assertEquals("Dragon", Copy.Picker.name(Species.DRAGON))
        assertEquals("Choisir Licorne", Copy.Picker.choose("Licorne"))
        assertEquals("Choisis ton copain", Copy.Picker.FIRST_RUN_TITLE)
        assertEquals("Change de copain", Copy.Picker.SWITCH_TITLE)
        assertEquals("Il grandira avec toi.", Copy.Picker.FIRST_RUN_SUBTITLE)
        assertEquals("Tu retrouveras chacun comme tu l'as laissé.", Copy.Picker.SWITCH_SUBTITLE)
        assertEquals("Niveau 3/10", Copy.Picker.level(3, 10))
        assertEquals("Tout neuf", Copy.Picker.BRAND_NEW)
        assertEquals("Actuel ✓", Copy.Picker.CURRENT)

        // Every species in :core has a display name — a new one fails here.
        for (species in Species.entries) {
            assertTrue(Copy.Picker.SPECIES_ORDER.contains(species), "$species is not in the picker")
            assertTrue(Copy.Picker.name(species).isNotEmpty())
        }
        assertEquals(
            Species.entries.size,
            Species.entries.map { Copy.Picker.name(it) }.toSet().size,
            "two species share a French name",
        )
    }

    @Test
    fun `shop`() {
        assertEquals("Habille ton copain !", Copy.Shop.TAGLINE)
        assertEquals("Ton armoire", Copy.Shop.WARDROBE_TITLE)
        assertEquals("Le magasin", Copy.Shop.STORE_TITLE)
        assertEquals("Bientôt de nouveaux objets à découvrir ✨", Copy.Shop.STORE_EMPTY)
        assertEquals("42 points", Copy.Shop.wallet(42))
        assertEquals("12 étoiles sur 22", Copy.Shop.savings(12, 22))

        assertEquals("Corps", Copy.Shop.SLOT_LABEL["bodyColor"])
        assertEquals("Corne", Copy.Shop.SLOT_LABEL["hornColor"])
        assertEquals("Crinière", Copy.Shop.SLOT_LABEL["maneColor"])
        assertEquals("Queue", Copy.Shop.SLOT_LABEL["tailColor"])
        assertEquals("Ventre", Copy.Shop.SLOT_LABEL["bellyColor"])
        assertEquals("Bout de queue", Copy.Shop.SLOT_LABEL["tailTipColor"])
        assertEquals("Queue", Copy.Shop.SLOT_LABEL["tailStyle"])
        assertEquals("Corne", Copy.Shop.SLOT_LABEL["hornStyle"])
        assertEquals("Poil", Copy.Shop.SLOT_LABEL["hair"])
        assertEquals("Queue", Copy.Shop.SLOT_LABEL["tailSize"])
        assertEquals("Pelage", Copy.Shop.SLOT_LABEL["furPattern"])
        // The four the TSX map is MISSING, added here on purpose — see the note
        // on SLOT_LABEL. Without them the rabbit's ears and the dragon's wings
        // and crest are headed with their raw config key. `ShopCopyCoverageTest`
        // is the test that makes the gap impossible to reintroduce.
        assertEquals("Oreilles", Copy.Shop.SLOT_LABEL["innerEarColor"])
        assertEquals("Oreilles", Copy.Shop.SLOT_LABEL["earStyle"])
        assertEquals("Ailes", Copy.Shop.SLOT_LABEL["wingColor"])
        assertEquals("Crête", Copy.Shop.SLOT_LABEL["crestStyle"])
        assertEquals(15, Copy.Shop.SLOT_LABEL.size)
        assertEquals("Accessoires", Copy.Shop.ACCESSORY_LABEL)

        // `SLOT_LABEL[slot] ?? slot` in the TSX: an unknown slot shows its key.
        assertEquals("Accessoires", Copy.Shop.groupLabel(isAccessory = true, slot = "bodyColor"))
        assertEquals("Corps", Copy.Shop.groupLabel(isAccessory = false, slot = "bodyColor"))
        assertEquals("nopeColor", Copy.Shop.groupLabel(isAccessory = false, slot = "nopeColor"))

        assertEquals("Essayer Corne dorée", Copy.Shop.TryOn.title("Corne dorée"))
        assertEquals("Ne pas acheter", Copy.Shop.TryOn.CANCEL)
        assertEquals(
            "Acheter Corne dorée pour 22 étoiles",
            Copy.Shop.TryOn.buy("Corne dorée", 22),
        )
        assertEquals(
            "Pas encore assez d'étoiles pour Corne dorée",
            Copy.Shop.TryOn.cannotAfford("Corne dorée"),
        )

        assertEquals("équipé, appuie pour enlever", Copy.Shop.ItemState.EQUIPPED_REMOVABLE)
        assertEquals("coûte 22 points", Copy.Shop.ItemState.cost(22))
        assertEquals(
            "coûte 22 points, à débloquer au niveau 5",
            Copy.Shop.ItemState.lockedCost(22, 5),
        )
        assertEquals("coûte 22 points, pas encore assez", Copy.Shop.ItemState.cannotAfford(22))
        assertEquals("Corne dorée, équipé", Copy.Shop.ItemState.label("Corne dorée", "équipé"))

        assertEquals("Équipé ✓", Copy.Shop.DefaultLook.EQUIPPED_BADGE)
        assertEquals("À toi", Copy.Shop.DefaultLook.OWNED_BADGE)
        assertEquals("🌱 niv. 5", Copy.Shop.DefaultLook.lockedBadge(5))

        assertEquals("Faire grandir 🌱", Copy.Shop.Growth.TITLE)
        assertEquals("Croissance 3 sur 10", Copy.Shop.Growth.meter(3, 10))
        assertEquals("Niveau maximum atteint", Copy.Shop.Growth.AT_MAX)
        assertEquals("Faire grandir pour 60 points", Copy.Shop.Growth.grow(60))
        assertEquals(
            "Pas encore assez de points pour grandir, il en faut 60",
            Copy.Shop.Growth.cannotAfford(60),
        )
        assertEquals("Niveau max ✨", Copy.Shop.Growth.AT_MAX_LABEL)
    }

    @Test
    fun `exercise chrome`() {
        assertEquals("Répéter le mot", Copy.Exercise.REPEAT_WORD)
        assertEquals("Répéter la consigne", Copy.Exercise.REPEAT_INSTRUCTION)
        assertEquals("Réécouter le mot", Copy.Exercise.REPLAY_WORD)
        assertEquals("Réécouter le son", Copy.Exercise.REPLAY_SOUND)
        assertEquals("Réécouter la syllabe", Copy.Exercise.REPLAY_SYLLABLE)
        assertEquals("🔊 Écouter", Copy.Exercise.LISTEN)
        assertEquals("🔊 CHAT", Copy.Exercise.listenWord("CHAT"))
        assertEquals("Écouter MA", Copy.Exercise.listenTile("MA"))
        assertEquals("Lettre A", Copy.Exercise.letterTile("A"))
        assertEquals("Syllabe MA", Copy.Exercise.syllableTile("MA"))
        assertEquals("Son ou", Copy.Exercise.soundTile("ou"))
        assertEquals("Image : jupe", Copy.Exercise.imageTile("jupe"))
        assertEquals("Mot à compléter", Copy.Exercise.WORD_TO_COMPLETE)
        assertEquals("Retirer MA", Copy.Exercise.remove("MA"))
        assertEquals("Syllabe à compléter : V", Copy.Exercise.syllableToComplete("V"))
        assertEquals("Écouter", Copy.Tile.LISTEN_FALLBACK)
    }

    /** Invariant 6's floor, in the unit a `Modifier.sizeIn` will consume. */
    @Test
    fun `the tile's minimum side is 92 dp`() {
        assertEquals(92f, Copy.Tile.MINIMUM_SIDE.value)
    }

    /**
     * The plain headline and the "écritures mêlées" headline must differ for
     * every mode: on the mixed twins, matching the WRITING is the task, and
     * showing the plain line would describe a different game.
     */
    @Test
    fun `spell-syllable headlines, plain and mixed`() {
        assertEquals(
            "Complète le mot avec les lettres",
            Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_EXACT),
        )
        assertEquals(
            "Complète le mot — attention aux intrus",
            Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_EXTRA),
        )
        assertEquals(
            "Complète les deux syllabes",
            Copy.Exercise.spellHeadline(SpellSyllableMode.LETTERS_TWO),
        )

        assertEquals(
            "Trouve la bonne écriture",
            Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_EXACT),
        )
        assertEquals(
            "La bonne lettre… et la bonne écriture",
            Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_EXTRA),
        )
        assertEquals(
            "Deux syllabes — la bonne écriture",
            Copy.Exercise.spellHeadlineMixed(SpellSyllableMode.LETTERS_TWO),
        )

        for (mode in SpellSyllableMode.entries) {
            assertTrue(
                Copy.Exercise.spellHeadline(mode) != Copy.Exercise.spellHeadlineMixed(mode),
                "$mode shows the same headline plain and mixed",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Boundaries with other modules
    // -----------------------------------------------------------------------

    /**
     * Copy must not grow a second copy of anything :core already owns: the
     * exercise names, the hub hints, or the spoken lines. A duplicate would
     * drift, and the spoken half is hashed exactly.
     */
    @Test
    fun `Copy does not restate a core string`() {
        val owned = buildSet {
            addAll(EXERCISES.map { it.name })
            addAll(MODE_HINT.values)
            addAll(MATCH_HINT.values)
            addAll(SPELL_HINT.values)
            add(MIXED_HINT)
            add(READ_IMAGE_PROMPT)
            add(SHOP_BOUGHT)
            add(SHOP_GREW)
            add(SHOP_NEED_MORE)
        }

        // « Complète les deux syllabes » is genuinely the same sentence in two
        // roles — SPELL_HINT (the hub chip) and HEADLINE (the in-game line) —
        // in the PWA too. It is the ONLY overlap, and it is authored, not drift.
        val sanctioned = setOf("Complète les deux syllabes")

        for (s in allCopy) {
            if (s in owned) {
                assertTrue(
                    s in sanctioned,
                    "« $s » is :core's; reference it instead of restating it",
                )
            }
        }
    }

    /**
     * The euro amount is formatted with a comma, French-style, and the price
     * carries a space before the symbol. `UNLOCK_PRICE_EUR` is 9.99, so the
     * shipped fallback reads « 9,99 € ».
     */
    @Test
    fun `the fallback price label is French-formatted`() {
        assertEquals("4,99 €", Copy.fallbackPriceLabel(4.99))
        assertEquals("5,00 €", Copy.fallbackPriceLabel(5.0))
        assertEquals("11,99 €", Copy.fallbackPriceLabel(UNLOCK_PRICE_EUR))
        assertFalse(Copy.fallbackPriceLabel(4.99).contains("."))
    }

    // -----------------------------------------------------------------------
    // The Android-only guard
    // -----------------------------------------------------------------------

    /**
     * Google Play Family Library explicitly does NOT share in-app purchases,
     * ever: an unlock bought here restores per Google account and nowhere else.
     * Apple's Family Sharing does cover the household, so `Copy.swift` may — and
     * does — say « pour toute la famille ». That promise must never be copied
     * across, and the likeliest way it would is somebody porting the iOS
     * Onboarding screen wholesale.
     *
     * So this scans the whole of :ui, not just [allCopy]: comment lines are
     * skipped (this file and Copy.kt both discuss the rule in prose), and every
     * remaining line of source must be free of the word. One grep, and it will
     * outlive both of us.
     */
    @Test
    fun `no string in the ui module promises family sharing`() {
        for (s in allCopy) {
            assertFalse(
                s.lowercase().contains("famille"),
                "Play does not share purchases with a family: « $s »",
            )
        }

        val root = assertNotNull(
            uiMainOrNull,
            "the scan could not locate :ui's main sources from ${System.getProperty("user.dir")}",
        )
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "the scan is looking at the wrong tree: ${root.path}")

        for (file in sources) {
            file.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                val isComment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                if (!isComment) {
                    assertFalse(
                        line.lowercase().contains("famille"),
                        "${file.name}:${i + 1} promises family sharing, which Play does not " +
                            "deliver: $t",
                    )
                }
            }
        }
    }
}
