import CoreGraphics
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* Every expected number below was computed BY HAND from the CSS in the TSX,   */
/* never read back out of `fluid`. The arithmetic is `viewport × vw ÷ 100`,     */
/* then CSS clamp: `max(min, min(preferred, max))`.                            */
/*                                                                             */
/* The three device widths are the portrait point widths of an iPhone SE (375), */
/* an iPhone 17 Pro (402) and an iPad in its full window (1024).               */
/* -------------------------------------------------------------------------- */

private let se: CGFloat = 375
private let pro: CGFloat = 402
private let pad: CGFloat = 1024

private func near(
    _ a: CGFloat, _ b: CGFloat, _ what: String,
    sourceLocation: SourceLocation = #_sourceLocation
) {
    #expect(
        abs(a - b) < 1e-9,
        Comment(rawValue: "\(what): got \(a), expected \(b)"),
        sourceLocation: sourceLocation
    )
}

@Suite("Fluid — the CSS clamp() analogue (D16)")
struct FluidTests {

    /* ---- Boundaries ------------------------------------------------------ */

    /// `clamp(8px, 2.5vw, 16px)` — the gap before a second syllable in
    /// `SpellSyllableExercise`. Chosen because both of its boundaries land on a
    /// whole viewport width: 8 ÷ 0.025 = 320, 16 ÷ 0.025 = 640.
    @Test("hits the lower clamp exactly at 320 pt and the upper exactly at 640 pt")
    func exactBoundaries() {
        near(fluid(min: 8, vw: 2.5, max: 16, viewport: 320), 8, "lower boundary")
        near(fluid(min: 8, vw: 2.5, max: 16, viewport: 640), 16, "upper boundary")
        // One point either side of each boundary: still clamped below, still
        // free above.
        near(fluid(min: 8, vw: 2.5, max: 16, viewport: 319), 8, "just below the lower boundary")
        near(fluid(min: 8, vw: 2.5, max: 16, viewport: 324), 8.1, "just above the lower boundary")
        near(fluid(min: 8, vw: 2.5, max: 16, viewport: 636), 15.9, "just below the upper boundary")
        near(fluid(min: 8, vw: 2.5, max: 16, viewport: 641), 16, "just above the upper boundary")
    }

    @Test("below the min and above the max are both pinned")
    func pinned() {
        // clamp(28px, 8vw, 44px) — the hub title. 200 × 0.08 = 16 → pinned to 28.
        near(fluid(min: 28, vw: 8, max: 44, viewport: 200), 28, "far below")
        // 2000 × 0.08 = 160 → pinned to 44.
        near(fluid(min: 28, vw: 8, max: 44, viewport: 2000), 44, "far above")
    }

    @Test("a zero or negative viewport still yields the authored minimum")
    func degenerateViewport() {
        near(fluid(min: 92, vw: 27, max: 150, viewport: 0), 92, "zero viewport")
        near(fluid(min: 92, vw: 27, max: 150, viewport: -100), 92, "negative viewport")
    }

    /// CSS defines `clamp(MIN, VAL, MAX)` as `max(MIN, min(VAL, MAX))`, so when
    /// an author inverts the pair the MINIMUM wins. The other plausible spelling
    /// — `min(max(MIN, VAL), MAX)` — would return 10 here.
    @Test("an inverted clamp resolves to the min, as CSS specifies")
    func invertedClampPrefersTheMinimum() {
        near(fluid(min: 50, vw: 1, max: 10, viewport: 1000), 50, "inverted clamp")
    }

    /* ---- Real clamps at real device widths -------------------------------- */

    @Test("hub title — clamp(28px, 8vw, 44px)")
    func hubTitle() {
        near(fluid(min: 28, vw: 8, max: 44, viewport: se), 30, "iPhone SE")       // 375 × .08
        near(fluid(min: 28, vw: 8, max: 44, viewport: pro), 32.16, "iPhone 17 Pro") // 402 × .08
        near(fluid(min: 28, vw: 8, max: 44, viewport: pad), 44, "iPad")           // 81.92 → 44
    }

    @Test("Tile side — clamp(92px, 27vw, 150px)")
    func tileSide() {
        near(fluid(min: 92, vw: 27, max: 150, viewport: se), 101.25, "iPhone SE")
        near(fluid(min: 92, vw: 27, max: 150, viewport: pro), 108.54, "iPhone 17 Pro")
        near(fluid(min: 92, vw: 27, max: 150, viewport: pad), 150, "iPad")  // 276.48 → 150
    }

    @Test("Tile glyph — clamp(30px, 9vw, 64px)")
    func tileFont() {
        near(fluid(min: 30, vw: 9, max: 64, viewport: se), 33.75, "iPhone SE")
        near(fluid(min: 30, vw: 9, max: 64, viewport: pro), 36.18, "iPhone 17 Pro")
        near(fluid(min: 30, vw: 9, max: 64, viewport: pad), 64, "iPad")  // 92.16 → 64
    }

    @Test("Tile's Écouter button — clamp(40px, 11vw, 52px) tall, clamp(16px, 4.5vw, 22px) type")
    func listenButton() {
        near(fluid(min: 40, vw: 11, max: 52, viewport: se), 41.25, "height, iPhone SE")
        near(fluid(min: 40, vw: 11, max: 52, viewport: pro), 44.22, "height, iPhone 17 Pro")
        near(fluid(min: 40, vw: 11, max: 52, viewport: pad), 52, "height, iPad")

        near(fluid(min: 16, vw: 4.5, max: 22, viewport: se), 16.875, "type, iPhone SE")
        near(fluid(min: 16, vw: 4.5, max: 22, viewport: pro), 18.09, "type, iPhone 17 Pro")
        near(fluid(min: 16, vw: 4.5, max: 22, viewport: pad), 22, "type, iPad")

        // Invariant 6's floor: the Écouter button never drops under 40 pt, and
        // the tile it sits under never under 92 pt, at ANY device width.
        for w in stride(from: CGFloat(200), through: 1400, by: 1) {
            #expect(fluid(min: 40, vw: 11, max: 52, viewport: w) >= 40)
            #expect(fluid(min: 92, vw: 27, max: 150, viewport: w) >= 92)
        }
    }

    @Test("celebration and pause emoji — clamp(64px, 20vw, 110px) and clamp(56px, 17vw, 90px)")
    func bigEmoji() {
        near(fluid(min: 64, vw: 20, max: 110, viewport: se), 75, "🤩, iPhone SE")
        near(fluid(min: 64, vw: 20, max: 110, viewport: pro), 80.4, "🤩, iPhone 17 Pro")
        near(fluid(min: 64, vw: 20, max: 110, viewport: pad), 110, "🤩, iPad")

        near(fluid(min: 56, vw: 17, max: 90, viewport: se), 63.75, "🌙, iPhone SE")
        near(fluid(min: 56, vw: 17, max: 90, viewport: pro), 68.34, "🌙, iPhone 17 Pro")
        near(fluid(min: 56, vw: 17, max: 90, viewport: pad), 90, "🌙, iPad")
    }

    @Test("Dashboard balance — clamp(34px, 11vw, 62px) with clamp(8px, 2.6vw, 15px) padding")
    func dashboardBalance() {
        near(fluid(min: 34, vw: 11, max: 62, viewport: se), 41.25, "type, iPhone SE")
        near(fluid(min: 34, vw: 11, max: 62, viewport: pro), 44.22, "type, iPhone 17 Pro")
        near(fluid(min: 34, vw: 11, max: 62, viewport: pad), 62, "type, iPad")

        near(fluid(min: 8, vw: 2.6, max: 15, viewport: se), 9.75, "pad-y, iPhone SE")
        near(fluid(min: 22, vw: 6.5, max: 36, viewport: se), 24.375, "pad-x, iPhone SE")
    }

    /* ---- The trap D16 exists to prevent ----------------------------------- */

    /// `index.css` caps the card at 480 px while `vw` keeps growing. On an iPad
    /// the hub title is 44 pt (8 vw of 1024 = 81.92, clamped) — NOT 38.4 pt,
    /// which is what a `GeometryReader` around the 480 pt card would produce.
    /// A naive port passes every other test in this file and fails this one.
    @Test("vw resolves against the window, not the 480 pt card")
    func viewportIsTheWindowNotTheCard() {
        let card = Shell.cardWidth(viewport: pad)
        near(card, 480, "iPad card width")

        let correct = fluid(min: 28, vw: 8, max: 44, viewport: pad)
        let wrong = fluid(min: 28, vw: 8, max: 44, viewport: card)
        near(correct, 44, "title against the window")
        near(wrong, 38.4, "title against the card — the bug")
        #expect(correct != wrong)
    }

    @Test("the card shell mirrors index.css")
    func cardShell() {
        // #root padding floor is 16 on each side; #root > * caps at 480.
        near(Shell.cardWidth(viewport: 375), 343, "iPhone SE")   // 375 − 32
        near(Shell.cardWidth(viewport: 402), 370, "iPhone 17 Pro")
        near(Shell.cardWidth(viewport: 1024), 480, "iPad")        // capped
        #expect(Shell.cardMaxWidth == 480)
        #expect(Shell.minimumInset == 16)
        #expect(Shell.minimumScreenHeight == 620)
    }

    /* ---- Percentage clamps ------------------------------------------------ */

    /// The Dashboard's mascot pedestal, `clamp(190px, 62%, 300px)`, is the one
    /// clamp resolved against the CONTAINER. 480 × 0.62 = 297.6.
    @Test("fluidPercent resolves against the container")
    func pedestal() {
        near(fluidPercent(min: 190, percent: 62, max: 300, of: 480), 297.6, "480 pt card")
        near(fluidPercent(min: 190, percent: 62, max: 300, of: 343), 212.66, "343 pt card")
        near(fluidPercent(min: 190, percent: 62, max: 300, of: 600), 300, "over the max")
        near(fluidPercent(min: 190, percent: 62, max: 300, of: 280), 190, "under the min")
    }

    /* ---- The spec value type ---------------------------------------------- */

    @Test("FluidSpec resolves identically to the free function")
    func specMatchesFunction() {
        let spec = FluidSpec(min: 92, vw: 27, max: 150)
        for w: CGFloat in [200, 320, 375, 402, 480, 640, 1024, 1400] {
            near(spec.resolve(viewport: w), fluid(min: 92, vw: 27, max: 150, viewport: w), "at \(w)")
        }
    }
}
