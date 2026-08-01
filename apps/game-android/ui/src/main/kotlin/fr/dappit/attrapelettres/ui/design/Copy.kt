package fr.dappit.attrapelettres.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import java.util.Locale

// ---------------------------------------------------------------------------
// Every user-facing French string in the shell, byte for byte.
//
// Copied character for character out of the TSX in `apps/game-web/src/`,
// punctuation included. Several characters recur that are easy to normalise by
// accident — each is a different Unicode scalar from the ASCII lookalike a
// keyboard produces, and each is written as an escape here so a reviewer can
// see it:
//
//   U+0027 APOSTROPHE          every apostrophe in THIS file. The typographic
//                              one (U+2019) appears only in `Levels.kt`, i.e.
//                              in :core, and the two must not be unified:
//                              `voKey` hashes UTF-16 code units exactly, so
//                              swapping one silently re-keys a baked clip and
//                              the line comes back in the robot voice with no
//                              error anywhere.
//   U+00D7 MULTIPLICATION SIGN the parental gate's sum.
//   U+00B7 MIDDLE DOT          the hub's hint separator, the shop prices.
//   U+2014 EM DASH             the adult copy and two exercise headlines.
//   U+2026 ELLIPSIS            the busy button, one exercise headline. ONE
//                              character, never three dots.
//   U+00A0 NO-BREAK SPACE      the `&nbsp;` in the paywall's child line.
//   U+FF0B FULLWIDTH PLUS      the roster's "new profile" card.
//
// NOT here, on purpose:
//  - `core.domain.faceLabel` — the letter tile's accessibility label.
//  - `core.levels.*` — exercise names, MODE_HINT / MATCH_HINT / SPELL_HINT /
//    MIXED_HINT / READ_IMAGE_PROMPT, and every per-round prompt and success
//    line. Those are content, they are already tested there, and several carry
//    the U+2019 apostrophe this file deliberately never uses.
//  - `core.vo.*` and the spoken celebration lines. A displayed title and a
//    spoken line happen to read alike ("Tu as tout trouve !") but they are
//    different strings with different owners; the spoken one is baked audio.
//  - The mascot's own label — that lives in :art with the rig.
//
// THE ONE DELIBERATE DIVERGENCE FROM iOS: family sharing.
// Apple's Family Sharing covers a household on a non-consumable, so
// `Copy.swift` may say « pour toute la famille ». Google Play Family Library
// explicitly does NOT share in-app purchases, ever — an Android restore is per
// Google account. So this file never contains the word "famille", and
// `Onboarding.scope` is the web's « sur vos appareils », which is exactly what
// a per-account restore delivers: the devices signed into that account.
// `CopyTests.noStringPromisesFamilySharing` is the one-line grep that keeps it
// that way.
//
// Emoji appear in this file only where they are user-facing copy the web
// renders (the star chip, the shop doors). They are strings, not decoration.
// ---------------------------------------------------------------------------
object Copy {

    // -----------------------------------------------------------------------
    // Shared vocabulary
    // -----------------------------------------------------------------------

    /**
     * « étoile » / « étoiles ». The PWA pluralises on `n > 1`, so **zero takes
     * the singular** — French usage, and it is what the code does. Ported as-is.
     */
    fun stars(n: Int): String = if (n > 1) "étoiles" else "étoile"

    /**
     * The price shown when the store has no localised label:
     * `UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",")` + " €".
     *
     * `Locale.ROOT` is not cosmetic — a French default locale would already
     * format with a comma and the `replace` would be a no-op, while a
     * decimal-comma locale plus the replace would produce nothing at all. Fix
     * the locale, then do the substitution the web does.
     */
    fun fallbackPriceLabel(eur: Double): String =
        String.format(Locale.ROOT, "%.2f", eur).replace(".", ",") + " €"

    // -----------------------------------------------------------------------
    // Hub — `App.tsx`
    // -----------------------------------------------------------------------

    object Hub {
        const val TITLE = "Attrape-Lettres"
        const val SUBTITLE = "Choisis un jeu et un niveau."

        /** The player chip: this prefix + the child's name. Trailing space is load-bearing. */
        const val PLAYER_CHIP_PREFIX = "👤 "

        /** contentDescription. */
        const val SWITCH_PLAYER = "Changer de joueur"

        /** contentDescription. */
        const val LISTEN_BALANCE = "Écouter mes points"
        const val LISTEN_ICON = "🔊"

        /** The balance chip: this prefix + the number. Trailing space is load-bearing. */
        const val BALANCE_CHIP_PREFIX = "⭐ "

        /** contentDescription. */
        const val OPEN_DASHBOARD = "Mon copain et mes points"

        /** contentDescription. */
        const val SEE_COMPANION = "Voir mon copain"

        /**
         * « · » + a hint, appended after an exercise's name. The hint text
         * itself comes from `core.levels`. Trailing space is load-bearing.
         */
        const val HINT_SEPARATOR = "· "

        /** A level button that pays: « Niveau 3, gagne 10 étoiles ». */
        fun levelPaying(level: Int, points: Int): String =
            "Niveau $level, gagne $points ${stars(points)}"

        /**
         * A level that promises nothing. No shipped row does since training
         * rows started paying the curve; the string stays with its branch.
         */
        fun levelTraining(level: Int): String = "Niveau $level, pour s'entraîner"

        /** The reward badge on a level button: `+10` and ⭐, or `+2` and 🪙. */
        fun rewardBadge(points: Int): String = "+$points"
        const val JACKPOT_GLYPH = "⭐"
        const val COIN_GLYPH = "🪙"
    }

    // -----------------------------------------------------------------------
    // Dashboard — `components/Dashboard.tsx`
    // -----------------------------------------------------------------------

    object Dashboard {
        /** contentDescription. */
        const val BACK_TO_MENU = "Retour au menu"
        const val BACK_TO_MENU_LABEL = "← Menu"
        const val HEADING = "Mon copain"

        /** « Tu as 42 étoiles » — the big balance pill's contentDescription. */
        fun balance(n: Int): String = "Tu as $n ${stars(n)}"
        const val BALANCE_GLYPH = "⭐"
        const val BALANCE_CAPTION = "étoiles à dépenser"

        const val GROWTH = "🌱 Croissance"

        /** contentDescription. */
        const val GROWTH_BAR = "Croissance de ton copain"

        const val SHOP_DOOR = "Boutique 🛍️"
        const val SWITCH_COMPANION = "Changer de copain 🔄"
    }

    // -----------------------------------------------------------------------
    // Game frame + end of run
    // -----------------------------------------------------------------------

    object Frame {
        /**
         * `GameFrame`'s back button. Note it is NOT the Dashboard's — same
         * glyph, different component, and both are written out here so a
         * refactor cannot quietly merge them.
         */
        const val BACK_TO_MENU = "← Menu"
        const val STAR = "⭐"
        const val FUTURE_ROUND = "•"
    }

    object Finished {
        const val CHEER_EMOJI = "🤩"
        const val STAR = "⭐"

        // The three end-of-run titles the exercises pass in. Displayed text —
        // the spoken celebration is core.vo's and is a different string.
        const val ALL_FOUND = "Tu as tout trouvé !"
        const val ALL_SUCCEEDED = "Tu as tout réussi !"
        const val ALL_READ = "Tu as tout lu !"
    }

    object EndButtons {
        const val MENU = "🏠 Menu"
        const val NEXT = "🎉 Suivant"
    }

    object EarnBadge {
        /** Always plural in the source, even for `+1`. Ported as-is, not "fixed". */
        fun label(earned: Int): String = "Tu gagnes $earned étoiles"
        fun amount(earned: Int): String = "+$earned"
        const val STAR = "⭐"
    }

    object Tile {
        /**
         * `previewLabel ?? "Écouter"` — the fallback label on the per-tile
         * audition button.
         */
        const val LISTEN_FALLBACK = "Écouter"
        const val LISTEN_GLYPH = "🔊"

        /** Invariant 6: the tile's minimum side. */
        val MINIMUM_SIDE: Dp = 92.dp
    }

    // -----------------------------------------------------------------------
    // Roster — `components/WhoIsPlaying.tsx`
    // -----------------------------------------------------------------------

    object WhoIsPlaying {
        const val HEADING = "Qui joue ?"
        const val EDIT = "Modifier"
        const val EDIT_DONE = "Terminé"

        /** contentDescription. */
        const val NEW_PROFILE = "Nouveau profil"

        /** U+FF0B FULLWIDTH PLUS SIGN — visibly larger than "+" at the same size. */
        const val NEW_PROFILE_GLYPH = "＋"
        const val NEW_PROFILE_LABEL = "Nouveau"

        const val ASK_NAME = "Comment tu t'appelles ?"

        /** Both the field's contentDescription and its placeholder. */
        const val NAME_PLACEHOLDER = "Ton prénom"

        /** `maxLength={14}` on the name field. */
        const val NAME_MAX_LENGTH = 14
        const val GO = "C'est parti ! 🎉"
        const val BACK = "Retour"

        /** The avatar shown before a child has chosen a species. */
        const val OWL_AVATAR = "🦉"

        fun play(name: String): String = "Jouer avec $name"
        fun rename(name: String): String = "Renommer $name"
        fun delete(name: String): String = "Supprimer $name"
        const val RENAME_GLYPH = "✏️"
        const val DELETE_GLYPH = "✕"

        /** `window.prompt` — an AlertDialog with a text field natively. */
        fun renamePrompt(name: String): String = "Nouveau prénom pour $name ?"

        /** `window.confirm` — a destructive confirmation dialog natively. */
        fun deleteConfirm(name: String): String =
            "Supprimer le profil de $name ? Tout sera perdu."
    }

    // -----------------------------------------------------------------------
    // Onboarding — `components/Onboarding.tsx`
    // -----------------------------------------------------------------------

    object Onboarding {
        const val WAVE = "👋"
        const val TITLE = "Nous aussi, on est parents."

        /**
         * The scope of the unlock, and the one string this app may not copy from
         * iOS. Apple's Family Sharing covers the household on a non-consumable,
         * so `Copy.swift` says « pour toute la famille ». Google Play Family
         * Library explicitly excludes in-app purchases — a Play restore is per
         * Google account. « sur vos appareils » is the web's wording and is
         * exactly true here: the account's devices, all of them, forever.
         */
        const val SCOPE = "sur vos appareils"

        /**
         * « Attrape-Lettres est gratuit pendant 14 jours. Ensuite, un achat
         * unique de 9,99 € débloque tout sur vos appareils, pour toujours. Pas
         * d'abonnement, pas de publicité, rien à acheter dans le jeu. »
         *
         * The `<strong>` runs in the TSX are emphasis only; the sentence is one
         * string. JSX collapses the source's line breaks to single spaces, so
         * this is the rendered text.
         *
         * This is the App-Review-3.1.1 / Play-equivalent disclosure: duration,
         * what stops working (see [pauseParagraph]) and the eventual charge, all
         * BEFORE the trial starts.
         */
        fun trialParagraph(days: Int, price: String, scope: String = SCOPE): String =
            "Attrape-Lettres est gratuit pendant $days jours. Ensuite, un achat unique de " +
                "$price débloque tout $scope, pour toujours. Pas d'abonnement, pas de " +
                "publicité, rien à acheter dans le jeu."

        fun pauseParagraph(days: Int): String =
            "Après $days jours, les exercices se mettent en pause. Les progrès, les étoiles " +
                "et les mascottes sont gardés."

        /** Shown when there is no store at all. */
        const val NO_STORE_PARAGRAPH =
            "Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas de " +
                "compte, rien à acheter — et tout fonctionne sans connexion."

        fun startTrial(days: Int): String = "Commencer les $days jours"
        const val START = "Commencer"

        /**
         * The consent checkbox. It starts UNTICKED — pre-ticked consent has been
         * invalid since CJEU Planet49, and the screen that owns this string owns
         * that default too. `CONSENT_INITIALLY_CHECKED` exists so a test can
         * assert the default without a composition.
         */
        const val CONSENT_TITLE = "Nous aider à améliorer le jeu"
        const val CONSENT_BODY =
            "On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le " +
                "prénom de votre enfant, jamais rien qui l'identifie. Vous pouvez changer " +
                "d'avis à tout moment."

        /** Invariant of the consent flow, not a style choice. See [CONSENT_BODY]. */
        const val CONSENT_INITIALLY_CHECKED = false
    }

    // -----------------------------------------------------------------------
    // Parental gate — `components/ParentalGate.tsx`
    // -----------------------------------------------------------------------

    object ParentalGate {
        /** Both the dialog's contentDescription and its heading. */
        const val TITLE = "Espace parents"

        /** « Combien font 7 × 4 ? » — U+00D7, not the letter x. */
        fun question(a: Int, b: Int): String = "Combien font $a × $b ?"

        const val WRONG = "Ce n'est pas le bon résultat."
        const val CANCEL = "Annuler"
        const val CONFIRM = "Continuer"

        /** The reason the Paywall passes in. */
        const val PURCHASE_REASON =
            "Cette page contient un achat. Elle est réservée aux adultes."
    }

    // -----------------------------------------------------------------------
    // Paywall — `components/Paywall.tsx`
    // -----------------------------------------------------------------------

    object Paywall {

        /**
         * Step 1, the child sees this. No price, no buy button (Kids Category
         * 1.3 and Play's Families policy), and nothing that reads as a failure
         * (invariant 3).
         */
        object Child {
            const val MOON = "🌙"
            const val TITLE = "Les jeux font une pause"

            /** `&nbsp;` before the "!" — U+00A0, the French typographic space. */
            const val BODY = "Demande à un grand ! Tes étoiles et ta mascotte t'attendent."
            const val SEE_COMPANION = "Voir ma mascotte"
            const val I_AM_AN_ADULT = "Je suis un adulte"
        }

        /** Step 3, behind the gate. */
        object Parent {
            const val TITLE = "Débloquer Attrape-Lettres"

            fun body(price: String): String =
                "Un achat unique de $price. Pas d'abonnement, pas de publicité, rien " +
                    "d'autre à acheter. Les progrès de vos enfants sont déjà enregistrés."

            /** « Débloquer — 9,99 € ». U+2014 em dash. */
            fun buy(price: String): String = "Débloquer — $price"

            /** The in-flight label — U+2026, one character. */
            const val BUSY = "…"

            /**
             * Play, like the App Store, needs a restore control for a
             * non-consumable. Note the wording promises nothing about who else
             * gets it: a Play restore is per Google account, and
             * [Note.NOTHING_TO_RESTORE] says « sur ce compte » for that reason.
             */
            const val RESTORE = "Restaurer un achat"

            const val NO_STORE =
                "L'achat se fait depuis l'application installée sur le téléphone ou la tablette."

            /**
             * GDPR Art. 7(3): withdrawal must be as easy as consent, so the
             * toggle lives here too — shorter wording than Onboarding's.
             */
            const val CONSENT_BODY =
                "Nous aider à améliorer le jeu — exercice, niveau, réussi ou non. Jamais le " +
                    "prénom de votre enfant."

            const val BACK_TO_GAME = "Retour au jeu"
        }

        /**
         * Store outcomes. Never an error state: a failed purchase says what did
         * NOT happen, and the child's side is untouched (invariant 11).
         */
        object Note {
            const val PURCHASE_FAILED = "L'achat n'a pas abouti. Rien n'a été débité."
            const val RESTORED = "Achat restauré."
            const val NOTHING_TO_RESTORE = "Aucun achat trouvé sur ce compte."
        }
    }

    // -----------------------------------------------------------------------
    // Species picker — `shop/Picker.tsx`
    // -----------------------------------------------------------------------

    object Picker {
        /**
         * The five friends, in the order the picker lists them. The names are
         * view copy — `core.domain.Species` carries no display name.
         */
        val SPECIES_ORDER: List<Species> = listOf(
            Species.UNICORN,
            Species.CAT,
            Species.FOX,
            Species.RABBIT,
            Species.DRAGON,
        )

        // `when` over the enum with no `else`: a sixth species fails to compile
        // until it has a French name, the same trick invariant 7 uses for icons.
        fun name(species: Species): String = when (species) {
            Species.UNICORN -> "Licorne"
            Species.CAT -> "Chat"
            Species.FOX -> "Renard"
            Species.RABBIT -> "Lapin"
            Species.DRAGON -> "Dragon"
        }

        /** contentDescription. */
        fun choose(name: String): String = "Choisir $name"

        const val EGG_GLYPH = "🥚"
        const val SWITCH_GLYPH = "🔄"

        const val FIRST_RUN_TITLE = "Choisis ton copain"
        const val SWITCH_TITLE = "Change de copain"

        const val FIRST_RUN_SUBTITLE = "Il grandira avec toi."
        const val SWITCH_SUBTITLE = "Tu retrouveras chacun comme tu l'as laissé."

        fun level(stage: Int, of: Int): String = "Niveau $stage/$of"
        const val BRAND_NEW = "Tout neuf"
        const val CURRENT = "Actuel ✓"

        /** contentDescription. */
        const val BACK = "Retour"
        const val BACK_LABEL = "← Retour"
    }

    // -----------------------------------------------------------------------
    // Shop — `shop/`
    // -----------------------------------------------------------------------

    object Shop {
        /** contentDescription. */
        const val BACK = "Retour"
        const val BACK_LABEL = "← Retour"

        /** contentDescription. */
        fun wallet(balance: Int): String = "$balance points"
        const val WALLET_GLYPH = "⭐"
        const val TAGLINE = "Habille ton copain !"

        const val WARDROBE_GLYPH = "🧺"
        const val WARDROBE_TITLE = "Ton armoire"
        const val STORE_GLYPH = "🛒"
        const val STORE_TITLE = "Le magasin"
        const val STORE_EMPTY = "Bientôt de nouveaux objets à découvrir ✨"

        /**
         * `SLOT_LABEL` — the body part a colour/style slot dresses; it titles
         * that slot's group in the list. Keyed by the raw slot name, which is
         * data from the catalogue, so this stays a Map and not an enum.
         */
        val SLOT_LABEL: Map<String, String> = mapOf(
            "bodyColor" to "Corps",
            "hornColor" to "Corne",
            "maneColor" to "Crinière",
            "tailColor" to "Queue",
            "bellyColor" to "Ventre",
            "tailTipColor" to "Bout de queue",
            "tailStyle" to "Queue",
            "hornStyle" to "Corne",
            "hair" to "Poil",
            "tailSize" to "Queue",
            "furPattern" to "Pelage",
        )

        /** The group title for `category == accessory` — it has no slot. */
        const val ACCESSORY_LABEL = "Accessoires"

        /**
         * The group title for a tile: accessories have no slot, and an unknown
         * slot falls back to its raw key exactly as the TSX does
         * (`SLOT_LABEL[slot] ?? slot`), so a catalogue addition shows something
         * rather than nothing.
         */
        fun groupLabel(isAccessory: Boolean, slot: String): String =
            if (isAccessory) ACCESSORY_LABEL else SLOT_LABEL[slot] ?: slot

        /** The try-on dialog. Nothing here spends; only its big button does. */
        object TryOn {
            /** contentDescription. */
            fun title(item: String): String = "Essayer $item"

            /** contentDescription. */
            const val CANCEL = "Ne pas acheter"
            const val CANCEL_GLYPH = "✕"

            /** contentDescription. */
            fun buy(item: String, cost: Int): String = "Acheter $item pour $cost étoiles"

            /** contentDescription. */
            fun cannotAfford(item: String): String = "Pas encore assez d'étoiles pour $item"

            /** « Acheter · ⭐ 22 » — U+00B7 middle dot. */
            fun buyLabel(cost: Int): String = "Acheter · ⭐ $cost"
            fun notYetLabel(cost: Int): String = "⭐ $cost · pas encore"
        }

        /** A catalogue tile's spoken state — the second half of « <nom>, <état> ». */
        object ItemState {
            const val EQUIPPED_REMOVABLE = "équipé, appuie pour enlever"
            const val EQUIPPED = "équipé"
            const val OWNED = "à toi"

            fun lockedCost(cost: Int, stage: Int): String =
                "coûte $cost points, à débloquer au niveau $stage"

            fun tryingCost(cost: Int): String = "coûte $cost points, en train d'essayer"
            fun cost(cost: Int): String = "coûte $cost points"
            fun cannotAfford(cost: Int): String = "coûte $cost points, pas encore assez"

            /** The whole contentDescription of a catalogue tile. */
            fun label(name: String, state: String): String = "$name, $state"

            const val REMOVE_GLYPH = "✕"
            const val OWNED_GLYPH = "✓"

            /** « 🌱 niv. 5 · ⭐ 22 » on a growth-gated tile. */
            fun lockedBadge(stage: Int, cost: Int): String =
                "🌱 niv. $stage · ⭐ $cost"

            fun priceBadge(cost: Int): String = "⭐ $cost"
        }

        /**
         * The mascot's factory look, shown as an ordinary already-owned tile —
         * never "reset"/"default" jargon in front of a child.
         */
        object DefaultLook {
            fun lockedBadge(stage: Int): String = "🌱 niv. $stage"
            const val EQUIPPED_BADGE = "Équipé ✓"
            const val OWNED_BADGE = "À toi"

            fun lockedState(stage: Int): String = "à débloquer au niveau $stage"
            const val EQUIPPED_STATE = "en place"
            const val OWNED_STATE = "à toi"
        }

        /** The growth card — the headline spend. */
        object Growth {
            const val TITLE = "Faire grandir 🌱"
            fun meter(stage: Int, of: Int): String = "Croissance $stage sur $of"

            /** contentDescription. */
            const val AT_MAX = "Niveau maximum atteint"

            /** contentDescription. */
            fun grow(price: Int): String = "Faire grandir pour $price points"

            /** contentDescription. */
            fun cannotAfford(price: Int): String =
                "Pas encore assez de points pour grandir, il en faut $price"

            const val AT_MAX_LABEL = "Niveau max ✨"
            fun growLabel(price: Int): String = "Grandir · ⭐ $price"
            fun notYetLabel(price: Int): String = "pas encore · ⭐ $price"
        }

        /** The savings meter — "how close am I?" with no arithmetic. */
        fun savings(balance: Int, of: Int): String = "$balance étoiles sur $of"
        const val SAVINGS_SPARKLE = "✨"
    }

    // -----------------------------------------------------------------------
    // Exercise chrome
    //
    // Gathered from the nine engines. Per-ROUND text is not here: prompts and
    // success lines come from `core.levels` (the exercise calls the same
    // function the VO manifest bakes from), and the letter tiles' labels from
    // `core.domain.faceLabel`.
    // -----------------------------------------------------------------------

    object Exercise {
        // The big replay button under the mascot. The wording is per-engine and
        // is what a screen reader announces, so the five variants are kept
        // distinct rather than unified.

        /** FirstLetter, Assemble. */
        const val REPEAT_WORD = "Répéter le mot"

        /** ReadImage, LetterMatch. */
        const val REPEAT_INSTRUCTION = "Répéter la consigne"

        /** SpellSyllable. */
        const val REPLAY_WORD = "Réécouter le mot"

        /** FindSound, SpellSound, SoundTwins. */
        const val REPLAY_SOUND = "Réécouter le son"

        /** SyllableGrid. */
        const val REPLAY_SYLLABLE = "Réécouter la syllabe"

        /**
         * The button's own text. FirstLetter is the exception — it shows the
         * word itself once revealed, and a bare speaker glyph before that.
         */
        const val LISTEN = "🔊 Écouter"
        const val LISTEN_GLYPH = "🔊"
        fun listenWord(word: String): String = "🔊 $word"

        /** A tile's audition button (contentDescription). */
        fun listenTile(text: String): String = "Écouter $text"

        // The consigne printed above the mascot, per engine. Four engines author
        // theirs and they live here; the other five take theirs from :core
        // (`GRID_PROMPT` for the two grid drills, `MODE_HINT` for the three
        // assemble modes) or from [spellHeadline] below. FirstLetter and
        // LetterMatch print none at all — `headline` is null for those two.

        /** `FindSoundExercise.tsx`. */
        const val FIND_SOUND_HEADLINE = "Écoute le son et trouve comment il s'écrit"

        /** `ReadImageExercise.tsx`. */
        const val READ_IMAGE_HEADLINE = "Lis le mot et touche la bonne image"

        /** `SpellSoundExercise.tsx`. */
        const val SPELL_SOUND_HEADLINE = "Écoute le son et écris-le avec les lettres"

        /** `SoundTwinsExercise.tsx`. U+2014 em dash, and U+0027 in « s'écrire ». */
        const val SOUND_TWINS_HEADLINE =
            "Un son peut s'écrire de plusieurs façons — trouve-les toutes !"

        // Tile contentDescriptions — invariant 6. The letter-FORM exercises use
        // `core.domain.faceLabel` instead, because the form has to be named too.
        fun letterTile(letter: String): String = "Lettre $letter"
        fun syllableTile(syllable: String): String = "Syllabe $syllable"
        fun soundTile(graphy: String): String = "Son $graphy"
        fun imageTile(word: String): String = "Image : $word"

        /** The assembly row and its slots. */
        const val WORD_TO_COMPLETE = "Mot à compléter"
        fun remove(piece: String): String = "Retirer $piece"

        /** The half-written syllable in the grid drill's `vowel` mode. */
        fun syllableToComplete(consonant: String): String = "Syllabe à compléter : $consonant"

        /** `HEADLINE` in `SpellSyllableExercise` — the line above the mascot. */
        fun spellHeadline(mode: SpellSyllableMode): String = when (mode) {
            SpellSyllableMode.LETTERS_EXACT -> "Complète le mot avec les lettres"
            SpellSyllableMode.LETTERS_EXTRA -> "Complète le mot — attention aux intrus"
            SpellSyllableMode.LETTERS_TWO -> "Complète les deux syllabes"
        }

        /**
         * `MIXED_HEADLINE` — the « écritures mêlées » twins swap the headline,
         * because matching the WRITING is the task now.
         */
        fun spellHeadlineMixed(mode: SpellSyllableMode): String = when (mode) {
            SpellSyllableMode.LETTERS_EXACT -> "Trouve la bonne écriture"
            SpellSyllableMode.LETTERS_EXTRA -> "La bonne lettre… et la bonne écriture"
            SpellSyllableMode.LETTERS_TWO -> "Deux syllabes — la bonne écriture"
        }
    }
}
