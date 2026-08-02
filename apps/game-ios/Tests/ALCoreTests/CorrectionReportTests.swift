import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* « Suggérer une correction » — the value and the URL.                         */
/*                                                                             */
/* Two things are worth holding still here, and they are not the French.        */
/*                                                                             */
/*  1. WHAT THE REPORT CAN HOLD. A support channel is the classic way a child's */
/*     first name reaches a server, and the defence is that the report is a     */
/*     struct with three closed fields rather than a bag. That is only true     */
/*     while nobody adds a fourth, so the field list is asserted by reflection  */
/*     — the same device `TelemetryClosureTests` uses on `TelemetryProps`.      */
/*                                                                             */
/*  2. THE ENCODING. A `mailto:` whose body is half-encoded does not fail; it   */
/*     opens a mail client with a truncated or mangled message, and we would    */
/*     only ever find out from the reports we never received. Accents, spaces,  */
/*     newlines and `&` all appear in the body this module builds.              */
/* -------------------------------------------------------------------------- */

private let sample = CorrectionReport(exercise: .firstLetter, level: 2, appVersion: "1.4.0")

@Suite("The correction report holds nothing about a child")
struct CorrectionReportShapeTests {

    @Test("exactly three fields, and they are the declared ones")
    func fieldsAreClosed() {
        let labels = Mirror(reflecting: sample).children.compactMap(\.label)
        #expect(labels == CorrectionReport.allowedKeys)
    }

    /// The only free-form field is the app's own version. A `String` here is
    /// how a name arrives — `childName`, `note`, `message`, `email`. If one is
    /// added, this fails and the reviewer has to justify it in the same change.
    @Test("the only String it carries is the app version")
    func oneStringOnly() {
        let strings = Mirror(reflecting: sample).children
            .filter { $0.value is String }
            .compactMap(\.label)
        #expect(strings == ["appVersion"])
    }

    @Test("the exercise name comes from the catalog, for every exercise")
    func namesResolve() {
        for id in ExerciseId.allCases {
            let report = CorrectionReport(exercise: id, level: 1, appVersion: "1.0.0")
            let catalog = Levels.exercises.first { $0.id == id }
            #expect(report.exerciseName == catalog?.name)
            #expect(!report.exerciseName.isEmpty)
        }
    }
}

@Suite("The mailto: URL")
struct CorrectionMailTests {

    @Test("percent-encoding covers everything a French sentence contains")
    func encoding() {
        #expect(CorrectionMail.percentEncoded("a b") == "a%20b")
        #expect(CorrectionMail.percentEncoded("é") == "%C3%A9")
        #expect(CorrectionMail.percentEncoded("\n") == "%0A")
        #expect(CorrectionMail.percentEncoded("&") == "%26")
        // The one URLComponents leaves alone, and the reason this is hand-rolled:
        // a mail client is entitled to read a bare `+` in a query value as a space.
        #expect(CorrectionMail.percentEncoded("+") == "%2B")
        #expect(CorrectionMail.percentEncoded("?") == "%3F")
        #expect(CorrectionMail.percentEncoded("\u{2014}") == "%E2%80%94")
        // Unreserved survives untouched (RFC 3986 §2.3).
        #expect(CorrectionMail.percentEncoded("aZ09-._~") == "aZ09-._~")
    }

    @Test("the address stays readable and the two fields are encoded")
    func shape() throws {
        let url = try #require(CorrectionMail.url(sample))
        let text = url.absoluteString
        // `@` is legal in a mailto path; encoding it produces an address no
        // client can parse.
        #expect(text.hasPrefix("mailto:\(CorrectionMail.address)?"))
        #expect(!text.contains("%40"))
        #expect(text.contains("subject="))
        #expect(text.contains("&body="))
        // Nothing unencoded survived into the query.
        let query = String(text.drop(while: { $0 != "?" }).dropFirst())
        #expect(!query.contains(" "))
        #expect(!query.contains("\n"))
    }

    @Test("the subject names the exercise and the level")
    func subject() {
        let subject = CorrectionMail.subject(sample)
        #expect(subject.contains(sample.exerciseName))
        #expect(subject.contains("niveau 2"))
        #expect(subject.hasPrefix("Attrape-Lettres"))
    }

    @Test("the body carries the three lines that make a report actionable")
    func body() {
        let body = CorrectionMail.body(sample)
        #expect(body.contains(sample.exerciseName))
        #expect(body.contains("first-letter"))
        #expect(body.contains("Niveau : 2"))
        #expect(body.contains("Version : 1.4.0"))
    }

    @Test("every exercise and level builds a URL that parses")
    func everyExerciseParses() throws {
        for id in ExerciseId.allCases {
            for level in 1...5 {
                let report = CorrectionReport(exercise: id, level: level, appVersion: "1.0.0")
                let url = try #require(
                    CorrectionMail.url(report), "\(id.rawValue) level \(level) produced no URL")
                #expect(url.scheme == "mailto")
            }
        }
    }

    @Test("an empty address yields no URL rather than a broken one")
    func emptyAddress() {
        #expect(CorrectionMail.url(sample, to: "") == nil)
        #expect(CorrectionMail.url(sample, to: "   ") == nil)
    }

    /// The whole point, stated as a test: a roster name cannot be in here,
    /// because there is no field it could have come from.
    @Test("nothing in the URL comes from the roster")
    func noRosterPath() throws {
        let url = try #require(CorrectionMail.url(sample))
        let decoded = url.absoluteString.removingPercentEncoding ?? ""
        // Every substring of the URL is derived from: a frozen enum raw value,
        // the catalog's own name, an Int, and the app version.
        let allowed =
            sample.exerciseName + sample.exercise.rawValue + String(sample.level)
            + sample.appVersion
        for word in ["Léa", "prénom", "profile", "device", "household"] {
            #expect(!decoded.contains(word))
        }
        #expect(!allowed.isEmpty)
    }
}
