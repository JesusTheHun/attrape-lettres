import CoreGraphics
import QuartzCore
import SwiftUI
import Testing

import ALArt
import ALCore
@testable import ALUI

// EarnBadge, EndButtons, Finished, WordIcon, Ollie and the pop flourish, all
// asserted against the TypeScript. Sources for every number:
//
//   EarnBadge.tsx    padding clamp(8,2.4vw,14) clamp(18,5vw,30)
//                    fontSize clamp(28,8vw,46), gap-2
//                    boxShadow "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)"
//                    aria-label `Tu gagnes ${earned} étoiles`, text `+${earned}`
//   EndButtons.tsx   mt-1, gap-3; Menu px-7 py-4 text-xl bg-white/80 shadow
//                    Suivant px-9 py-4 text-2xl bg-[#66BB6A]
//                    boxShadow "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)"
//   Finished.tsx     gap-5, px-6, z-[41]; 🤩 clamp(64,20vw,110)
//                    stars fontSize 28, gap-1, grayscale(1)/opacity .45
//                    title clamp(26,7vw,40); pill shown only when earned > 0
//   WordIcon.tsx     img → <img>, else <span aria-hidden>{emoji}</span>
//   Mascot.tsx       RAINBOW_IDS = [ACCESSORY.unicorn.starClip]
//                    mask x=-120 y=-120 width=340 height=340
//
// Tailwind spacing is n × 4 px: gap-1 = 4, mt-1 = 4, gap-2 = 8, gap-3 = 12,
// py-4 = 16, gap-5 = 20, px-6 = 24, px-7 = 28, px-9 = 36.

private let awake = FixedReduceMotion(false)
private let asleep = FixedReduceMotion(true)

// MARK: - EarnBadge

@Suite("EarnBadge — EarnBadge.tsx")
@MainActor
struct EarnBadgeTests {

    @Test("the pill shows the reward it was handed, and only that")
    func showsTheValue() {
        // Invariant 8: EarnBadge DISPLAYS what `sessionReward` returned. Not a
        // single point is computed, adjusted or rounded on the way in.
        #expect(EarnBadge(earned: 0, reduceMotion: awake).amountText == "+0")
        #expect(EarnBadge(earned: 1, reduceMotion: awake).amountText == "+1")
        #expect(EarnBadge(earned: 7, reduceMotion: awake).amountText == "+7")
        #expect(EarnBadge(earned: 46, reduceMotion: awake).amountText == "+46")
        #expect(EarnBadge(earned: 1_234, reduceMotion: awake).amountText == "+1234")
    }

    @Test("the aria-label is the TSX's, always plural")
    func label() {
        // `aria-label={`Tu gagnes ${earned} étoiles`}` — plural even for +1,
        // as authored.
        #expect(EarnBadge(earned: 0, reduceMotion: awake).accessibilityText == "Tu gagnes 0 étoiles")
        #expect(EarnBadge(earned: 1, reduceMotion: awake).accessibilityText == "Tu gagnes 1 étoiles")
        #expect(EarnBadge(earned: 12, reduceMotion: awake).accessibilityText == "Tu gagnes 12 étoiles")
    }

    @Test("the clamps are the authored ones")
    func clamps() {
        #expect(EarnBadge.fontSize == FluidSpec(min: 28, vw: 8, max: 46))
        #expect(EarnBadge.paddingY == FluidSpec(min: 8, vw: 2.4, max: 14))
        #expect(EarnBadge.paddingX == FluidSpec(min: 18, vw: 5, max: 30))
        #expect(EarnBadge.gap == 8)   // gap-2

        // 8vw of a 390 pt phone is 31.2, inside [28, 46].
        #expect(abs(EarnBadge.fontSize.resolve(viewport: 390) - 31.2) < 1e-9)
        // …of a 320 pt phone it is 25.6, so the floor wins.
        #expect(EarnBadge.fontSize.resolve(viewport: 320) == 28)
        // …of a 1024 pt iPad it is 81.9, so the cap wins. `vw` is the WINDOW,
        // not the 480 pt card (D16).
        #expect(EarnBadge.fontSize.resolve(viewport: 1024) == 46)
    }

    @Test("the two box-shadows are the authored ones")
    func shadows() {
        // "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)"
        #expect(EarnBadge.lipDrop == 8)
        #expect(EarnBadge.softShadow == CSSShadow(y: 16, blur: 26, opacity: 0.2))
        #expect(EarnBadge.softShadow.swiftUIRadius == 13)
        // The lip's colour and the gradient come from the shared tokens.
        #expect(Palette.goldLip.hex == "#E0A800")
        #expect(Palette.goldInk.hex == "#4A3B00")
    }
}

// MARK: - EndButtons

@Suite("EndButtons — EndButtons.tsx")
@MainActor
struct EndButtonsTests {

    @Test("the French copy is byte-exact, emoji included")
    func copy() {
        #expect(Copy.EndButtons.menu == "🏠 Menu")
        #expect(Copy.EndButtons.next == "🎉 Suivant")
    }

    @Test("the Tailwind spacing resolves to the authored pixels")
    func spacing() {
        #expect(EndButtons.gap == 12)            // gap-3
        #expect(EndButtons.topMargin == 4)       // mt-1
        #expect(EndButtons.menuPaddingX == 28)   // px-7
        #expect(EndButtons.menuPaddingY == 16)   // py-4
        #expect(EndButtons.nextPaddingX == 36)   // px-9
        #expect(EndButtons.nextPaddingY == 16)   // py-4
    }

    @Test("Suivant carries the primary button's lip, Menu carries Tailwind's shadow")
    func shadows() {
        // "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)"
        #expect(EndButtons.nextLipDrop == 8)
        #expect(EndButtons.nextSoftShadow == CSSShadow(y: 14, blur: 24, opacity: 0.2))
        #expect(EndButtons.nextSoftShadow.swiftUIRadius == 12)
        #expect(Palette.green.hex == "#66BB6A")
        #expect(Palette.greenLip.hex == "#43A047")
        // `shadow`: 0 1px 3px 0 rgb(0 0 0 / 0.1)
        #expect(EndButtons.menuShadow == CSSShadow(y: 1, blur: 3, opacity: 0.1))
        // `bg-white/80`
        #expect(Palette.White.o80 == 0.80)
    }

    @Test("callbacks are the caller's, and both are stored, not swapped")
    func callbacks() {
        var menu = 0
        var next = 0
        let buttons = EndButtons(onMenu: { menu += 1 }, onNext: { next += 1 })
        buttons.onMenu()
        #expect(menu == 1)
        #expect(next == 0)
        buttons.onNext()
        buttons.onNext()
        #expect(menu == 1)
        #expect(next == 2)
    }
}

// MARK: - ComponentsWrapRow (flex-wrap)

@Suite("flex-wrap")
struct WrapRowTests {

    private func box(_ w: CGFloat, _ h: CGFloat = 20) -> CGSize { CGSize(width: w, height: h) }

    @Test("a row that fits stays on one line")
    func oneLine() {
        let rows = ComponentsWrapRow.lines(
            sizes: [box(120), box(180)],
            maxWidth: 400,
            spacing: 12
        )
        #expect(rows.count == 1)
        #expect(rows[0].width == 312)   // 120 + 12 + 180
        #expect(rows[0].indices == [0, 1])
    }

    @Test("a row that overflows drops to a second line rather than shrinking")
    func wraps() {
        // An HStack would squash « 🎉 Suivant »; `flex-wrap` moves it down.
        let rows = ComponentsWrapRow.lines(
            sizes: [box(120), box(180)],
            maxWidth: 300,
            spacing: 12
        )
        #expect(rows.count == 2)
        #expect(rows[0].indices == [0])
        #expect(rows[1].indices == [1])
        #expect(rows[1].width == 180)
    }

    @Test("a line takes its tallest child's height")
    func lineHeight() {
        let rows = ComponentsWrapRow.lines(
            sizes: [box(50, 20), box(50, 44), box(50, 30)],
            maxWidth: 400,
            spacing: 4
        )
        #expect(rows.count == 1)
        #expect(rows[0].height == 44)
    }

    @Test("a run of recap stars wraps only when the card runs out")
    func starRecapWraps() {
        // 28 pt stars, gap-1 = 4, inside a 480 pt card less `px-6` on both
        // sides: 432 pt fits 13 before the 14th wraps (13×28 + 12×4 = 412).
        let stars = Array(repeating: CGSize(width: 28, height: 28), count: 14)
        let rows = ComponentsWrapRow.lines(sizes: stars, maxWidth: 432, spacing: 4)
        #expect(rows.count == 2)
        #expect(rows[0].indices.count == 13)
        #expect(rows[1].indices.count == 1)
    }

    @Test("an empty row produces no lines")
    func empty() {
        #expect(ComponentsWrapRow.lines(sizes: [], maxWidth: 400, spacing: 4).isEmpty)
    }
}

// MARK: - Finished

@Suite("Finished — Finished.tsx")
@MainActor
struct FinishedTests {

    @Test("the earn pill shows exactly the points it was given")
    func earnedIsPassedThrough() {
        // Invariant 8 again, one level up: Finished does not adjust the reward
        // on the way to the pill.
        for earned in [1, 2, 3, 5, 8, 13, 46, 999] {
            #expect(Finished.display(stars: [true], earned: earned, title: "t").earned == earned)
        }
    }

    @Test("a training run (0 points) hides the pill instead of showing a zero")
    func zeroHidesThePill() {
        // « Training exercises (difficulty 0) earn nothing — the points pill
        // hides and the cheer IS the reward, so a 0 never reads as a
        // punishment. »
        #expect(Finished.display(stars: [true], earned: 0, title: "t").earned == nil)
        // …and one single point is still worth a pill.
        #expect(Finished.display(stars: [true], earned: 1, title: "t").earned == 1)
    }

    @Test("the star array is rendered as handed in — lost rounds stay on screen")
    func starsAreNotFiltered() {
        // Invariants 3 and 8: a greyed star is still DRAWN; the round counts as
        // played. Nothing here may compact, sort or drop an entry.
        let stars = [true, false, false, true, false, true]
        let model = Finished.display(stars: stars, earned: 4, title: "Tu as tout lu !")
        #expect(model.stars == stars)
        #expect(model.stars.count == 6)
        #expect(model.title == "Tu as tout lu !")

        let allLost = [false, false, false]
        #expect(Finished.display(stars: allLost, earned: 0, title: "t").stars == allLost)
    }

    @Test("the view's model is the static one — the body has no second opinion")
    func viewUsesTheModel() {
        let view = Finished(
            stars: [true, false],
            earned: 0,
            title: Copy.Finished.allFound,
            reduceMotion: awake,
            onMenu: {},
            onNext: {}
        )
        #expect(view.display == Finished.display(stars: [true, false], earned: 0, title: Copy.Finished.allFound))
        #expect(view.display.earned == nil)
    }

    @Test("the layout numbers are the authored ones")
    func metrics() {
        #expect(Finished.gap == 20)        // gap-5
        #expect(Finished.paddingX == 24)   // px-6
        #expect(Finished.starGap == 4)     // gap-1
        #expect(Finished.starSize == 28)   // a plain 28, not a clamp
        #expect(Finished.zIndex == 41)     // z-[41], one above the confetti's 40
        #expect(Finished.cheerSize == FluidSpec(min: 64, vw: 20, max: 110))
        #expect(Finished.titleSize == FluidSpec(min: 26, vw: 7, max: 40))
        // 20vw of 390 = 78; 7vw of 390 = 27.3.
        #expect(abs(Finished.cheerSize.resolve(viewport: 390) - 78) < 1e-9)
        #expect(abs(Finished.titleSize.resolve(viewport: 390) - 27.3) < 1e-9)
    }

    @Test("a lost star is greyed, not hidden")
    func lostStarStyling() {
        // `{ filter: "grayscale(1)", opacity: 0.45 }`
        #expect(Palette.Lost.saturation == 0)
        #expect(Palette.Lost.opacity == 0.45)
        #expect(Palette.Lost.opacity > 0, Comment(rawValue: "the round still counts as played"))
    }

    @Test("the three end-of-run titles are byte-exact")
    func titles() {
        #expect(Copy.Finished.allFound == "Tu as tout trouvé !")
        #expect(Copy.Finished.allSucceeded == "Tu as tout réussi !")
        #expect(Copy.Finished.allRead == "Tu as tout lu !")
        #expect(Copy.Finished.cheerEmoji == "🤩")
    }
}

// MARK: - WordIcon

@Suite("WordIcon — WordIcon.tsx")
@MainActor
struct WordIconTests {

    @Test("every ImageKey ALCore defines resolves to a real drawing")
    func everyKeyDraws() {
        // The `img` branch delegates to `WordImages.draw`, whose `switch` has no
        // `default:` — a new key is a compile error there, never a picture that
        // silently renders nothing. This asserts the other half: that each of
        // the four cases actually produces geometry.
        #expect(ImageKey.allCases.count == 4)
        for key in ImageKey.allCases {
            let nodes = WordIcon.drawList(for: key)
            #expect(!nodes.isEmpty, Comment(rawValue: "\(key) drew nothing"))
        }
    }

    @Test("the four drawings are four different pictures")
    func keysAreDistinct() {
        let counts = ImageKey.allCases.map { WordIcon.drawList(for: $0).count }
        // Not a strong claim on its own — the strong one is that the labels are
        // distinct and each list is non-trivial.
        #expect(counts.allSatisfy { $0 > 5 })
        let labels = ImageKey.allCases.map { WordImages.label($0) }
        #expect(Set(labels).count == 4)
        #expect(labels == ["Igloo", "Jupe", "Macaron", "Pyjama"])
    }

    @Test("the emoji is kept as the fallback even when an illustration wins")
    func emojiSurvives() {
        // « `emoji` stays as the text fallback so nothing ever renders blank. »
        let icon = WordIcon(emoji: "🧊", img: .igloo, size: 80)
        #expect(icon.emoji == "🧊")
        #expect(icon.img == .igloo)
        let bare = WordIcon(emoji: "🐘", size: 80)
        #expect(bare.img == nil)
    }

    @Test("alt is decorative by default")
    func altDefaultsToDecorative() {
        // `alt = ""` in the signature: « Decorative by default (aria-hidden /
        // empty alt) — every call site already labels the word in text or an
        // aria-label on the surrounding tile/button. »
        #expect(WordIcon(emoji: "👗", img: .jupe, size: 60).alt == "")
        #expect(WordIcon(emoji: "👗", img: .jupe, size: 60, alt: "Jupe").alt == "Jupe")
    }

    @Test("the emoji span's line-height is the authored 1.1")
    func emojiLineHeight() {
        #expect(WordIcon.emojiLineHeight == 1.1)
    }
}

// MARK: - Ollie (the mascot host)

@Suite("Ollie — Mascot.tsx's shell")
@MainActor
struct OllieTests {

    private func look(_ accessories: [String], species: Species = .unicorn, stage: Int = 6) -> MascotConfig {
        MascotConfig(
            species: species,
            stage: stage,
            colors: ["bodyColor": "#FFD1E8"],
            styles: ["tailSize": "long"],
            accessories: accessories
        )
    }

    @Test("RAINBOW_IDS is the one premium whole-image accessory")
    func rainbowIDs() {
        // `const RAINBOW_IDS: string[] = [ACCESSORY.unicorn.starClip];`
        #expect(Ollie.rainbowIDs == ["unicorn.accessory.star-clip"])
        #expect(Ollie.rainbowIDs == [Accessory.Unicorn.starClip])
    }

    @Test("the drawing canvas covers the web's own mask window")
    func overflowWindow() {
        // `<mask maskUnits="userSpaceOnUse" x={-120} y={-120} width={340} height={340}>`
        #expect(Ollie.overflowViewBox == CGRect(x: -120, y: -120, width: 340, height: 340))
        #expect(Ollie.overflowFactor == 3.4)
        // …so the wings at stade 9 have 120 units of room on every side, which
        // a `size × size` Canvas would have clipped.
        #expect(Ollie.overflowViewBox.minX < 0)
        #expect(Ollie.overflowViewBox.maxX > 100)
    }

    @Test("the rainbow is detected exactly when it is worn")
    func detectsRainbow() {
        #expect(Ollie(config: look([Accessory.Unicorn.starClip]), mood: .idle, reduceMotion: awake).wearsRainbow)
        #expect(!Ollie(config: look([]), mood: .idle, reduceMotion: awake).wearsRainbow)
        #expect(
            !Ollie(config: look([Accessory.Unicorn.flowerCrown]), mood: .idle, reduceMotion: awake)
                .wearsRainbow
        )
    }

    @Test("splitting the band off the rig changes nothing else about the look")
    func stripKeepsEverythingElse() {
        let worn = [Accessory.Unicorn.flowerCrown, Accessory.Unicorn.starClip, Accessory.Unicorn.ribbon]
        let view = Ollie(config: look(worn), mood: .happy, reduceMotion: awake)
        let stripped = view.rigConfig
        // Only the whole-image id is gone, and the remaining order is intact.
        #expect(stripped.accessories == [Accessory.Unicorn.flowerCrown, Accessory.Unicorn.ribbon])
        #expect(stripped.species == view.config.species)
        #expect(stripped.stage == view.config.stage)
        #expect(stripped.colors == view.config.colors)
        #expect(stripped.styles == view.config.styles)
    }

    @Test("a look with no rainbow is handed to the rig untouched")
    func noRainbowNoChange() {
        let plain = look([Accessory.Unicorn.flowerCrown])
        let view = Ollie(config: plain, mood: .idle, reduceMotion: awake)
        #expect(view.rigConfig == plain)
        // …and the rig draws it identically either way.
        #expect(
            MascotRig.drawList(config: view.rigConfig, mood: .idle).count
                == MascotRig.drawList(config: plain, mood: .idle).count
        )
    }

    @Test("the stripped rig really is the rig without the baked sheen")
    func strippingRemovesTheBakedBand() {
        let worn = look([Accessory.Unicorn.starClip])
        let view = Ollie(config: worn, mood: .idle, reduceMotion: awake)
        let withBand = MascotRig.drawList(config: worn, mood: .idle)
        let withoutBand = MascotRig.drawList(config: view.rigConfig, mood: .idle)
        // ALArt bakes the masked band into the draw list when the accessory is
        // equipped; this view has to take it out before it can sweep it.
        #expect(withBand.count > withoutBand.count)
        // The `star-clip` is not a worn part, so what is left is the plain pet.
        #expect(withoutBand.count == MascotRig.drawList(config: look([]), mood: .idle).count)
    }

    @Test("the sheen sweep is the CSS one, and reduced motion parks it visible")
    func sheenMotion() {
        // `@keyframes alSheen { from: translateX(-150px); to: translateX(150px) }`
        // `.al-sheen { animation: alSheen 3.6s linear infinite; }`
        #expect(MascotSheen.fromUnits == -150)
        #expect(MascotSheen.toUnits == 150)
        #expect(MascotSheen.duration == 3.6)
        // Invariant 6: `animation: none` leaves the band at its BASE transform —
        // a static rainbow across the pet, not a hidden one and not one parked
        // off-screen at −150.
        #expect(MascotSheen.offsetUnits(at: 1.2, reduceMotion: true) == 0)
        #expect(MascotSheen.offsetUnits(at: 1.2, reduceMotion: false) != 0)
    }

    @Test("preview never animates and pins the growth scale")
    func previewIsStill() {
        // « Shop thumbnails never bob — a grid of jittering mascots is noise,
        // not signal. »
        #expect(MascotMotion.plan(mood: .idle, preview: true, reduceMotion: false) == nil)
        #expect(MascotMotion.plan(mood: .cheer, preview: true, reduceMotion: false) == nil)
        // …and reduced motion starts NOTHING, not something shorter.
        #expect(MascotMotion.plan(mood: .idle, preview: false, reduceMotion: true) == nil)
        #expect(MascotMotion.plan(mood: .idle, preview: false, reduceMotion: false) != nil)
    }

    @Test("the default size is the TSX's 88")
    func defaultSize() {
        #expect(Ollie(config: look([]), mood: .idle, reduceMotion: awake).size == 88)
        #expect(!Ollie(config: look([]), mood: .idle, reduceMotion: awake).preview)
        #expect(Ollie(config: look([]), mood: .idle, reduceMotion: awake).focus == nil)
    }

    @Test("the accessibility label comes from the rig, not from this file")
    func label() {
        #expect(MascotRig.label(for: .unicorn) == "Ma licorne")
        #expect(MascotRig.label(for: .dragon) == "Mon dragon")
    }
}

// MARK: - PopFlourish

@Suite("PopFlourish — usePopFlourish.ts")
@MainActor
struct PopFlourishTests {

    @Test("the flourish runs once, on a real layer")
    func runsOnce() {
        let layer = CALayer()
        Anim.pop(layer, reduceMotion: awake)
        let animation = layer.animation(forKey: "ALPop")
        #expect(animation != nil)
        #expect(animation?.duration == 0.48)
        #expect(animation?.repeatCount == 0, Comment(rawValue: "one-shot, not a loop"))
    }

    @Test("the flourish does not run under reduced motion — invariant 6")
    func reducedMotionGate() {
        // `if (reduce) return;` before `el.animate(...)`.
        let layer = CALayer()
        Anim.pop(layer, reduceMotion: asleep)
        #expect(layer.animation(forKey: "ALPop") == nil)
        #expect(layer.animationKeys() == nil)
    }

    @Test("a caller's continuation still fires when nothing animates")
    func completionAlwaysFires() {
        // Gameplay sequenced behind a flourish must never stall for a child
        // with reduced motion on.
        var fired = 0
        Anim.pop(CALayer(), reduceMotion: asleep) { fired += 1 }
        #expect(fired == 1)
        // …and with no layer at all (the macOS host path).
        Anim.pop(nil, reduceMotion: awake) { fired += 1 }
        #expect(fired == 2)
    }
}

// MARK: - Rasterisation
//
// Three claims that only pixels can settle: that `WordIcon`'s Canvas is wired
// to the right viewBox, that `Ollie` does NOT clip the overflowing rig, and
// that the confetti actually paints its palette. `ImageRenderer` runs on the
// macOS host, so these live with the rest of the host suite.

#if canImport(AppKit) || canImport(UIKit)

@MainActor
private func raster(_ size: CGSize, @ViewBuilder _ content: () -> some View) -> (px: [UInt8], w: Int, h: Int)? {
    let renderer = ImageRenderer(content: content().frame(width: size.width, height: size.height))
    renderer.scale = 1
    guard let cg = renderer.cgImage else { return nil }
    let w = cg.width, h = cg.height
    var buffer = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(
        data: &buffer, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    return (buffer, w, h)
}

/// Opaque-ish pixels, counted inside a rect.
private func painted(
    _ r: (px: [UInt8], w: Int, h: Int),
    in box: CGRect
) -> Int {
    var count = 0
    for y in Swift.max(0, Int(box.minY))..<Swift.min(r.h, Int(box.maxY)) {
        for x in Swift.max(0, Int(box.minX))..<Swift.min(r.w, Int(box.maxX)) {
            if r.px[(y * r.w + x) * 4 + 3] > 24 { count += 1 }
        }
    }
    return count
}


/// The bounding box of everything painted — insensitive to how many pixels an
/// edge antialiases into, which is what makes it comparable across two
/// differently-sized canvases.
@MainActor
private func inkBounds(_ r: (px: [UInt8], w: Int, h: Int)) -> CGRect? {
    var minX = r.w, minY = r.h, maxX = -1, maxY = -1
    for y in 0..<r.h {
        for x in 0..<r.w where r.px[(y * r.w + x) * 4 + 3] > 24 {
            minX = Swift.min(minX, x); maxX = Swift.max(maxX, x)
            minY = Swift.min(minY, y); maxY = Swift.max(maxY, y)
        }
    }
    guard maxX >= 0 else { return nil }
    return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
}

@Suite("Components — rasterisation", .serialized)
@MainActor
struct ComponentRenderTests {

    @Test("every word illustration renders something inside its box")
    func wordIconsDraw() throws {
        for key in ImageKey.allCases {
            let shot = try #require(raster(CGSize(width: 80, height: 80)) {
                WordIcon(emoji: "❌", img: key, size: 80)
            })
            let ink = painted(shot, in: CGRect(x: 0, y: 0, width: 80, height: 80))
            #expect(ink > 500, Comment(rawValue: "\(key) rendered \(ink) painted pixels"))
        }
    }

    @Test("Ollie does not clip the rig at the layout box — `overflow: visible`")
    func overflowIsVisible() throws {
        // A stade-9 unicorn's wings and halo deliberately reach outside the
        // 0…100 viewBox (spec §6), and `overflow: visible` on the `<svg>` lets
        // them. This view redraws the rig into the web's own mask window
        // (−120…220) and pins the LAYOUT box back to `size × size`, so the two
        // claims below are: the pet lands exactly where ALArt puts it, and it
        // is not shorn off at the box edge.
        let config = MascotConfig(
            species: .unicorn,
            stage: 9,
            colors: [:],
            styles: [:],
            accessories: []
        )
        // 100 pt so the oversized canvas (× 3.4) is a whole 340, and both
        // renders land on integer pixel boundaries — a half-pixel offset alone
        // shifts every antialiased edge and would swamp the comparison.
        let side: CGFloat = 100
        let canvas = CGSize(width: 400, height: 400)
        let all = CGRect(origin: .zero, size: canvas)
        let box = CGRect(
            x: (canvas.width - side) / 2,
            y: (canvas.height - side) / 2,
            width: side,
            height: side
        )

        let host = try #require(raster(canvas) {
            Ollie(config: config, mood: .idle, size: side, reduceMotion: asleep)
        })
        let art = try #require(raster(canvas) {
            MascotRigView(config: config, mood: .idle, size: side)
        })

        #expect(painted(host, in: box) > 1_000, Comment(rawValue: "the pet itself must be drawn"))
        #expect(
            painted(host, in: all) > painted(host, in: box),
            Comment(rawValue: "the wings hang outside the 100 pt layout box")
        )
        // Re-mapping the viewBox from 0…100 to −120…220 must not move or resize
        // the pet by a point: the ink's bounding box has to land in the same
        // place as ALArt's own view puts it. (Coverage COUNTS are not compared
        // — the two renders antialias slightly differently — but the extent is
        // exactly what a wrong viewBox or a wrong canvas size would change.)
        let hostBounds = try #require(inkBounds(host))
        let artBounds = try #require(inkBounds(art))
        // Same scale, same place: the pet's ink starts and ends on the same
        // columns and the same bottom row in both renders. A wrong viewBox or a
        // wrong canvas size would move all three.
        #expect(abs(hostBounds.minX - artBounds.minX) <= 2)
        #expect(abs(hostBounds.maxX - artBounds.maxX) <= 2)
        #expect(abs(hostBounds.maxY - artBounds.maxY) <= 2)

        // …and the top is where they differ: ALArt's `size × size` Canvas cuts
        // the stade-9 halo off, this one keeps it. That is the whole reason
        // this file re-hosts the rig instead of using `MascotRigView` directly.
        #expect(
            hostBounds.minY < artBounds.minY - 8,
            Comment(rawValue: "host \(hostBounds) vs art \(artBounds)")
        )
        #expect(hostBounds.minY < box.minY)
        #expect(hostBounds.width > box.width)
    }

    @Test("the rainbow sheen stays inside the pet's silhouette")
    func sheenIsMasked() throws {
        // « Arc-en-ciel magique » is masked to the rig, so it must ADD COLOUR
        // and NO COVERAGE — it may never spill onto the card or hard-cut at the
        // viewBox edge. Reduced motion parks the band at its base transform,
        // which is the frame this asserts (and the one the D3 harness shoots).
        let plain = MascotConfig(species: .unicorn, stage: 7, colors: [:], styles: [:], accessories: [])
        var rainbow = plain
        rainbow.accessories = [Accessory.Unicorn.starClip]

        let canvas = CGSize(width: 300, height: 300)
        let all = CGRect(origin: .zero, size: canvas)

        let bare = try #require(raster(canvas) {
            Ollie(config: plain, mood: .idle, size: 88, reduceMotion: asleep)
        })
        let lit = try #require(raster(canvas) {
            Ollie(config: rainbow, mood: .idle, size: 88, reduceMotion: asleep)
        })

        let bareInk = Double(painted(bare, in: all))
        let litInk = Double(painted(lit, in: all))
        #expect(bareInk > 1_000)
        #expect(
            abs(litInk - bareInk) / bareInk < 0.02,
            Comment(rawValue: "the band covered \(litInk) vs \(bareInk) — it escaped the mask")
        )
        // …and it did change the picture: the sheen is really there.
        var differing = 0
        for index in stride(from: 0, to: bare.px.count, by: 4) where bare.px[index] != lit.px[index] {
            differing += 1
        }
        #expect(differing > 200, Comment(rawValue: "the band tinted \(differing) pixels"))
    }

    @Test("the confetti paints its flakes")
    func confettiPaints() throws {
        let confetti = ConfettiSystem(random: .seeded(99), reduceMotion: FixedReduceMotion(false))
        confetti.report(size: CGSize(width: 300, height: 300), pixelScale: 1)
        confetti.fire()
        let shot = try #require(raster(CGSize(width: 300, height: 300)) {
            Canvas { context, _ in
                var ctx = context
                confetti.draw(into: &ctx)
            }
        })
        // 90 flakes, 5–11 pt wide and 0.6 as tall.
        let ink = painted(shot, in: CGRect(x: 0, y: 0, width: 300, height: 300))
        #expect(ink > 500, Comment(rawValue: "painted \(ink) pixels"))

        // …and nothing at all once the buffer is empty.
        confetti.reset()
        let blank = try #require(raster(CGSize(width: 300, height: 300)) {
            Canvas { context, _ in
                var ctx = context
                confetti.draw(into: &ctx)
            }
        })
        #expect(painted(blank, in: CGRect(x: 0, y: 0, width: 300, height: 300)) == 0)
    }
}

#endif
