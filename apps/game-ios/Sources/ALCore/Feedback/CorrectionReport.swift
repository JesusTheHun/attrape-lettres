import Foundation

/* -------------------------------------------------------------------------- */
/* « Suggérer une correction » — the report a parent sends, as a value.         */
/*                                                                             */
/* The content of this game is authored by hand: 855 baked voice clips, a       */
/* hand-split syllable bank, a word list where every fragment had to be checked */
/* against a real French rule (CLAUDE.md, « Add a word »). Hand-authored means  */
/* wrong sometimes, and the people who will notice are parents sitting next to  */
/* the child — not us, and not a test.                                         */
/*                                                                             */
/* ── The report leaves through the PARENT'S mail client, not through us. ────── */
/*                                                                             */
/* There is no endpoint behind this and that is the design, not a shortcut:     */
/*                                                                             */
/*   • a free-text field posted to our server is a store of user-generated      */
/*     content, with a retention policy, a moderation duty and an Art. 30       */
/*     processing record behind it — for a suggestion box;                      */
/*   • worse, it is a path by which a parent typing « Léa dit que … » puts a    */
/*     six-year-old's first name in our database. Invariant 10 says nothing     */
/*     identifying leaves the device; the cheapest way to keep that true of a   */
/*     free-text field is to never receive the text.                            */
/*                                                                             */
/* So this module builds a `mailto:` URL and hands it to the system. The parent */
/* sees every word before it is sent, in their own client, and can delete any   */
/* of it. We receive whatever they chose to write, in a mailbox, like a letter. */
/*                                                                             */
/* ── What this value may hold. ─────────────────────────────────────────────── */
/*                                                                             */
/* Three fields, all closed: a `ExerciseId` (an enum), an `Int`, and the app's  */
/* own version string. No name, no device id, no household id, no free text —   */
/* the same "closed by construction" rule `TelemetryProps` follows (D12), and   */
/* `CorrectionReportTests` asserts the field list by reflection so a fourth      */
/* field cannot be added quietly. If a future report needs the word on screen,  */
/* that is a `String` off the round's authored content, not off the roster.     */
/* -------------------------------------------------------------------------- */

/// What we can say about the exercise a parent is writing about — without
/// asking anything of the roster.
public struct CorrectionReport: Equatable, Sendable {
    /// The exercise the parent had open. An enum, so it is one of seventeen
    /// known values and can never be a sentence.
    public let exercise: ExerciseId
    /// 1-based, as the hub numbers them.
    public let level: Int
    /// `AppVersionProvider.marketing` — which build the parent is describing.
    /// Without it, a report about a clip we re-baked last month is unactionable.
    public let appVersion: String

    public init(exercise: ExerciseId, level: Int, appVersion: String) {
        self.exercise = exercise
        self.level = level
        self.appVersion = appVersion
    }

    /// The allowlist, spelled out for the reflection test — the same device
    /// `TelemetryProps.allowedKeys` uses. Adding a field without touching this
    /// fails the run.
    public static let allowedKeys: [String] = ["exercise", "level", "appVersion"]

    /// The exercise's hub name (« Trouve la première lettre »), or its raw id if
    /// the catalog somehow does not carry it. Never a crash: this is a support
    /// mail, not a routing decision.
    public var exerciseName: String {
        Levels.exercises.first { $0.id == exercise }?.name ?? exercise.rawValue
    }
}

/// The `mailto:` URL, built by hand.
///
/// By hand rather than through `URLComponents` because the encoding is the part
/// that has to be right and `URLComponents` is not predictable here: it leaves
/// `+` unencoded in a query value, where a mail client is entitled to read it as
/// a space, and its "allowed" sets differ by component in ways that are easy to
/// get subtly wrong under accents. Percent-encoding everything outside RFC 3986
/// *unreserved* is one rule, testable in one line, and correct for every French
/// string this file can produce.
public enum CorrectionMail {

    /// Where corrections arrive. One constant — changing the address is a
    /// one-line change and a release, which is the honest cost of a native
    /// binary (CLAUDE.md § Native).
    ///
    /// NB this mailbox has to exist before the build ships. It is the only part
    /// of this feature that lives outside the repository.
    public static let address = "corrections@attrape-lettres.app"

    /// RFC 3986 §2.3 unreserved. Everything else — spaces, accents, newlines,
    /// the em dash, `&`, `?`, `+` — becomes percent-encoded UTF-8.
    private static let unreserved: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()

    /// `encodeURIComponent`, effectively.
    public static func percentEncoded(_ text: String) -> String {
        // The set above contains no character that can fail to encode, so the
        // optional is structural, not a real failure mode.
        text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }

    /// « Attrape-Lettres — correction : Trouve la première lettre (niveau 2) ».
    /// The em dash and the colon are U+2014 and U+003A; the subject is what we
    /// sort the mailbox by, so the shape is deliberate.
    public static func subject(_ report: CorrectionReport) -> String {
        "Attrape-Lettres \u{2014} correction : \(report.exerciseName) (niveau \(report.level))"
    }

    /// The pre-filled body: an invitation, room to write, then the three lines
    /// that make the report actionable — visible to the parent, deletable by
    /// the parent, and containing nothing about their child.
    public static func body(_ report: CorrectionReport) -> String {
        """
        Bonjour,

        (Décrivez ici ce qui vous a semblé faux : un mot, un son, une image.)



        \u{2014} Ces trois lignes nous aident à retrouver l'exercice \u{2014}
        Exercice : \(report.exerciseName) (\(report.exercise.rawValue))
        Niveau : \(report.level)
        Version : \(report.appVersion)
        """
    }

    /// The whole URL. The address sits in the path, where `@` is legal and must
    /// NOT be percent-encoded; only the two query values are encoded.
    ///
    /// nil for an empty address — the caller shows the address as text instead
    /// of opening nothing (invariant 3's spirit: no dead end).
    public static func url(_ report: CorrectionReport, to address: String = CorrectionMail.address)
        -> URL?
    {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let query =
            "subject=\(percentEncoded(subject(report)))"
            + "&body=\(percentEncoded(body(report)))"
        return URL(string: "mailto:\(trimmed)?\(query)")
    }
}
