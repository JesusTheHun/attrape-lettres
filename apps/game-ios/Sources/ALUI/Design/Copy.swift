import ALCore
import CoreGraphics
import Foundation

/* -------------------------------------------------------------------------- */
/* Every user-facing French string in the shell, byte for byte.                */
/*                                                                             */
/* Copied character for character out of the TSX, including punctuation. Four   */
/* characters recur and are easy to normalise by accident — each one is a       */
/* different Unicode scalar from the ASCII lookalike a keyboard produces:       */
/*                                                                             */
/*   '  U+0027 APOSTROPHE          — every apostrophe in THIS file. The         */
/*                                   typographic ’ (U+2019) appears only in     */
/*                                   `levels.ts`, i.e. in ALCore, and the two   */
/*                                   must not be unified: `voKey` hashes UTF-16 */
/*                                   code units exactly (D17), so swapping one  */
/*                                   silently re-keys a clip and the line comes */
/*                                   back in the robot voice with no error.     */
/*   ×  U+00D7 MULTIPLICATION SIGN — the parental gate's sum.                   */
/*   ·  U+00B7 MIDDLE DOT          — the hub's hint separator, the shop prices. */
/*   —  U+2014 EM DASH             — the adult copy and two exercise headlines. */
/*   …  U+2026 HORIZONTAL ELLIPSIS — the busy button, one exercise headline.    */
/*   \u{00A0} NO-BREAK SPACE       — `&nbsp;` in « Demande à un grand ! ».      */
/*   ＋ U+FF0B FULLWIDTH PLUS      — the roster's "new profile" card.           */
/*                                                                             */
/* NOT here, on purpose:                                                       */
/*  - `ALCore.faceLabel(_:)` — the letter tile's accessibility label.           */
/*  - `ALCore.Levels.*` — exercise names, `modeHint`, `matchHint`, `spellHint`, */
/*    `mixedHint`, `readImagePrompt`, `gridPrompt`, and every per-round prompt  */
/*    and success line. Those are content, they are already tested there, and   */
/*    several of them carry the U+2019 apostrophe this file deliberately never  */
/*    uses.                                                                    */
/*  - `ALCore.VO.*` and the spoken celebration lines. A displayed title and a   */
/*    spoken line happen to read alike ("Tu as tout trouvé !") but they are     */
/*    different strings with different owners; the spoken one is baked audio.   */
/*  - `ALCore.trialNotice(_:)` — the hub's trial pill.                          */
/*  - The mascot's own label (« Ma licorne », …) — `ALArt/Mascot/Rig.swift`.     */
/*  - `src/dev/` (the `#stages` and `#vo` benches) — never in a child's flow.    */
/* -------------------------------------------------------------------------- */

public enum Copy {

    /* ====================================================================== */
    /* Shared vocabulary                                                       */
    /* ====================================================================== */

    /// « étoile » / « étoiles ». The PWA pluralises on `n > 1`, so **zero takes
    /// the singular** — French usage, and it is what the code does. Ported as-is.
    public static func stars(_ n: Int) -> String { n > 1 ? "étoiles" : "étoile" }

    /// The price shown when the store has no localised label:
    /// `UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",")` + " €".
    public static func fallbackPriceLabel(_ eur: Double) -> String {
        let fixed = String(format: "%.2f", eur).replacingOccurrences(of: ".", with: ",")
        return "\(fixed) €"
    }

    /* ====================================================================== */
    /* Hub — `App.tsx`                                                         */
    /* ====================================================================== */

    public enum Hub {
        public static let title = "Attrape-Lettres"
        public static let subtitle = "Choisis un jeu et un niveau."

        /// The player chip: `👤 ` + the child's name.
        public static let playerChipPrefix = "👤 "
        public static let switchPlayer = "Changer de joueur"        // aria-label

        public static let listenBalance = "Écouter mes points"      // aria-label
        public static let listenIcon = "🔊"

        /// The balance chip: `⭐ ` + the number.
        public static let balanceChipPrefix = "⭐ "
        public static let openDashboard = "Mon copain et mes points" // aria-label
        public static let seeCompanion = "Voir mon copain"           // aria-label

        /// « · » + a hint, appended after an exercise's name. The hint text
        /// itself comes from `ALCore.Levels`.
        public static let hintSeparator = "· "

        /// A level button that pays: « Niveau 3, gagne 10 étoiles ».
        public static func levelPaying(_ level: Int, points: Int) -> String {
            "Niveau \(level), gagne \(points) \(Copy.stars(points))"
        }

        /// A level that promises nothing. No shipped row does since training
        /// rows started paying the curve; the string stays with its branch.
        public static func levelTraining(_ level: Int) -> String {
            "Niveau \(level), pour s'entraîner"
        }

        /// The reward badge on a level button: `+10` and `⭐`, or `+2` and `🪙`.
        public static func rewardBadge(_ points: Int) -> String { "+\(points)" }
        public static let jackpotGlyph = "⭐"
        public static let coinGlyph = "🪙"
    }

    /* ====================================================================== */
    /* Dashboard — `components/Dashboard.tsx`                                  */
    /* ====================================================================== */

    public enum Dashboard {
        public static let backToMenu = "Retour au menu"   // aria-label
        public static let backToMenuLabel = "← Menu"
        public static let heading = "Mon copain"

        /// « Tu as 42 étoiles » — the big balance pill's label.
        public static func balance(_ n: Int) -> String { "Tu as \(n) \(Copy.stars(n))" }
        public static let balanceGlyph = "⭐"
        public static let balanceCaption = "étoiles à dépenser"

        public static let growth = "🌱 Croissance"
        public static let growthBar = "Croissance de ton copain"  // aria-label

        public static let shopDoor = "Boutique 🛍️"
        public static let switchCompanion = "Changer de copain 🔄"
        /// The parent door. Optional — `RootView` only supplies the
        /// callback in the app target, so a preview or a test renders the
        /// dashboard exactly as before.
        public static let shareDoor = "Partager entre appareils 📱"
    }

    /* ====================================================================== */
    /* Game frame + end of run                                                 */
    /* ====================================================================== */

    public enum Frame {
        /// `GameFrame`'s back button. Note it is NOT the Dashboard's — same
        /// glyph, different component, and both are written out here so a
        /// refactor cannot quietly merge them.
        public static let backToMenu = "← Menu"
        public static let star = "⭐"
        public static let futureRound = "•"
    }

    public enum Finished {
        public static let cheerEmoji = "🤩"
        public static let star = "⭐"

        /// The three end-of-run titles the exercises pass in. Displayed text —
        /// the spoken celebration is `ALCore.VO`'s and is a different string.
        public static let allFound = "Tu as tout trouvé !"
        public static let allSucceeded = "Tu as tout réussi !"
        public static let allRead = "Tu as tout lu !"
    }

    public enum EndButtons {
        public static let menu = "🏠 Menu"
        public static let next = "🎉 Suivant"
    }

    public enum EarnBadge {
        /// Always plural in the source, even for `+1`. Ported as-is.
        public static func label(_ earned: Int) -> String { "Tu gagnes \(earned) étoiles" }
        public static func amount(_ earned: Int) -> String { "+\(earned)" }
        public static let star = "⭐"
    }

    public enum Tile {
        /// `previewLabel ?? "Écouter"` — the fallback label on the per-tile
        /// audition button.
        public static let listenFallback = "Écouter"
        public static let listenGlyph = "🔊"
        /// Invariant 6: the tile's minimum side, in points.
        public static let minimumSide: CGFloat = 92
    }

    /* ====================================================================== */
    /* Roster — `components/WhoIsPlaying.tsx`                                  */
    /* ====================================================================== */

    public enum WhoIsPlaying {
        public static let heading = "Qui joue ?"
        public static let edit = "Modifier"
        public static let editDone = "Terminé"

        public static let newProfile = "Nouveau profil"   // aria-label
        /// U+FF0B FULLWIDTH PLUS SIGN — not an ASCII "+".
        public static let newProfileGlyph = "\u{FF0B}"
        public static let newProfileLabel = "Nouveau"

        public static let askName = "Comment tu t'appelles ?"
        /// Both the field's `aria-label` and its placeholder.
        public static let namePlaceholder = "Ton prénom"
        /// `maxLength={14}` on the name field.
        public static let nameMaxLength = 14
        public static let go = "C'est parti ! 🎉"
        public static let back = "Retour"

        /// The avatar shown before a child has chosen a species.
        public static let owlAvatar = "🦉"

        public static func play(with name: String) -> String { "Jouer avec \(name)" }
        public static func rename(_ name: String) -> String { "Renommer \(name)" }
        public static func delete(_ name: String) -> String { "Supprimer \(name)" }
        public static let renameGlyph = "✏️"
        public static let deleteGlyph = "✕"

        /// `window.prompt` — becomes an alert with a text field natively.
        public static func renamePrompt(_ name: String) -> String {
            "Nouveau prénom pour \(name) ?"
        }
        /// `window.confirm` — becomes a destructive confirmation alert.
        public static func deleteConfirm(_ name: String) -> String {
            "Supprimer le profil de \(name) ? Tout sera perdu."
        }
    }

    /* ====================================================================== */
    /* Onboarding — `components/Onboarding.tsx`                                */
    /* ====================================================================== */

    public enum Onboarding {
        public static let wave = "👋"
        public static let title = "Nous aussi, on est parents."

        /// Apple's Family Sharing covers the household on a non-consumable;
        /// Google Play's Family Library explicitly does not, so the Android copy
        /// promises only what Android delivers.
        public static let scopeIOS = "pour toute la famille"
        public static let scopeOther = "sur vos appareils"

        /// « Attrape-Lettres est gratuit pendant 7 jours. Ensuite, un achat
        /// unique de 11,99 € débloque tout pour toute la famille, pour toujours.
        /// Pas d'abonnement, pas de publicité, rien à acheter dans le jeu. »
        ///
        /// The `<strong>` runs in the TSX are emphasis only; the sentence is one
        /// string. JSX collapses the source's line breaks to single spaces, so
        /// this is the rendered text.
        public static func trialParagraph(days: Int, price: String, scope: String) -> String {
            "Attrape-Lettres est gratuit pendant \(days) jours. Ensuite, un achat unique de \(price) débloque tout \(scope), pour toujours. Pas d'abonnement, pas de publicité, rien à acheter dans le jeu."
        }

        public static func pauseParagraph(days: Int) -> String {
            "Après \(days) jours, les exercices se mettent en pause. Les progrès, les étoiles et les mascottes sont gardés."
        }

        /// Shown when there is no store at all (the web build).
        public static let noStoreParagraph =
            "Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas de compte, rien à acheter — et tout fonctionne sans connexion."

        public static func startTrial(days: Int) -> String { "Commencer les \(days) jours" }
        public static let start = "Commencer"

        /// The consent checkbox. Starts UNTICKED — pre-ticked consent has been
        /// invalid since CJEU Planet49.
        public static let consentTitle = "Nous aider à améliorer le jeu"
        public static let consentBody =
            "On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le prénom de votre enfant, jamais rien qui l'identifie. Vous pouvez changer d'avis à tout moment."
    }

    /* ====================================================================== */
    /* Pairing — no TSX. The PWA has no such screen.                           */
    /*                                                                        */
    /* Written for the grown-up, like Onboarding and the gate, and plainly on  */
    /* purpose: a screen that looks like the game invites a six-year-old to    */
    /* press things on it.                                                     */
    /* ====================================================================== */

    public enum Pairing {
        public static let heading = "Les mêmes progrès sur tous les appareils"

        /// What it is for, in the terms a parent already has. No jargon: not
        /// « synchronisation », not « compte », not « foyer ».
        public static let intro =
            "Le téléphone de papa, celui de maman, la tablette — les étoiles et les progrès de votre enfant se retrouvent sur tous."

        /// The sentence that has to be here. Invariant 10 is the promise; this
        /// is where the person it protects can read it.
        public static let privacy =
            "Sans compte et sans adresse e-mail. Le prénom de votre enfant ne quitte jamais l'appareil."

        public static let showTitle = "Sur cet appareil"
        public static let qrLabel = "Code à faire scanner par l'autre appareil"  // aria-label

        /// Deliberately « l'appareil photo », the system camera. The app asks
        /// for no camera permission of its own — one more permission to
        /// justify in a Kids Category review, for a screen a parent opens once.
        public static let scanHint =
            "Ouvrez l'appareil photo de l'autre appareil et visez ce carré."

        public static let share = "Partager le lien"
        public static let shareMessage = "Rejoindre les progrès Attrape-Lettres"

        /// The automatic path, named so a parent does not pair two devices by
        /// hand for nothing.
        public static let automatic =
            "Vos propres appareils, avec le même identifiant Apple, se relient tout seuls."

        public static let joinedTitle = "C'est fait"
        public static let joined =
            "Les deux appareils partagent maintenant les mêmes étoiles et les mêmes progrès."

        /// The hazard `HouseholdClaim`'s header describes: a third device left
        /// behind. Not data loss, so the wording must not read like a warning
        /// about losing something.
        public static let thirdDevice =
            "Si un troisième appareil était déjà relié, reliez-le à nouveau. Il garde toutes ses étoiles, mais il ne se mettra plus à jour avec les autres."

        /// No endpoint in this build. Says so rather than showing a code that
        /// would do nothing.
        public static let unavailable =
            "Le partage entre appareils n'est pas disponible dans cette version."

        public static let done = "Terminé"

        /// Shown on the gate. Guideline 1.3 is about not putting an adult
        /// mechanism in front of a child, and a share sheet is one — so the
        /// door is visible but what is behind it is not reachable by tapping.
        public static let gateReason = "Partager les progrès entre appareils"
    }

    /* ====================================================================== */
    /* « Suggérer une correction » — no TSX. The PWA has no such link.          */
    /*                                                                        */
    /* The one adult affordance that lives INSIDE a child's screen, so its     */
    /* wording carries more weight than usual: it has to read as "not for you" */
    /* to a six-year-old and as "yes, that" to the parent beside them. Hence   */
    /* the flat register and no glyph — the link is text, and quiet.           */
    /* ====================================================================== */

    public enum Correction {
        /// The link at the foot of every exercise. Deliberately not « Signaler
        /// une erreur » — a parent is helping, not filing a complaint, and a
        /// child who reads a little should not find the word « erreur » under a
        /// game with no fail state (invariant 3).
        public static let link = "Suggérer une correction"

        /// The gate's one line. Says what the adult is about to do, because
        /// what follows leaves the app.
        public static let gateReason =
            "Nous écrire au sujet de cet exercice \u{2014} un mot, un son ou une image qui vous semble faux."

        /// No mail client answered the `mailto:`. Not an error state: the
        /// address is shown so the parent can write from wherever they like.
        public static func noMailApp(_ address: String) -> String {
            "Aucune application e-mail sur cet appareil. Écrivez-nous à \(address)."
        }
    }

    /* ====================================================================== */
    /* Parental gate — `components/ParentalGate.tsx`                           */
    /* ====================================================================== */

    public enum ParentalGate {
        /// Both the dialog's `aria-label` and its heading.
        public static let title = "Espace parents"

        /// « Combien font 7 × 4 ? » — U+00D7, not the letter x.
        public static func question(_ a: Int, _ b: Int) -> String {
            "Combien font \(a) \u{00D7} \(b) ?"
        }

        public static let wrong = "Ce n'est pas le bon résultat."
        public static let cancel = "Annuler"
        public static let confirm = "Continuer"

        /// The reason the Paywall passes in.
        public static let purchaseReason =
            "Cette page contient un achat. Elle est réservée aux adultes."
    }

    /* ====================================================================== */
    /* Paywall — `components/Paywall.tsx`                                      */
    /* ====================================================================== */

    public enum Paywall {

        /// Step 1, the child sees this. No price, no buy button (Kids Category
        /// 1.3), and nothing that reads as a failure (invariant 3).
        public enum Child {
            public static let moon = "🌙"
            public static let title = "Les jeux font une pause"
            /// `&nbsp;` before the "!" — U+00A0, the French typographic space.
            public static let body =
                "Demande à un grand\u{00A0}! Tes étoiles et ta mascotte t'attendent."
            public static let seeCompanion = "Voir ma mascotte"
            public static let iAmAnAdult = "Je suis un adulte"
        }

        /// Step 3, behind the gate.
        public enum Parent {
            public static let title = "Débloquer Attrape-Lettres"

            public static func body(price: String) -> String {
                "Un achat unique de \(price). Pas d'abonnement, pas de publicité, rien d'autre à acheter. Les progrès de vos enfants sont déjà enregistrés."
            }

            /// « Débloquer — 4,99 € ». U+2014 em dash.
            public static func buy(price: String) -> String { "Débloquer — \(price)" }
            /// The in-flight label — U+2026, one character.
            public static let busy = "…"

            /// Apple requires a restore control for non-consumables.
            public static let restore = "Restaurer un achat"

            public static let noStore =
                "L'achat se fait depuis l'application installée sur le téléphone ou la tablette."

            /// GDPR Art. 7(3): withdrawal must be as easy as consent, so the
            /// toggle lives here too — shorter wording than Onboarding's.
            public static let consentBody =
                "Nous aider à améliorer le jeu — exercice, niveau, réussi ou non. Jamais le prénom de votre enfant."

            public static let backToGame = "Retour au jeu"

            /// The door to the code field, under the two store controls. Quiet
            /// on purpose: a code is the exception, buying is the path.
            public static let haveACode = "J'ai un code"

            /// Shown above the buy button once a `discount` code is redeemed, so
            /// the parent can see WHY the price dropped before they pay. Without
            /// it the cheaper number looks like a mistake, and a parent who
            /// suspects a mistake does not buy.
            public static let earlyPrice = "Prix early adopter — merci d'être là si tôt."
        }

        /// The code step, behind the same gate as the price. Written for the
        /// adult holding a card, so: plain, and it names what a code is for.
        public enum Code {
            public static let title = "Utiliser un code"
            /// Two kinds of code, and the sentence has to be true of both
            /// without turning the paywall into a treasure hunt: « offerts »
            /// stays, because neither kind is ever sold (D61).
            public static let body =
                "Les codes sont offerts — presse, écoles, familles qui nous aident à tester. Selon le code, ils débloquent le jeu ou donnent le prix early adopter."
            public static let placeholder = "XXXX-XXXX-XXXX"
            public static let field = "Code à douze caractères"  // aria-label
            public static let submit = "Valider"
            /// The in-flight label — U+2026, one character. Same as the buy button.
            public static let busy = "…"
            public static let cancel = "Retour"
        }

        /// Redemption outcomes. Never an error state, and never a rebuke: a
        /// parent typing a code off a card is doing us a favour.
        public enum CodeNote {
            public static let granted = "C'est débloqué. Merci !"
            public static let already = "Ce foyer est déjà débloqué."

            /// A discount code did NOT unlock anything, and the note must not
            /// let a parent believe it did — they still have to buy, and finding
            /// that out at the next launch instead of now would be a betrayal.
            public static func discountGranted(price: String) -> String {
                "Votre prix early adopter est activé : \(price). Il reste à l'acheter ci-dessous."
            }

            public static let alreadyDiscount =
                "Votre prix early adopter est déjà activé."
            /// Covers both « no such code » and « the server said it was
            /// malformed ». From the parent's chair they are the same thing.
            public static let unknown = "Ce code n'existe pas. Vérifiez les caractères."
            public static let exhausted = "Ce code a déjà servi."
            public static let expired = "Ce code a expiré."
            /// The fail-open answer. Says nothing happened, blames nobody, and
            /// invites a retry — never « échec ».
            public static let unreachable =
                "Nous n'avons pas pu joindre le serveur. Réessayez plus tard, rien n'a changé."
        }

        /// Store outcomes. Never an error state: a failed purchase says what did
        /// NOT happen, and the child's side is untouched (invariant 11).
        public enum Note {
            public static let purchaseFailed = "L'achat n'a pas abouti. Rien n'a été débité."
            public static let restored = "Achat restauré."
            public static let nothingToRestore = "Aucun achat trouvé sur ce compte."
        }
    }

    /* ====================================================================== */
    /* Species picker — `shop/Picker.tsx`                                      */
    /* ====================================================================== */

    public enum Picker {
        /// The five friends, in the order the picker lists them. The names are
        /// view copy — `ALCore.Species` carries no display name.
        public static let speciesOrder: [Species] = [.unicorn, .cat, .fox, .rabbit, .dragon]

        public static func name(_ species: Species) -> String {
            switch species {
            case .unicorn: return "Licorne"
            case .cat: return "Chat"
            case .fox: return "Renard"
            case .rabbit: return "Lapin"
            case .dragon: return "Dragon"
            }
        }

        public static func choose(_ name: String) -> String { "Choisir \(name)" }  // aria-label

        public static let eggGlyph = "🥚"
        public static let switchGlyph = "🔄"

        public static let firstRunTitle = "Choisis ton copain"
        public static let switchTitle = "Change de copain"

        public static let firstRunSubtitle = "Il grandira avec toi."
        public static let switchSubtitle = "Tu retrouveras chacun comme tu l'as laissé."

        public static func level(_ stage: Int, of total: Int) -> String {
            "Niveau \(stage)/\(total)"
        }
        public static let brandNew = "Tout neuf"
        public static let current = "Actuel ✓"

        public static let back = "Retour"          // aria-label
        public static let backLabel = "← Retour"
    }

    /* ====================================================================== */
    /* Shop — `shop/`                                                          */
    /* ====================================================================== */

    public enum Shop {
        public static let back = "Retour"          // aria-label
        public static let backLabel = "← Retour"
        public static func wallet(_ balance: Int) -> String { "\(balance) points" }  // aria-label
        public static let walletGlyph = "⭐"
        public static let tagline = "Habille ton copain !"

        public static let wardrobeGlyph = "🧺"
        public static let wardrobeTitle = "Ton armoire"
        public static let storeGlyph = "🛒"
        public static let storeTitle = "Le magasin"
        public static let storeEmpty = "Bientôt de nouveaux objets à découvrir ✨"

        /// `SLOT_LABEL` — the body part a colour/style slot dresses; it titles
        /// that slot's group in the list. Keyed by the raw slot name.
        public static let slotLabel: [String: String] = [
            "bodyColor": "Corps",
            "hornColor": "Corne",
            "maneColor": "Crinière",
            "tailColor": "Queue",
            "bellyColor": "Ventre",
            "tailTipColor": "Bout de queue",
            "tailStyle": "Queue",
            "hornStyle": "Corne",
            "hair": "Poil",
            "tailSize": "Queue",
            "furPattern": "Pelage",
        ]
        /// The group title for `category == "accessory"` — it has no slot.
        public static let accessoryLabel = "Accessoires"

        /// The try-on dialog. Nothing here spends; only its big button does.
        public enum TryOn {
            public static func title(_ item: String) -> String { "Essayer \(item)" }  // aria-label
            public static let cancel = "Ne pas acheter"                              // aria-label
            public static let cancelGlyph = "✕"

            public static func buy(_ item: String, cost: Int) -> String {
                "Acheter \(item) pour \(cost) étoiles"
            }
            public static func cannotAfford(_ item: String) -> String {
                "Pas encore assez d'étoiles pour \(item)"
            }
            /// « Acheter · ⭐ 22 » — U+00B7 middle dot.
            public static func buyLabel(_ cost: Int) -> String { "Acheter \u{00B7} ⭐ \(cost)" }
            public static func notYetLabel(_ cost: Int) -> String { "⭐ \(cost) \u{00B7} pas encore" }
        }

        /// A catalogue tile's spoken state — the second half of
        /// « <nom>, <état> ».
        public enum ItemState {
            public static let equippedRemovable = "équipé, appuie pour enlever"
            public static let equipped = "équipé"
            public static let owned = "à toi"
            public static func lockedCost(_ cost: Int, stage: Int) -> String {
                "coûte \(cost) points, à débloquer au niveau \(stage)"
            }
            public static func tryingCost(_ cost: Int) -> String {
                "coûte \(cost) points, en train d'essayer"
            }
            public static func cost(_ cost: Int) -> String { "coûte \(cost) points" }
            public static func cannotAfford(_ cost: Int) -> String {
                "coûte \(cost) points, pas encore assez"
            }
            public static func label(_ name: String, _ state: String) -> String {
                "\(name), \(state)"
            }

            public static let removeGlyph = "✕"
            public static let ownedGlyph = "✓"
            /// « 🌱 niv. 5 · ⭐ 22 » on a growth-gated tile.
            public static func lockedBadge(stage: Int, cost: Int) -> String {
                "🌱 niv. \(stage) \u{00B7} ⭐ \(cost)"
            }
            public static func priceBadge(_ cost: Int) -> String { "⭐ \(cost)" }
        }

        /// The mascot's factory look, shown as an ordinary already-owned tile —
        /// never "reset"/"default" jargon.
        public enum DefaultLook {
            public static func lockedBadge(stage: Int) -> String { "🌱 niv. \(stage)" }
            public static let equippedBadge = "Équipé ✓"
            public static let ownedBadge = "À toi"

            public static func lockedState(stage: Int) -> String {
                "à débloquer au niveau \(stage)"
            }
            public static let equippedState = "en place"
            public static let ownedState = "à toi"
        }

        /// The growth card — the headline spend.
        public enum Growth {
            public static let title = "Faire grandir 🌱"
            public static func meter(_ stage: Int, of total: Int) -> String {
                "Croissance \(stage) sur \(total)"
            }

            public static let atMax = "Niveau maximum atteint"                    // aria-label
            public static func grow(_ price: Int) -> String {
                "Faire grandir pour \(price) points"
            }
            public static func cannotAfford(_ price: Int) -> String {
                "Pas encore assez de points pour grandir, il en faut \(price)"
            }

            public static let atMaxLabel = "Niveau max ✨"
            public static func growLabel(_ price: Int) -> String { "Grandir \u{00B7} ⭐ \(price)" }
            public static func notYetLabel(_ price: Int) -> String {
                "pas encore \u{00B7} ⭐ \(price)"
            }
        }

        /// The savings meter — "how close am I?" with no arithmetic.
        public static func savings(_ balance: Int, of cost: Int) -> String {
            "\(balance) étoiles sur \(cost)"
        }
        public static let savingsSparkle = "✨"
    }

    /* ====================================================================== */
    /* Exercise chrome                                                         */
    /*                                                                         */
    /* Gathered from the nine engines. Per-ROUND text is not here: prompts and  */
    /* success lines come from `ALCore.Levels` (the exercise calls the same     */
    /* function the VO manifest bakes from), and the letter tiles' labels from  */
    /* `ALCore.faceLabel(_:)`.                                                  */
    /* ====================================================================== */

    public enum Exercise {
        /// The big replay button under the mascot. The wording is per-engine and
        /// is what a screen reader announces, so the four variants are kept
        /// distinct rather than unified.
        public static let repeatWord = "Répéter le mot"           // FirstLetter, Assemble
        public static let repeatInstruction = "Répéter la consigne" // ReadImage, LetterMatch
        public static let replayWord = "Réécouter le mot"          // SpellSyllable
        public static let replaySound = "Réécouter le son"         // FindSound, SpellSound, SoundTwins
        public static let replaySyllable = "Réécouter la syllabe"  // SyllableGrid

        /// The button's own text: « 🔊 Écouter ». FirstLetter is the exception —
        /// it shows the word itself once revealed, and a bare 🔊 before that.
        public static let listen = "🔊 Écouter"
        public static let listenGlyph = "🔊"
        public static func listenWord(_ word: String) -> String { "🔊 \(word)" }

        /// A tile's audition button.
        public static func listenTile(_ text: String) -> String { "Écouter \(text)" }

        /// Tile labels. The letter-FORM exercises use `ALCore.faceLabel(_:)`
        /// instead, because the form has to be named too.
        public static func letterTile(_ letter: String) -> String { "Lettre \(letter)" }
        public static func syllableTile(_ syllable: String) -> String { "Syllabe \(syllable)" }
        public static func soundTile(_ graphy: String) -> String { "Son \(graphy)" }
        public static func imageTile(_ word: String) -> String { "Image : \(word)" }

        /// The assembly row and its slots.
        public static let wordToComplete = "Mot à compléter"
        public static func remove(_ piece: String) -> String { "Retirer \(piece)" }

        /// The half-written syllable in the grid drill's `vowel` mode.
        public static func syllableToComplete(_ consonant: String) -> String {
            "Syllabe à compléter : \(consonant)"
        }

        /// `HEADLINE` in `SpellSyllableExercise` — the line above the mascot.
        public static func spellHeadline(_ mode: SpellSyllableMode) -> String {
            switch mode {
            case .lettersExact: return "Complète le mot avec les lettres"
            case .lettersExtra: return "Complète le mot — attention aux intrus"
            case .lettersTwo: return "Complète les deux syllabes"
            }
        }

        /// `MIXED_HEADLINE` — the « écritures mêlées » twins swap the headline,
        /// because matching the WRITING is the task now.
        public static func spellHeadlineMixed(_ mode: SpellSyllableMode) -> String {
            switch mode {
            case .lettersExact: return "Trouve la bonne écriture"
            case .lettersExtra: return "La bonne lettre… et la bonne écriture"
            case .lettersTwo: return "Deux syllabes — la bonne écriture"
            }
        }
    }
}
