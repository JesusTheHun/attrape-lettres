import CoreGraphics
import Foundation
import Testing
@testable import ALArt

// Every `d` string that exists in the web app, parsed. The corpus is generated
// by scripts/extract-svg-corpus.mjs straight out of the TSX and SVG sources —
// template literals have their ${...} interpolations replaced with sentinel
// numbers, so the SHAPE of every authored path is covered even though the
// values are synthetic.
//
// Regenerate after touching any mascot:
//   node ios-native/scripts/extract-svg-corpus.mjs \
//        ios-native/Tests/ALArtTests/Resources/svg-corpus.json

struct CorpusEntry: Decodable {
    let file: String
    let d: String
    let kind: String
}

struct Corpus: Decodable {
    let count: Int
    let paths: [CorpusEntry]
}

func loadCorpus() throws -> Corpus {
    let url = try #require(
        Bundle.module.url(forResource: "svg-corpus", withExtension: "json"),
        "svg-corpus.json missing from the test bundle"
    )
    return try JSONDecoder().decode(Corpus.self, from: Data(contentsOf: url))
}

@Suite("SVG corpus")
struct SVGPathCorpusTests {

    @Test("the corpus is present and has not shrunk unnoticed")
    func corpusPresent() throws {
        let corpus = try loadCorpus()
        #expect(corpus.paths.count == corpus.count)
        // A regeneration that silently captures fewer paths is the failure mode
        // this guards: the suite would still pass on a corpus of three strings.
        #expect(corpus.paths.count >= 180, Comment(rawValue: "corpus has \(corpus.paths.count) paths — did extraction break?"))
        #expect(corpus.paths.contains { $0.kind == "template" })
        #expect(corpus.paths.contains { $0.kind == "static" })
    }

    @Test("every path string in the app parses")
    func everyPathParses() throws {
        var failures: [String] = []
        for entry in try loadCorpus().paths {
            do {
                _ = try SVGPath.cgPath(from: entry.d)
            } catch {
                failures.append("[\(entry.file)] \(error) — \(entry.d.prefix(120))")
            }
        }
        #expect(failures.isEmpty, Comment(rawValue: "\(failures.count) path(s) failed:\n" + failures.joined(separator: "\n")))
    }

    @Test("no path parses to nothing")
    func noPathIsEmpty() throws {
        var empty: [String] = []
        for entry in try loadCorpus().paths {
            let path = try SVGPath.cgPath(from: entry.d)
            if path.isEmpty { empty.append("[\(entry.file)] \(entry.d.prefix(120))") }
        }
        #expect(empty.isEmpty, Comment(rawValue: "empty result for:\n" + empty.joined(separator: "\n")))
    }

    @Test("no path produces a degenerate or non-finite bounding box")
    func boundingBoxesAreSane() throws {
        var bad: [String] = []
        for entry in try loadCorpus().paths {
            let box = try SVGPath.cgPath(from: entry.d).boundingBoxOfPath
            let finite = box.origin.x.isFinite && box.origin.y.isFinite
                && box.size.width.isFinite && box.size.height.isFinite
            // A path may legitimately be a straight line (zero on one axis), but
            // never zero on both, and never NaN — NaN is what a botched arc
            // conversion produces, and it renders as nothing at all.
            if !finite || (box.width == 0 && box.height == 0) {
                bad.append("[\(entry.file)] \(box) — \(entry.d.prefix(120))")
            }
        }
        #expect(bad.isEmpty, Comment(rawValue: "suspect bounding boxes:\n" + bad.joined(separator: "\n")))
    }

    @Test("the corpus exercises every command the app actually uses")
    func commandCoverage() throws {
        let all = try loadCorpus().paths.map(\.d).joined()
        // Present in the source today. If a mascot introduces S/T/h/v the parser
        // already handles them and SVGPathGrammarTests covers them; this only
        // asserts the corpus did not lose coverage of what is in use.
        for command in "MLQZCAlqHaz" {
            #expect(all.contains(command), "corpus no longer contains a '\(command)' command")
        }
    }

    @Test("parsing is deterministic")
    func deterministic() throws {
        for entry in try loadCorpus().paths.prefix(40) {
            let a = try SVGPath.cgPath(from: entry.d)
            let b = try SVGPath.cgPath(from: entry.d)
            #expect(a == b, "[\(entry.file)] parsed differently twice")
        }
    }
}
