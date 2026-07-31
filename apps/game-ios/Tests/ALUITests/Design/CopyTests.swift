import ALCore
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* Copy tests exist for one reason: a one-character drift in a French string    */
/* does not crash, log or fail anything. It changes what a six-year-old reads,  */
/* and — where the same string is also spoken — silently re-keys a baked clip   */
/* (D17: `voKey` hashes UTF-16 code units exactly), so the line comes back in   */
/* the robot text-to-speech voice mid-sentence.                                 */
/*                                                                             */
/* Every expected string here was copied out of the TSX, not out of Copy.swift. */
/* -------------------------------------------------------------------------- */

private let typographicApostrophe: Unicode.Scalar = "\u{2019}"
private let asciiApostrophe: Unicode.Scalar = "\u{0027}"

/// Everything in `Copy` that is displayed as a whole string. Deliberately hand-
/// listed: there is no reflection over `enum` statics, and a hand list is also a
/// checklist — a new string that nobody adds here gets no hygiene coverage.
private let allCopy: [String] = [
    // Hub
    Copy.Hub.title, Copy.Hub.subtitle, Copy.Hub.switchPlayer, Copy.Hub.listenBalance,
    Copy.Hub.listenIcon, Copy.Hub.openDashboard, Copy.Hub.seeCompanion,
    Copy.Hub.jackpotGlyph, Copy.Hub.coinGlyph,
    Copy.Hub.levelPaying(3, points: 10), Copy.Hub.levelPaying(1, points: 1),
    Copy.Hub.levelTraining(2), Copy.Hub.rewardBadge(10),
    // Dashboard
    Copy.Dashboard.backToMenu, Copy.Dashboard.backToMenuLabel, Copy.Dashboard.heading,
    Copy.Dashboard.balance(42), Copy.Dashboard.balance(1), Copy.Dashboard.balanceGlyph,
    Copy.Dashboard.balanceCaption, Copy.Dashboard.growth, Copy.Dashboard.growthBar,
    Copy.Dashboard.shopDoor, Copy.Dashboard.switchCompanion,
    // Frame / end of run
    Copy.Frame.backToMenu, Copy.Frame.star, Copy.Frame.futureRound,
    Copy.Finished.cheerEmoji, Copy.Finished.star,
    Copy.Finished.allFound, Copy.Finished.allSucceeded, Copy.Finished.allRead,
    Copy.EndButtons.menu, Copy.EndButtons.next,
    Copy.EarnBadge.label(7), Copy.EarnBadge.amount(7), Copy.EarnBadge.star,
    Copy.Tile.listenFallback, Copy.Tile.listenGlyph,
    // Roster
    Copy.WhoIsPlaying.heading, Copy.WhoIsPlaying.edit, Copy.WhoIsPlaying.editDone,
    Copy.WhoIsPlaying.newProfile, Copy.WhoIsPlaying.newProfileGlyph,
    Copy.WhoIsPlaying.newProfileLabel, Copy.WhoIsPlaying.askName,
    Copy.WhoIsPlaying.namePlaceholder, Copy.WhoIsPlaying.go, Copy.WhoIsPlaying.back,
    Copy.WhoIsPlaying.owlAvatar, Copy.WhoIsPlaying.renameGlyph, Copy.WhoIsPlaying.deleteGlyph,
    Copy.WhoIsPlaying.play(with: "Léa"), Copy.WhoIsPlaying.rename("Léa"),
    Copy.WhoIsPlaying.delete("Léa"), Copy.WhoIsPlaying.renamePrompt("Léa"),
    Copy.WhoIsPlaying.deleteConfirm("Léa"),
    // Onboarding
    Copy.Onboarding.wave, Copy.Onboarding.title, Copy.Onboarding.scopeIOS,
    Copy.Onboarding.scopeOther,
    Copy.Onboarding.trialParagraph(days: 14, price: "4,99 €", scope: Copy.Onboarding.scopeIOS),
    Copy.Onboarding.pauseParagraph(days: 14), Copy.Onboarding.noStoreParagraph,
    Copy.Onboarding.startTrial(days: 14), Copy.Onboarding.start,
    Copy.Onboarding.consentTitle, Copy.Onboarding.consentBody,
    // Parental gate
    Copy.ParentalGate.title, Copy.ParentalGate.question(7, 4), Copy.ParentalGate.wrong,
    Copy.ParentalGate.cancel, Copy.ParentalGate.confirm, Copy.ParentalGate.purchaseReason,
    // Paywall
    Copy.Paywall.Child.moon, Copy.Paywall.Child.title, Copy.Paywall.Child.body,
    Copy.Paywall.Child.seeCompanion, Copy.Paywall.Child.iAmAnAdult,
    Copy.Paywall.Parent.title, Copy.Paywall.Parent.body(price: "4,99 €"),
    Copy.Paywall.Parent.buy(price: "4,99 €"), Copy.Paywall.Parent.busy,
    Copy.Paywall.Parent.restore, Copy.Paywall.Parent.noStore,
    Copy.Paywall.Parent.consentBody, Copy.Paywall.Parent.backToGame,
    Copy.Paywall.Note.purchaseFailed, Copy.Paywall.Note.restored,
    Copy.Paywall.Note.nothingToRestore,
    // Picker
    Copy.Picker.name(.unicorn), Copy.Picker.name(.cat), Copy.Picker.name(.fox),
    Copy.Picker.name(.rabbit), Copy.Picker.name(.dragon), Copy.Picker.choose("Licorne"),
    Copy.Picker.eggGlyph, Copy.Picker.switchGlyph,
    Copy.Picker.firstRunTitle, Copy.Picker.switchTitle,
    Copy.Picker.firstRunSubtitle, Copy.Picker.switchSubtitle,
    Copy.Picker.level(3, of: 10), Copy.Picker.brandNew, Copy.Picker.current,
    Copy.Picker.back, Copy.Picker.backLabel,
    // Shop
    Copy.Shop.back, Copy.Shop.backLabel, Copy.Shop.wallet(42), Copy.Shop.walletGlyph,
    Copy.Shop.tagline, Copy.Shop.wardrobeGlyph, Copy.Shop.wardrobeTitle,
    Copy.Shop.storeGlyph, Copy.Shop.storeTitle, Copy.Shop.storeEmpty,
    Copy.Shop.accessoryLabel,
    Copy.Shop.TryOn.title("Crinière corail"), Copy.Shop.TryOn.cancel,
    Copy.Shop.TryOn.cancelGlyph, Copy.Shop.TryOn.buy("Crinière corail", cost: 22),
    Copy.Shop.TryOn.cannotAfford("Crinière corail"), Copy.Shop.TryOn.buyLabel(22),
    Copy.Shop.TryOn.notYetLabel(22),
    Copy.Shop.ItemState.equippedRemovable, Copy.Shop.ItemState.equipped,
    Copy.Shop.ItemState.owned, Copy.Shop.ItemState.lockedCost(22, stage: 5),
    Copy.Shop.ItemState.tryingCost(22), Copy.Shop.ItemState.cost(22),
    Copy.Shop.ItemState.cannotAfford(22),
    Copy.Shop.ItemState.label("Crinière corail", Copy.Shop.ItemState.equipped),
    Copy.Shop.ItemState.removeGlyph, Copy.Shop.ItemState.ownedGlyph,
    Copy.Shop.ItemState.lockedBadge(stage: 5, cost: 22), Copy.Shop.ItemState.priceBadge(22),
    Copy.Shop.DefaultLook.lockedBadge(stage: 5), Copy.Shop.DefaultLook.equippedBadge,
    Copy.Shop.DefaultLook.ownedBadge, Copy.Shop.DefaultLook.lockedState(stage: 5),
    Copy.Shop.DefaultLook.equippedState, Copy.Shop.DefaultLook.ownedState,
    Copy.Shop.Growth.title, Copy.Shop.Growth.meter(3, of: 10), Copy.Shop.Growth.atMax,
    Copy.Shop.Growth.grow(60), Copy.Shop.Growth.cannotAfford(60),
    Copy.Shop.Growth.atMaxLabel, Copy.Shop.Growth.growLabel(60),
    Copy.Shop.Growth.notYetLabel(60),
    Copy.Shop.savings(12, of: 22), Copy.Shop.savingsSparkle,
    // Exercise chrome
    Copy.Exercise.repeatWord, Copy.Exercise.repeatInstruction, Copy.Exercise.replayWord,
    Copy.Exercise.replaySound, Copy.Exercise.replaySyllable,
    Copy.Exercise.listen, Copy.Exercise.listenGlyph, Copy.Exercise.listenWord("CHAT"),
    Copy.Exercise.listenTile("MA"), Copy.Exercise.letterTile("A"),
    Copy.Exercise.syllableTile("MA"), Copy.Exercise.soundTile("ou"),
    Copy.Exercise.imageTile("jupe"), Copy.Exercise.wordToComplete,
    Copy.Exercise.remove("MA"), Copy.Exercise.syllableToComplete("V"),
    Copy.Exercise.spellHeadline(.lettersExact), Copy.Exercise.spellHeadline(.lettersExtra),
    Copy.Exercise.spellHeadline(.lettersTwo),
    Copy.Exercise.spellHeadlineMixed(.lettersExact),
    Copy.Exercise.spellHeadlineMixed(.lettersExtra),
    Copy.Exercise.spellHeadlineMixed(.lettersTwo),
]
    + Copy.Shop.slotLabel.values.sorted()

@Suite("Copy — French strings, byte for byte")
struct CopyTests {

    /* ====================================================================== */
    /* D17 — the apostrophe                                                    */
    /* ====================================================================== */

    /// The shell's own copy uses ASCII `'` everywhere. Normalising one of these
    /// to the typographic `’` is exactly the invisible edit a "clean up the
    /// French" pass makes.
    @Test("every apostrophe in the shell's copy is U+0027, never U+2019")
    func shellUsesAsciiApostrophes() {
        for s in allCopy {
            #expect(
                !s.unicodeScalars.contains(typographicApostrophe),
                Comment(rawValue: "U+2019 found in « \(s) »")
            )
        }

        // The strings that actually carry one, named so the check cannot pass
        // vacuously if the list above ever loses its apostrophe-bearing entries.
        let withApostrophes = [
            Copy.Hub.levelTraining(2),
            Copy.WhoIsPlaying.askName,
            Copy.WhoIsPlaying.go,
            Copy.Onboarding.trialParagraph(days: 14, price: "4,99 €", scope: Copy.Onboarding.scopeIOS),
            Copy.Onboarding.consentBody,
            Copy.ParentalGate.wrong,
            Copy.Paywall.Child.body,
            Copy.Paywall.Parent.body(price: "4,99 €"),
            Copy.Paywall.Parent.noStore,
            Copy.Paywall.Note.purchaseFailed,
            Copy.Picker.switchSubtitle,
            Copy.Shop.TryOn.cannotAfford("Crinière corail"),
        ]
        #expect(withApostrophes.count == 12)
        for s in withApostrophes {
            #expect(
                s.unicodeScalars.contains(asciiApostrophe),
                Comment(rawValue: "expected an ASCII apostrophe in « \(s) »")
            )
        }
    }

    /// The other side of the same coin: ALCore's content DOES use the
    /// typographic apostrophe, and both halves are baked into the clip bank.
    /// If a future tidy-up normalised one direction or the other, this fails
    /// even though every screen still looks right.
    @Test("ALCore's hub hints still carry the typographic apostrophe")
    func coreKeepsTypographicApostrophes() throws {
        let order = try #require(Levels.modeHint[.order])
        #expect(order == "Remets les syllabes dans l\u{2019}ordre")
        #expect(order.unicodeScalars.contains(typographicApostrophe))

        let intruder = try #require(Levels.modeHint[.orderDistractor])
        #expect(intruder == "Range le mot\u{2026} et \u{00E9}vite l\u{2019}intrus !")

        let script = try #require(Levels.matchHint[.script])
        #expect(script == "Associe le script et l\u{2019}attach\u{00E9}")
    }

    /* ====================================================================== */
    /* The other scalars that look like ASCII and are not                      */
    /* ====================================================================== */

    @Test("the parental gate multiplies with U+00D7, not the letter x")
    func multiplicationSign() {
        let q = Copy.ParentalGate.question(7, 4)
        #expect(q == "Combien font 7 \u{00D7} 4 ?")
        #expect(q.unicodeScalars.contains("\u{00D7}"))
        #expect(!q.lowercased().contains("x"))
    }

    @Test("the shop's separators are U+00B7 middle dots")
    func middleDots() {
        #expect(Copy.Shop.TryOn.buyLabel(22) == "Acheter \u{00B7} ⭐ 22")
        #expect(Copy.Shop.TryOn.notYetLabel(22) == "⭐ 22 \u{00B7} pas encore")
        #expect(Copy.Shop.Growth.growLabel(60) == "Grandir \u{00B7} ⭐ 60")
        #expect(Copy.Shop.Growth.notYetLabel(60) == "pas encore \u{00B7} ⭐ 60")
        #expect(Copy.Shop.ItemState.lockedBadge(stage: 5, cost: 22) == "🌱 niv. 5 \u{00B7} ⭐ 22")
        #expect(Copy.Hub.hintSeparator == "\u{00B7} ")
    }

    @Test("the adult copy uses em dashes and a one-character ellipsis")
    func dashesAndEllipsis() {
        #expect(Copy.Paywall.Parent.buy(price: "4,99 €") == "Débloquer \u{2014} 4,99 €")
        #expect(Copy.Paywall.Parent.consentBody.unicodeScalars.contains("\u{2014}"))
        #expect(Copy.Onboarding.noStoreParagraph.unicodeScalars.contains("\u{2014}"))
        #expect(Copy.Exercise.spellHeadline(.lettersExtra).unicodeScalars.contains("\u{2014}"))
        #expect(Copy.Exercise.spellHeadlineMixed(.lettersTwo).unicodeScalars.contains("\u{2014}"))

        // "…" is ONE scalar, not three dots — the busy button's whole label.
        #expect(Copy.Paywall.Parent.busy == "\u{2026}")
        #expect(Copy.Paywall.Parent.busy.unicodeScalars.count == 1)
        #expect(Copy.Exercise.spellHeadlineMixed(.lettersExtra)
            == "La bonne lettre\u{2026} et la bonne écriture")
    }

    /// `&nbsp;` before the exclamation mark — French typography, and the reason
    /// « Demande à un grand ! » does not wrap onto its own line.
    @Test("the Paywall's child line keeps its no-break space")
    func noBreakSpace() {
        let body = Copy.Paywall.Child.body
        #expect(body == "Demande à un grand\u{00A0}! Tes étoiles et ta mascotte t'attendent.")
        #expect(body.unicodeScalars.contains("\u{00A0}"))
        #expect(!body.contains(" !"))  // an ordinary space would be the bug
    }

    /// The roster's "new profile" card uses the FULLWIDTH plus, which is visibly
    /// larger than "+" at the same font size — that is why it was chosen.
    @Test("the new-profile glyph is U+FF0B")
    func fullwidthPlus() {
        #expect(Copy.WhoIsPlaying.newProfileGlyph == "\u{FF0B}")
        #expect(Copy.WhoIsPlaying.newProfileGlyph != "+")
    }

    /* ====================================================================== */
    /* Hygiene                                                                 */
    /* ====================================================================== */

    @Test("no string is empty, and none carries a stray leading or trailing space")
    func hygiene() {
        for s in allCopy {
            #expect(!s.isEmpty)
            #expect(
                s == s.trimmingCharacters(in: .whitespacesAndNewlines),
                Comment(rawValue: "stray whitespace around « \(s) »")
            )
            #expect(!s.contains("  "), Comment(rawValue: "double space in « \(s) »"))
            #expect(!s.contains("\n"), Comment(rawValue: "newline in « \(s) »"))
        }
        #expect(allCopy.count > 140)
    }

    /// Three strings DO end in a space, on purpose: they are glyph prefixes that
    /// a number or a name is concatenated onto. Asserted separately so the
    /// hygiene sweep above can stay strict.
    @Test("the three deliberate trailing spaces survive")
    func deliberateTrailingSpaces() {
        #expect(Copy.Hub.playerChipPrefix == "👤 ")
        #expect(Copy.Hub.balanceChipPrefix == "⭐ ")
        #expect(Copy.Hub.hintSeparator == "· ")
    }

    /* ====================================================================== */
    /* Whole strings, verbatim                                                 */
    /* ====================================================================== */

    @Test("hub")
    func hub() {
        #expect(Copy.Hub.title == "Attrape-Lettres")
        #expect(Copy.Hub.subtitle == "Choisis un jeu et un niveau.")
        #expect(Copy.Hub.switchPlayer == "Changer de joueur")
        #expect(Copy.Hub.listenBalance == "Écouter mes points")
        #expect(Copy.Hub.openDashboard == "Mon copain et mes points")
        #expect(Copy.Hub.seeCompanion == "Voir mon copain")
    }

    /// The PWA pluralises on `n > 1`, so ZERO takes the singular. Ported as-is:
    /// "gagne 1 étoile", "gagne 2 étoiles".
    @Test("star pluralisation follows the JS rule, zero included")
    func pluralisation() {
        #expect(Copy.stars(0) == "étoile")
        #expect(Copy.stars(1) == "étoile")
        #expect(Copy.stars(2) == "étoiles")
        #expect(Copy.Hub.levelPaying(4, points: 1) == "Niveau 4, gagne 1 étoile")
        #expect(Copy.Hub.levelPaying(4, points: 10) == "Niveau 4, gagne 10 étoiles")
        #expect(Copy.Hub.levelTraining(1) == "Niveau 1, pour s'entraîner")
        #expect(Copy.Dashboard.balance(1) == "Tu as 1 étoile")
        #expect(Copy.Dashboard.balance(42) == "Tu as 42 étoiles")
        // EarnBadge is always plural in the source, even at +1. Not "fixed".
        #expect(Copy.EarnBadge.label(1) == "Tu gagnes 1 étoiles")
    }

    @Test("dashboard and end-of-run")
    func dashboardAndFinish() {
        #expect(Copy.Dashboard.backToMenu == "Retour au menu")
        #expect(Copy.Dashboard.backToMenuLabel == "← Menu")
        #expect(Copy.Dashboard.heading == "Mon copain")
        #expect(Copy.Dashboard.balanceCaption == "étoiles à dépenser")
        #expect(Copy.Dashboard.growth == "🌱 Croissance")
        #expect(Copy.Dashboard.growthBar == "Croissance de ton copain")
        #expect(Copy.Dashboard.shopDoor == "Boutique 🛍️")
        #expect(Copy.Dashboard.switchCompanion == "Changer de copain 🔄")

        #expect(Copy.Frame.backToMenu == "← Menu")
        #expect(Copy.EndButtons.menu == "🏠 Menu")
        #expect(Copy.EndButtons.next == "🎉 Suivant")
        #expect(Copy.Finished.allFound == "Tu as tout trouvé !")
        #expect(Copy.Finished.allSucceeded == "Tu as tout réussi !")
        #expect(Copy.Finished.allRead == "Tu as tout lu !")
    }

    @Test("roster")
    func roster() {
        #expect(Copy.WhoIsPlaying.heading == "Qui joue ?")
        #expect(Copy.WhoIsPlaying.edit == "Modifier")
        #expect(Copy.WhoIsPlaying.editDone == "Terminé")
        #expect(Copy.WhoIsPlaying.askName == "Comment tu t'appelles ?")
        #expect(Copy.WhoIsPlaying.namePlaceholder == "Ton prénom")
        #expect(Copy.WhoIsPlaying.nameMaxLength == 14)
        #expect(Copy.WhoIsPlaying.go == "C'est parti ! 🎉")
        #expect(Copy.WhoIsPlaying.play(with: "Léa") == "Jouer avec Léa")
        #expect(Copy.WhoIsPlaying.rename("Léa") == "Renommer Léa")
        #expect(Copy.WhoIsPlaying.delete("Léa") == "Supprimer Léa")
        #expect(Copy.WhoIsPlaying.renamePrompt("Léa") == "Nouveau prénom pour Léa ?")
        #expect(
            Copy.WhoIsPlaying.deleteConfirm("Léa")
                == "Supprimer le profil de Léa ? Tout sera perdu."
        )
    }

    @Test("onboarding — App Review 3.1.1 disclosure, verbatim")
    func onboarding() {
        #expect(Copy.Onboarding.title == "Nous aussi, on est parents.")
        #expect(Copy.Onboarding.scopeIOS == "pour toute la famille")
        #expect(Copy.Onboarding.scopeOther == "sur vos appareils")
        #expect(
            Copy.Onboarding.trialParagraph(days: 14, price: "4,99 €", scope: "pour toute la famille")
                == "Attrape-Lettres est gratuit pendant 14 jours. Ensuite, un achat unique de 4,99 € débloque tout pour toute la famille, pour toujours. Pas d'abonnement, pas de publicité, rien à acheter dans le jeu."
        )
        #expect(
            Copy.Onboarding.pauseParagraph(days: 14)
                == "Après 14 jours, les exercices se mettent en pause. Les progrès, les étoiles et les mascottes sont gardés."
        )
        #expect(
            Copy.Onboarding.noStoreParagraph
                == "Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas de compte, rien à acheter — et tout fonctionne sans connexion."
        )
        #expect(Copy.Onboarding.startTrial(days: 14) == "Commencer les 14 jours")
        #expect(Copy.Onboarding.start == "Commencer")
        #expect(Copy.Onboarding.consentTitle == "Nous aider à améliorer le jeu")
        #expect(
            Copy.Onboarding.consentBody
                == "On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le prénom de votre enfant, jamais rien qui l'identifie. Vous pouvez changer d'avis à tout moment."
        )
    }

    @Test("parental gate")
    func parentalGate() {
        #expect(Copy.ParentalGate.title == "Espace parents")
        #expect(Copy.ParentalGate.wrong == "Ce n'est pas le bon résultat.")
        #expect(Copy.ParentalGate.cancel == "Annuler")
        #expect(Copy.ParentalGate.confirm == "Continuer")
        #expect(
            Copy.ParentalGate.purchaseReason
                == "Cette page contient un achat. Elle est réservée aux adultes."
        )
    }

    @Test("paywall")
    func paywall() {
        #expect(Copy.Paywall.Child.title == "Les jeux font une pause")
        #expect(Copy.Paywall.Child.seeCompanion == "Voir ma mascotte")
        #expect(Copy.Paywall.Child.iAmAnAdult == "Je suis un adulte")
        #expect(Copy.Paywall.Parent.title == "Débloquer Attrape-Lettres")
        #expect(
            Copy.Paywall.Parent.body(price: "4,99 €")
                == "Un achat unique de 4,99 €. Pas d'abonnement, pas de publicité, rien d'autre à acheter. Les progrès de vos enfants sont déjà enregistrés."
        )
        #expect(Copy.Paywall.Parent.restore == "Restaurer un achat")
        #expect(
            Copy.Paywall.Parent.noStore
                == "L'achat se fait depuis l'application installée sur le téléphone ou la tablette."
        )
        #expect(
            Copy.Paywall.Parent.consentBody
                == "Nous aider à améliorer le jeu — exercice, niveau, réussi ou non. Jamais le prénom de votre enfant."
        )
        #expect(Copy.Paywall.Parent.backToGame == "Retour au jeu")
        #expect(Copy.Paywall.Note.purchaseFailed == "L'achat n'a pas abouti. Rien n'a été débité.")
        #expect(Copy.Paywall.Note.restored == "Achat restauré.")
        #expect(Copy.Paywall.Note.nothingToRestore == "Aucun achat trouvé sur ce compte.")
    }

    /// A failed purchase must never read as the child's fault, and the child
    /// step must never show a price or a buy button (Kids Category 1.3 +
    /// invariants 3 and 11).
    @Test("the child's paywall step names no money")
    func childStepIsMoneyFree() {
        for s in [Copy.Paywall.Child.title, Copy.Paywall.Child.body,
                  Copy.Paywall.Child.seeCompanion, Copy.Paywall.Child.iAmAnAdult] {
            #expect(!s.contains("€"))
            #expect(!s.lowercased().contains("achat"))
            #expect(!s.lowercased().contains("acheter"))
            #expect(!s.lowercased().contains("prix"))
        }
    }

    @Test("picker")
    func picker() {
        #expect(Copy.Picker.speciesOrder == [.unicorn, .cat, .fox, .rabbit, .dragon])
        #expect(Copy.Picker.name(.unicorn) == "Licorne")
        #expect(Copy.Picker.name(.cat) == "Chat")
        #expect(Copy.Picker.name(.fox) == "Renard")
        #expect(Copy.Picker.name(.rabbit) == "Lapin")
        #expect(Copy.Picker.name(.dragon) == "Dragon")
        #expect(Copy.Picker.choose("Licorne") == "Choisir Licorne")
        #expect(Copy.Picker.firstRunTitle == "Choisis ton copain")
        #expect(Copy.Picker.switchTitle == "Change de copain")
        #expect(Copy.Picker.firstRunSubtitle == "Il grandira avec toi.")
        #expect(Copy.Picker.switchSubtitle == "Tu retrouveras chacun comme tu l'as laissé.")
        #expect(Copy.Picker.level(3, of: 10) == "Niveau 3/10")
        #expect(Copy.Picker.brandNew == "Tout neuf")
        #expect(Copy.Picker.current == "Actuel ✓")

        // Every species in ALCore has a display name — a new one fails here.
        for species in Species.allCases {
            #expect(Copy.Picker.speciesOrder.contains(species))
            #expect(!Copy.Picker.name(species).isEmpty)
        }
        #expect(Set(Species.allCases.map(Copy.Picker.name)).count == Species.allCases.count)
    }

    @Test("shop")
    func shop() {
        #expect(Copy.Shop.tagline == "Habille ton copain !")
        #expect(Copy.Shop.wardrobeTitle == "Ton armoire")
        #expect(Copy.Shop.storeTitle == "Le magasin")
        #expect(Copy.Shop.storeEmpty == "Bientôt de nouveaux objets à découvrir ✨")
        #expect(Copy.Shop.wallet(42) == "42 points")
        #expect(Copy.Shop.savings(12, of: 22) == "12 étoiles sur 22")

        #expect(Copy.Shop.slotLabel["bodyColor"] == "Corps")
        #expect(Copy.Shop.slotLabel["hornColor"] == "Corne")
        #expect(Copy.Shop.slotLabel["maneColor"] == "Crinière")
        #expect(Copy.Shop.slotLabel["tailColor"] == "Queue")
        #expect(Copy.Shop.slotLabel["bellyColor"] == "Ventre")
        #expect(Copy.Shop.slotLabel["tailTipColor"] == "Bout de queue")
        #expect(Copy.Shop.slotLabel["tailStyle"] == "Queue")
        #expect(Copy.Shop.slotLabel["hornStyle"] == "Corne")
        #expect(Copy.Shop.slotLabel["hair"] == "Poil")
        #expect(Copy.Shop.slotLabel["tailSize"] == "Queue")
        #expect(Copy.Shop.slotLabel["furPattern"] == "Pelage")
        #expect(Copy.Shop.slotLabel.count == 11)
        #expect(Copy.Shop.accessoryLabel == "Accessoires")

        #expect(Copy.Shop.TryOn.title("Corne dorée") == "Essayer Corne dorée")
        #expect(Copy.Shop.TryOn.cancel == "Ne pas acheter")
        #expect(Copy.Shop.TryOn.buy("Corne dorée", cost: 22) == "Acheter Corne dorée pour 22 étoiles")
        #expect(
            Copy.Shop.TryOn.cannotAfford("Corne dorée")
                == "Pas encore assez d'étoiles pour Corne dorée"
        )

        #expect(Copy.Shop.ItemState.equippedRemovable == "équipé, appuie pour enlever")
        #expect(Copy.Shop.ItemState.cost(22) == "coûte 22 points")
        #expect(
            Copy.Shop.ItemState.lockedCost(22, stage: 5)
                == "coûte 22 points, à débloquer au niveau 5"
        )
        #expect(Copy.Shop.ItemState.cannotAfford(22) == "coûte 22 points, pas encore assez")
        #expect(Copy.Shop.ItemState.label("Corne dorée", "équipé") == "Corne dorée, équipé")

        #expect(Copy.Shop.DefaultLook.equippedBadge == "Équipé ✓")
        #expect(Copy.Shop.DefaultLook.ownedBadge == "À toi")
        #expect(Copy.Shop.DefaultLook.lockedBadge(stage: 5) == "🌱 niv. 5")

        #expect(Copy.Shop.Growth.title == "Faire grandir 🌱")
        #expect(Copy.Shop.Growth.meter(3, of: 10) == "Croissance 3 sur 10")
        #expect(Copy.Shop.Growth.atMax == "Niveau maximum atteint")
        #expect(Copy.Shop.Growth.grow(60) == "Faire grandir pour 60 points")
        #expect(
            Copy.Shop.Growth.cannotAfford(60)
                == "Pas encore assez de points pour grandir, il en faut 60"
        )
        #expect(Copy.Shop.Growth.atMaxLabel == "Niveau max ✨")
    }

    @Test("exercise chrome")
    func exerciseChrome() {
        #expect(Copy.Exercise.repeatWord == "Répéter le mot")
        #expect(Copy.Exercise.repeatInstruction == "Répéter la consigne")
        #expect(Copy.Exercise.replayWord == "Réécouter le mot")
        #expect(Copy.Exercise.replaySound == "Réécouter le son")
        #expect(Copy.Exercise.replaySyllable == "Réécouter la syllabe")
        #expect(Copy.Exercise.listen == "🔊 Écouter")
        #expect(Copy.Exercise.listenWord("CHAT") == "🔊 CHAT")
        #expect(Copy.Exercise.listenTile("MA") == "Écouter MA")
        #expect(Copy.Exercise.letterTile("A") == "Lettre A")
        #expect(Copy.Exercise.syllableTile("MA") == "Syllabe MA")
        #expect(Copy.Exercise.soundTile("ou") == "Son ou")
        #expect(Copy.Exercise.imageTile("jupe") == "Image : jupe")
        #expect(Copy.Exercise.wordToComplete == "Mot à compléter")
        #expect(Copy.Exercise.remove("MA") == "Retirer MA")
        #expect(Copy.Exercise.syllableToComplete("V") == "Syllabe à compléter : V")
        #expect(Copy.Tile.listenFallback == "Écouter")
        #expect(Copy.Tile.minimumSide == 92)
    }

    /// The plain headline and the "écritures mêlées" headline must differ for
    /// every mode: on the mixed twins, matching the WRITING is the task, and
    /// showing the plain line would describe a different game.
    @Test("spell-syllable headlines, plain and mixed")
    func spellHeadlines() {
        #expect(Copy.Exercise.spellHeadline(.lettersExact) == "Complète le mot avec les lettres")
        #expect(Copy.Exercise.spellHeadline(.lettersExtra) == "Complète le mot — attention aux intrus")
        #expect(Copy.Exercise.spellHeadline(.lettersTwo) == "Complète les deux syllabes")

        #expect(Copy.Exercise.spellHeadlineMixed(.lettersExact) == "Trouve la bonne écriture")
        #expect(
            Copy.Exercise.spellHeadlineMixed(.lettersExtra)
                == "La bonne lettre… et la bonne écriture"
        )
        #expect(Copy.Exercise.spellHeadlineMixed(.lettersTwo) == "Deux syllabes — la bonne écriture")

        for mode in SpellSyllableMode.allCases {
            #expect(Copy.Exercise.spellHeadline(mode) != Copy.Exercise.spellHeadlineMixed(mode))
        }
    }

    /* ====================================================================== */
    /* Boundaries with other modules                                           */
    /* ====================================================================== */

    /// Copy must not grow a second copy of anything ALCore already owns: the
    /// exercise names, the hub hints, or the spoken lines. A duplicate would
    /// drift, and the spoken half is hashed exactly (D17).
    @Test("Copy does not restate an ALCore string")
    func noDuplicationOfCore() {
        var owned = Set<String>()
        owned.formUnion(Levels.exercises.map(\.name))
        owned.formUnion(Levels.modeHint.values)
        owned.formUnion(Levels.matchHint.values)
        owned.formUnion(Levels.spellHint.values)
        owned.insert(Levels.mixedHint)
        owned.insert(Levels.readImagePrompt)
        owned.insert(VO.shopBought)
        owned.insert(VO.shopGrew)
        owned.insert(VO.shopNeedMore)

        // « Complète les deux syllabes » is genuinely the same sentence in two
        // roles — `spellHint` (the hub chip) and `HEADLINE` (the in-game line) —
        // in the PWA too. It is the ONLY overlap, and it is authored, not drift.
        let sanctioned: Set<String> = ["Complète les deux syllabes"]

        for s in allCopy where owned.contains(s) {
            #expect(
                sanctioned.contains(s),
                Comment(rawValue: "« \(s) » is ALCore's; reference it instead of restating it")
            )
        }
    }

    /// The euro amount is formatted with a comma, French-style, and the price
    /// carries a space before the symbol.
    @Test("the fallback price label is French-formatted")
    func priceLabel() {
        #expect(Copy.fallbackPriceLabel(4.99) == "4,99 €")
        #expect(Copy.fallbackPriceLabel(5) == "5,00 €")
        // `UNLOCK_PRICE_EUR` is 9.99 in `licensing/entitlement.ts`, so the
        // shipped fallback reads « 9,99 € ».
        #expect(Copy.fallbackPriceLabel(unlockPriceEur) == "9,99 €")
        #expect(!Copy.fallbackPriceLabel(4.99).contains("."))
    }
}
