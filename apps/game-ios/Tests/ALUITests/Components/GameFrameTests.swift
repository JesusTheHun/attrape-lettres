import CoreGraphics
import QuartzCore
import Testing

import ALCore
@testable import ALUI

// GameFrame — the strip rule and the frame's authored numbers, asserted
// against the TypeScript:
//
//   strip     src/components/GameFrame.tsx  (the three-branch render + LOST)
//   missRound src/exercises/*.tsx           (guard on an already-grey star)
//   metrics   src/components/GameFrame.tsx  Tailwind classes
//              rounded-3xl=24  min-h-[620px]  gap-3=12  px-4 pt-4=16
//              gap-x-1=4  gap-y-0.5=2  w-[84px]  sm: = 640px

@Suite("StarStrip — the strip classifier, verbatim from the TSX render")
struct StarStripCellsTests {

    @Test("star size: 20 pt, dropping to 16 pt only past 9 rounds")
    func fontSizeRule() {
        // TSX: const fontSize = total > 9 ? 16 : 20;
        #expect(StarStrip.fontSize(total: 5) == 20)
        #expect(StarStrip.fontSize(total: 9) == 20)
        #expect(StarStrip.fontSize(total: 10) == 16)
        #expect(StarStrip.fontSize(total: 12) == 16)
    }

    @Test("mid-run: played rounds keep their verdict, the live round pulses, the rest are dots")
    func midRun() {
        // done = 2, one miss in round 1 (index 1):
        //   i=0 < done, stars → ⭐ ; i=1 < done, !stars → ⭐ LOST ;
        //   i=2 == done, stars → pulsing ; i=3, i=4 → •
        let cells = StarStrip.cells(done: 2, total: 5, stars: [true, false, true, true, true])
        #expect(cells == [.earned, .lost, .live, .pending, .pending])
    }

    @Test("the LIVE round's star reads lost the instant its flag flips — the i == done && !stars[i] branch")
    func liveRoundGreysInstantly() {
        let cells = StarStrip.cells(done: 2, total: 5, stars: [true, false, false, true, true])
        #expect(
            cells == [.earned, .lost, .lost, .pending, .pending],
            Comment(rawValue: "the round is still live, but the star already reads as gone")
        )
    }

    @Test("a fresh run: the first star pulses, everything else is a dot")
    func freshRun() {
        let cells = StarStrip.cells(done: 0, total: 3, stars: [true, true, true])
        #expect(cells == [.live, .pending, .pending])
    }

    @Test("a finished run (done == total): only verdicts, no live star, no dots")
    func finishedRun() {
        // Engines pass done = total once finished.
        let cells = StarStrip.cells(done: 3, total: 3, stars: [true, false, true])
        #expect(cells == [.earned, .lost, .earned])
    }

    @Test("a missing flag is falsy, as JS undefined — never read as earned")
    func missingFlagIsLost() {
        let cells = StarStrip.cells(done: 1, total: 3, stars: [])
        #expect(cells == [.lost, .lost, .pending])
    }

    @Test("zero rounds render an empty strip")
    func emptyStrip() {
        #expect(StarStrip.cells(done: 0, total: 0, stars: []) == [])
    }
}

@Suite("StarStrip.miss — greys exactly once, at pointerdown")
@MainActor
struct StarStripMissTests {

    @Test("the first wrong tap greys the round's star; a second changes nothing")
    func missIsIdempotentPerRound() {
        var stars = [true, true, true]
        StarStrip.miss(1, in: &stars)
        #expect(stars == [true, false, true])
        StarStrip.miss(1, in: &stars)
        #expect(stars == [true, false, true], Comment(rawValue: "TSX: if (!starsRef.current[i]) return"))
    }

    @Test("out-of-range is a no-op — JS: !undefined short-circuits")
    func outOfRangeIsSafe() {
        var stars = [true, true]
        StarStrip.miss(5, in: &stars)
        StarStrip.miss(-1, in: &stars)
        #expect(stars == [true, true])
    }

    @Test("the grey is visible the moment the pick handler returns — same synchronous beat as the shake")
    func greyLandsAtPointerdown() {
        let time = MutableTimeSource(1_000)
        let harness = SinglePickHarness(rounds: 4, target: "ou", time: time)
        let layer = CALayer()

        // Round 0 is live and winnable before the tap…
        #expect(StarStrip.cells(done: harness.idx, total: 4, stars: harness.stars).first == .live)

        TilePress.pointerDown(
            layer: layer,
            disabled: harness.tileDisabled
        ) {
            harness.pick("an") // wrong
        }

        // …and reads LOST on the very next line — no Task, no await, no
        // run-loop turn in between.
        let cells = StarStrip.cells(done: harness.idx, total: 4, stars: harness.stars)
        #expect(cells == [.lost, .pending, .pending, .pending])

        // A second wrong tap in the same round changes the strip not at all.
        time.advance(millis: 800)
        _ = harness.pick("in")
        #expect(StarStrip.cells(done: harness.idx, total: 4, stars: harness.stars) == cells)
    }
}

@Suite("StripFlowLayout — the flex-wrap arithmetic")
struct StripFlowLayoutTests {

    @Test("12 stars of 16 pt wrap 9 + 3 in a 180 pt slot at gap 4")
    func wrapsLikeFlexbox() {
        // 9 items: 9·16 + 8·4 = 176 ≤ 180; a 10th would need 196.
        let sizes = Array(repeating: CGSize(width: 16, height: 19), count: 12)
        let lines = StripFlowLayout.wrapLines(sizes: sizes, maxWidth: 180, gapX: 4)
        #expect(lines.map(\.count) == [9, 3])
    }

    @Test("an unbounded width keeps one line")
    func singleLineWhenUnbounded() {
        let sizes = Array(repeating: CGSize(width: 20, height: 20), count: 12)
        let lines = StripFlowLayout.wrapLines(sizes: sizes, maxWidth: .infinity, gapX: 4)
        #expect(lines.map(\.count) == [12])
    }

    @Test("an exact fit stays on the line; one point less wraps")
    func exactFitBoundary() {
        let sizes = Array(repeating: CGSize(width: 20, height: 20), count: 3)
        // 3·20 + 2·4 = 68
        #expect(StripFlowLayout.wrapLines(sizes: sizes, maxWidth: 68, gapX: 4).map(\.count) == [3])
        #expect(StripFlowLayout.wrapLines(sizes: sizes, maxWidth: 67, gapX: 4).map(\.count) == [2, 1])
    }
}

@Suite("GameFrame metrics — the Tailwind classes as numbers")
struct GameFrameMetricsTests {

    @Test("rounded-3xl 24, min-h 620, gap-3 12, px-4/pt-4 16")
    func frameNumbers() {
        #expect(GameFrameMetrics.cornerRadius == 24)
        #expect(GameFrameMetrics.minHeight == 620)
        #expect(GameFrameMetrics.minHeight == Shell.minimumScreenHeight)
        #expect(GameFrameMetrics.headerSpacing == 12)
        #expect(GameFrameMetrics.headerPadding == 16)
    }

    @Test("strip gaps 4/2; the sm:-only 84 pt spacer appears at viewport ≥ 640")
    func stripNumbers() {
        #expect(GameFrameMetrics.stripGapX == 4) // gap-x-1
        #expect(GameFrameMetrics.stripGapY == 2) // gap-y-0.5
        #expect(GameFrameMetrics.spacerWidth == 84) // w-[84px]
        #expect(GameFrameMetrics.spacerMinViewport == 640) // Tailwind sm:
    }

    @Test("the lost-star treatment and the strip glyphs, byte-exact")
    func lostAndGlyphs() {
        // LOST = { filter: grayscale(1), opacity: 0.45 }; live 0.8; dots 0.28.
        #expect(Palette.Lost.saturation == 0)
        #expect(Palette.Lost.opacity == 0.45)
        #expect(Palette.liveStarOpacity == 0.8)
        #expect(Palette.futureDotOpacity == 0.28)
        #expect(Copy.Frame.star == "⭐")
        #expect(Copy.Frame.futureRound == "•")
        #expect(Copy.Frame.backToMenu == "← Menu")
    }
}
