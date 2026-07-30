import QuartzCore
import SwiftUI
import Testing

import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The mascot shop, tested against `src/shop/Shop.tsx` + `Shop.test.tsx`.       */
/*                                                                             */
/* Every expected value below is derived FROM THE TYPESCRIPT — catalog prices,   */
/* the 30·(stage+1) growth curve, the flight-size clamp, the wallet-count       */
/* easing, the anim.ts particle geometry, the French state labels — never read   */
/* back out of the Swift under test.                                           */
/* -------------------------------------------------------------------------- */

// MARK: - Doubles

/// Records everything the shop plays or says. `say` appends on the main actor
/// so a test that drains the task queue observes a settled transcript.
private final class ShopAudioSpy: AudioEngine, @unchecked Sendable {
    private(set) var unlocks = 0
    private(set) var pops = 0
    private(set) var successes = 0
    private(set) var nudges = 0
    private(set) var spoken: [String] = []

    func unlock() { unlocks += 1 }
    func pop() { pops += 1 }
    func success() { successes += 1 }
    func nudge() { nudges += 1 }
    func oops() {}

    @discardableResult
    func say(_ text: String, rate: Double, pitch: Double) async -> Bool {
        await MainActor.run { spoken.append(text) }
        return true
    }

    func stop() {}
}


// MARK: - Harness

/// The web test's `ShopHarness`: boot a child, award six distinct first-clears
/// of a PAYING exercise (difficulty 1, no perfect-round bonus) = 6 × 10 = 60
/// pts — "plenty for a 40-pt style".
@MainActor
private struct Harness {
    let kv = InMemoryKVStore()
    let audio = ShopAudioSpy()
    let store: ProfileStore
    let model: ShopModel

    init(awardLevels: Int = 6) {
        store = ProfileStore(kv: kv, device: { "test-device" }, now: { 1_000 })
        store.createChild(name: "Test")
        for level in 0..<awardLevels {
            store.award(exercise: .orderSyllables, level: level, perfectRounds: 0, totalRounds: 1)
        }
        model = ShopModel(store: store, audio: audio, kv: kv)
    }

    /// A catalog row by its French shop label, for the active species.
    func option(_ name: String) -> CustomizationOption? {
        MascotCatalog.catalog.first {
            $0.species == store.profile.config.species && $0.name == name
        }
    }

    func defaultLook(_ name: String) -> DefaultLook? {
        MascotCatalog.defaultLooks[store.profile.config.species]?.first { $0.name == name }
    }

    func itemSurface(_ option: CustomizationOption) -> ShopItemSurface {
        ShopItemSurface(option: option, profile: store.profile, cartId: model.cart?.id)
    }
}

// MARK: - Economy harness

@Suite("Shop — economy harness")
@MainActor
struct ShopHarnessTests {

    @Test("six distinct first-clears of a difficulty-1 exercise pay 6 × 10 = 60")
    func sixFirstClearsPaySixty() {
        let h = Harness()
        #expect(h.store.profile.balance == 60)
        #expect(h.store.profile.config.species == .unicorn)
        #expect(h.store.profile.stars.earned["test-device"] == 60)
        #expect(h.store.profile.stars.spent["test-device"] == nil)
    }
}

// MARK: - Try-on before buy (Shop.test.tsx, block 1)

@Suite("Shop — try-on before buy")
@MainActor
struct ShopTryOnTests {

    @Test("trying on opens the dialog but spends nothing")
    func tryOnSpendsNothing() async throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        h.model.tapItem(curly)

        #expect(h.model.cart == curly)
        #expect(h.model.cartAffordable)
        #expect(h.store.profile.balance == 60)
        #expect(h.store.profile.config.styles.isEmpty, Comment(rawValue: "nothing equipped yet"))
        #expect(h.store.profile.owned.isEmpty)
        #expect(h.itemSurface(curly).trying)
        #expect(h.audio.unlocks == 1)
        #expect(h.audio.pops == 1)

        await h.model.speechTask?.value
        #expect(h.audio.spoken == ["Ça coûte 40 étoiles."])
    }

    @Test("the buy button spends, equips, and closes the dialog")
    func buySpendsEquipsCloses() async throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        h.model.tapItem(curly)
        await h.model.speechTask?.value
        h.model.confirmBuy()
        await h.model.speechTask?.value

        #expect(h.store.profile.config.styles["tailStyle"] == "curly")
        #expect(h.store.profile.owned == [curly.id])
        #expect(h.store.profile.balance == 20, Comment(rawValue: "60 − 40"))
        #expect(h.model.cart == nil)
        #expect(h.audio.successes == 1)
        #expect(h.audio.spoken == ["Ça coûte 40 étoiles.", "C'est à toi !"])
    }

    @Test("an unaffordable item can be tried on but not bought")
    func unaffordableTryOn() async throws {
        let h = Harness()
        let ring = try #require(h.option("Bouée"))  // 75 pts > 60 balance

        h.model.tapItem(ring)

        #expect(h.model.cart == ring)
        #expect(!h.model.cartAffordable)
        let surface = TryOnSurface(option: ring, balance: h.store.profile.balance)
        #expect(surface.buyLabel == "⭐ 75 \u{00B7} pas encore")
        #expect(surface.buyAccessibilityLabel == "Pas encore assez d'étoiles pour Bouée")
        #expect(surface.showsMeter)

        await h.model.speechTask?.value
        #expect(h.audio.spoken == ["Ça coûte 75 étoiles. Il te manque des étoiles."])

        // The disabled path is also fail-open at the model: a forced confirm is
        // a quiet no-op with the dialog still up — never a wedge, never a spend.
        h.model.confirmBuy()
        #expect(h.model.cart == ring)
        #expect(h.store.profile.balance == 60)
        #expect(h.store.profile.owned.isEmpty)
        #expect(h.audio.successes == 0)
    }

    @Test("the ✕ button cancels a try-on")
    func cancelTryOn() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        h.model.tapItem(curly)
        h.model.cancelTryOn()

        #expect(h.model.cart == nil)
        #expect(h.store.profile.balance == 60)
        #expect(h.store.profile.owned.isEmpty)
        #expect(!h.itemSurface(curly).trying)
    }

    @Test("the affordable dialog surface carries the TSX labels")
    func affordableDialogSurface() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        let surface = TryOnSurface(option: curly, balance: 60)
        #expect(surface.title == "Essayer Queue bouclée")
        #expect(surface.buyLabel == "Acheter \u{00B7} ⭐ 40")
        #expect(surface.buyAccessibilityLabel == "Acheter Queue bouclée pour 40 étoiles")
        #expect(!surface.showsMeter)
    }

    @Test("the spoken price line pluralises like the TSX (cost 1 = étoile)")
    func costLineSingular() {
        #expect(VO.shopCostLine(1) == "Ça coûte 1 étoile.")
        #expect(VO.shopCostLine(40) == "Ça coûte 40 étoiles.")
    }
}

// MARK: - The spend path (invariants 8 and 9)

@Suite("Shop — the spend path through ProfileStore")
@MainActor
struct ShopSpendPathTests {

    @Test("a purchase lands in the spent counter — points leave, none appear")
    func spendLandsInCounters() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        h.model.tapItem(curly)
        h.model.confirmBuy()

        #expect(h.store.profile.stars.spent["test-device"] == 40)
        #expect(h.store.profile.stars.earned["test-device"] == 60, Comment(rawValue: "earned is untouched by a spend"))
        #expect(h.store.profile.balance == 20)
    }

    @Test("tapping buy twice fast cannot spend twice")
    func doubleTapBuy() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        h.model.tapItem(curly)
        h.model.confirmBuy()
        h.model.confirmBuy()  // the cart is already empty — a quiet no-op

        #expect(h.store.profile.balance == 20)
        #expect(h.store.profile.owned == [curly.id], Comment(rawValue: "owned exactly once"))
        #expect(h.store.profile.stars.spent["test-device"] == 40)
    }

    @Test("even a racing second buy() is a free re-equip, never a second charge")
    func rapidDoubleBuyIsFree() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        // The web's protection when two clicks land before React re-renders:
        // buy() sees the item owned and re-equips without touching the wallet.
        #expect(h.store.buy(curly))
        #expect(h.store.buy(curly))

        #expect(h.store.profile.balance == 20)
        #expect(h.store.profile.owned == [curly.id])
        #expect(h.store.profile.stars.spent["test-device"] == 40)
    }

    @Test("buying with exactly enough succeeds and leaves zero")
    func exactlyEnough() async throws {
        let h = Harness()
        let swimsuit = try #require(h.option("Maillot de bain"))  // 60 = the balance

        h.model.tapItem(swimsuit)
        #expect(h.model.cartAffordable, Comment(rawValue: "balance >= cost is affordable, not >"))

        await h.model.speechTask?.value
        #expect(h.audio.spoken == ["Ça coûte 60 étoiles."], Comment(rawValue: "no 'il te manque' clause at exactly enough"))

        h.model.confirmBuy()
        #expect(h.store.profile.balance == 0)
        #expect(h.store.profile.owned == [swimsuit.id])
        #expect(h.model.cart == nil)
    }

    @Test("buying one short is a quiet, spend-free no-op that never wedges")
    func oneShort() throws {
        let h = Harness()
        h.store.spend(cost: 1)  // 59 left
        let swimsuit = try #require(h.option("Maillot de bain"))  // costs 60

        h.model.tapItem(swimsuit)
        #expect(!h.model.cartAffordable)

        h.model.confirmBuy()
        #expect(h.store.profile.balance == 59)
        #expect(h.store.profile.owned.isEmpty)
        #expect(h.model.cart == swimsuit, Comment(rawValue: "the dialog stays open — ✕ still leads out"))
        #expect(h.audio.successes == 0)

        h.model.cancelTryOn()
        #expect(h.model.cart == nil, Comment(rawValue: "and the child can always leave"))
    }

    @Test("nothing in the shop ever mints a point")
    func shopNeverMints() throws {
        let h = Harness()
        let bow = try #require(h.option("Nœud"))  // 45-pt accessory
        let lilas = try #require(h.defaultLook("Corps lilas"))
        let earnedBefore = h.store.profile.stars.earned

        h.model.tapItem(bow)
        h.model.confirmBuy()        // spend 45
        h.model.tapItem(bow)        // toggle the accessory off (free)
        h.model.tapItem(bow)        // and back on (free — already owned)
        h.model.clearSlot(lilas)    // factory look (free)
        h.model.grow()              // 15 < 30 — refused, spends nothing

        #expect(h.store.profile.stars.earned == earnedBefore, Comment(rawValue: "earned counters are read-only to the shop"))
        #expect(h.store.profile.balance == 15, Comment(rawValue: "60 − 45, nothing else moved"))
    }
}

// MARK: - Accessory toggle

@Suite("Shop — accessory toggle")
@MainActor
struct ShopAccessoryToggleTests {

    @Test("tapping a worn accessory takes it off; ownership and wallet survive")
    func toggleOffAndOn() throws {
        let h = Harness()
        let bow = try #require(h.option("Nœud"))

        h.model.tapItem(bow)
        h.model.confirmBuy()
        #expect(h.store.profile.config.accessories == [bow.id])
        #expect(h.store.profile.balance == 15)

        h.model.tapItem(bow)  // off
        #expect(h.store.profile.config.accessories.isEmpty)
        #expect(h.store.profile.owned == [bow.id], Comment(rawValue: "taking it off never un-buys it"))
        #expect(h.store.profile.balance == 15)

        h.model.tapItem(bow)  // back on, free
        #expect(h.store.profile.config.accessories == [bow.id])
        #expect(h.store.profile.balance == 15)
    }
}

// MARK: - Item states

@Suite("Shop — item states")
@MainActor
struct ShopItemSurfaceTests {

    @Test("an affordable unowned item shows the gold price tag")
    func affordableUnowned() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        let s = h.itemSurface(curly)
        #expect(!s.owned && !s.equipped && !s.locked && !s.trying && s.affordable)
        #expect(s.accessibilityLabel == "Queue bouclée, coûte 40 points")
        #expect(s.pricePanel == .affordable(badge: "⭐ 40"))
        #expect(s.sticker == .none)
        #expect(!s.showsMeter)
    }

    @Test("an unaffordable item shows the grey tag and the savings meter")
    func unaffordableShowsMeter() throws {
        let h = Harness()
        let ring = try #require(h.option("Bouée"))

        let s = h.itemSurface(ring)
        #expect(s.accessibilityLabel == "Bouée, coûte 75 points, pas encore assez")
        #expect(s.pricePanel == .unaffordable(badge: "⭐ 75"))
        #expect(s.showsMeter)
    }

    @Test("a growth-gated item is locked with the 🌱 chip — minStage 2 reads « niveau 3 »")
    func lockedItem() throws {
        let h = Harness()
        let horn = try #require(h.option("Corne rose"))  // minStage 2, cost 20

        let s = h.itemSurface(horn)
        #expect(s.locked)
        #expect(s.accessibilityLabel == "Corne rose, coûte 20 points, à débloquer au niveau 3")
        #expect(s.pricePanel == .locked(badge: "🌱 niv. 3 \u{00B7} ⭐ 20"))
    }

    @Test("the tile in the cart announces the try-on")
    func tryingState() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))
        h.model.tapItem(curly)

        #expect(h.itemSurface(curly).accessibilityLabel == "Queue bouclée, coûte 40 points, en train d'essayer")
    }

    @Test("a worn style is « équipé » with a filled ✓ and no price")
    func equippedStyle() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))
        h.model.tapItem(curly)
        h.model.confirmBuy()

        let s = h.itemSurface(curly)
        #expect(s.accessibilityLabel == "Queue bouclée, équipé")
        #expect(s.sticker == .check(filled: true))
        #expect(s.pricePanel == .none, Comment(rawValue: "a bought item never shows a number"))
    }

    @Test("an owned colour that is not worn is « à toi » with a white ✓")
    func ownedNotEquipped() throws {
        let h = Harness()
        let rose = try #require(h.option("Corps rose"))
        let ciel = try #require(h.option("Corps bleu ciel"))
        h.model.tapItem(rose)
        h.model.confirmBuy()
        h.model.tapItem(ciel)
        h.model.confirmBuy()

        let s = h.itemSurface(rose)
        #expect(s.owned && !s.equipped)
        #expect(s.accessibilityLabel == "Corps rose, à toi")
        #expect(s.sticker == .check(filled: false))
    }

    @Test("a worn accessory warns that a tap takes it off — the ✕ sticker")
    func removableAccessory() throws {
        let h = Harness()
        let bow = try #require(h.option("Nœud"))
        h.model.tapItem(bow)
        h.model.confirmBuy()

        let s = h.itemSurface(bow)
        #expect(s.removable)
        #expect(s.accessibilityLabel == "Nœud, équipé, appuie pour enlever")
        #expect(s.sticker == .remove)
    }
}

// MARK: - Factory looks

@Suite("Shop — factory look returns things to default")
@MainActor
struct ShopFactoryLookTests {

    @Test("the factory look starts « en place » and there is no 'Défaut' jargon")
    func freshFactoryLook() throws {
        let h = Harness()
        let straight = try #require(h.defaultLook("Queue lisse"))

        let s = DefaultLookSurface(look: straight, config: h.store.profile.config)
        #expect(s.active)
        #expect(s.badge == "Équipé ✓")
        #expect(s.accessibilityLabel == "Queue lisse, en place")
    }

    @Test("switches a style, then the named default look reverts it")
    func styleRoundTrip() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))
        let straight = try #require(h.defaultLook("Queue lisse"))

        h.model.tapItem(curly)
        h.model.confirmBuy()
        #expect(h.itemSurface(curly).equipped)
        var s = DefaultLookSurface(look: straight, config: h.store.profile.config)
        #expect(!s.active)
        #expect(s.accessibilityLabel == "Queue lisse, à toi")

        h.model.clearSlot(straight)
        #expect(h.store.profile.config.styles["tailStyle"] == nil)
        s = DefaultLookSurface(look: straight, config: h.store.profile.config)
        #expect(s.active)
        #expect(s.accessibilityLabel == "Queue lisse, en place")
        #expect(!h.itemSurface(curly).equipped)
        #expect(h.itemSurface(curly).owned, Comment(rawValue: "reverting never un-buys"))
    }

    @Test("returns the body colour to its factory look")
    func bodyColourRoundTrip() throws {
        let h = Harness()
        let rose = try #require(h.option("Corps rose"))
        let lilas = try #require(h.defaultLook("Corps lilas"))

        #expect(DefaultLookSurface(look: lilas, config: h.store.profile.config).active)

        h.model.tapItem(rose)
        h.model.confirmBuy()
        #expect(h.itemSurface(rose).equipped)
        #expect(DefaultLookSurface(look: lilas, config: h.store.profile.config).accessibilityLabel == "Corps lilas, à toi")

        h.model.clearSlot(lilas)
        #expect(h.store.profile.config.colors["bodyColor"] == nil)
        #expect(DefaultLookSurface(look: lilas, config: h.store.profile.config).active)
        #expect(!h.itemSurface(rose).equipped)
    }

    @Test("clearing a slot also closes an open try-on")
    func clearSlotClosesCart() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))
        let lilas = try #require(h.defaultLook("Corps lilas"))

        h.model.tapItem(curly)
        #expect(h.model.cart != nil)
        h.model.clearSlot(lilas)
        #expect(h.model.cart == nil)
    }

    @Test("a default for a not-yet-grown part stays gated like its variants")
    func lockedFactoryLook() throws {
        let h = Harness()
        let horn = try #require(h.defaultLook("Corne dorée"))  // minStage 2

        let s = DefaultLookSurface(look: horn, config: h.store.profile.config)
        #expect(s.locked)
        #expect(s.badge == "🌱 niv. 3")
        #expect(s.accessibilityLabel == "Corne dorée, à débloquer au niveau 3")
    }
}

// MARK: - Zones

@Suite("Shop — armoire / magasin zones")
@MainActor
struct ShopZoneTests {

    @Test("a fresh unicorn's store: body parts first, accessories pooled last")
    func freshStoreGroups() {
        let h = Harness()
        let groups = shopStoreGroups(for: h.store.profile)
        #expect(groups.map(\.label) == ["Corps", "Corne", "Crinière", "Queue", "Accessoires"])
        #expect(groups.map(\.tiles.count) == [3, 3, 2, 2, 5])
    }

    @Test("a fresh wardrobe holds exactly the factory looks")
    func freshArmoire() {
        let h = Harness()
        let groups = shopArmoireGroups(for: h.store.profile)
        #expect(groups.map(\.label) == ["Corps", "Corne", "Crinière", "Queue"])
        #expect(groups.map(\.tiles.count) == [1, 2, 1, 2])
        for group in groups {
            for tile in group.tiles {
                guard case .factory = tile else {
                    Issue.record("a fresh wardrobe listed a bought item: \(tile.id)")
                    return
                }
            }
        }
    }

    @Test("a bought item moves from the store to the wardrobe")
    func boughtItemMoves() throws {
        let h = Harness()
        let curly = try #require(h.option("Queue bouclée"))

        let inStore = { shopStoreGroups(for: h.store.profile).flatMap(\.tiles).contains(.item(curly)) }
        let inArmoire = { shopArmoireGroups(for: h.store.profile).flatMap(\.tiles).contains(.item(curly)) }

        #expect(inStore() && !inArmoire())

        h.model.tapItem(curly)
        h.model.confirmBuy()

        #expect(!inStore() && inArmoire())
        let queue = shopStoreGroups(for: h.store.profile).first { $0.label == "Queue" }
        #expect(queue?.tiles.count == 1, Comment(rawValue: "only « Queue menthe » is left for sale"))
    }

    @Test("slot labels are the authored SLOT_LABEL map")
    func slotLabels() {
        #expect(shopSlotLabel(slot: "bodyColor", category: .color) == "Corps")
        #expect(shopSlotLabel(slot: "tailTipColor", category: .color) == "Bout de queue")
        #expect(shopSlotLabel(slot: "furPattern", category: .style) == "Pelage")
        #expect(shopSlotLabel(slot: "anything", category: .accessory) == "Accessoires")
        #expect(shopSlotLabel(slot: "unknownSlot", category: .color) == "unknownSlot", Comment(rawValue: "SLOT_LABEL[slot] ?? slot"))
    }
}

// MARK: - Growth

@Suite("Shop — growth")
@MainActor
struct ShopGrowthTests {

    @Test("the price curve is 30 · (stage + 1)")
    func priceCurve() {
        #expect(growthPrice(stage: 0) == 30)
        #expect(growthPrice(stage: 1) == 60)
        #expect(growthPrice(stage: 4) == 150)
        #expect(growthPrice(stage: 8) == 270)
    }

    @Test("the affordable card: price, counter, pips and labels")
    func affordableSurface() {
        let s = GrowthCardSurface(stage: 0, balance: 60)
        #expect(s.price == 30)
        #expect(!s.atMax && s.affordable && !s.disabled)
        #expect(s.nextStage == 1)
        #expect(s.counter == "1/10")
        #expect(s.meterLabel == "Croissance 1 sur 10")
        #expect(s.filledPips == 1)
        #expect(s.accessibilityLabel == "Faire grandir pour 30 points")
        #expect(s.buttonLabel == "Grandir \u{00B7} ⭐ 30")
        #expect(!s.showsMeter)
    }

    @Test("too poor: disabled, « pas encore », and the fattest savings meter")
    func poorSurface() {
        let s = GrowthCardSurface(stage: 1, balance: 30)
        #expect(s.price == 60)
        #expect(s.disabled && !s.atMax)
        #expect(s.accessibilityLabel == "Pas encore assez de points pour grandir, il en faut 60")
        #expect(s.buttonLabel == "pas encore \u{00B7} ⭐ 60")
        #expect(s.showsMeter)
    }

    @Test("at the top: « Niveau max ✨ », no meter, and the peek stops climbing")
    func atMaxSurface() {
        let s = GrowthCardSurface(stage: 9, balance: 10_000)
        #expect(s.atMax && s.disabled)
        #expect(s.accessibilityLabel == "Niveau maximum atteint")
        #expect(s.buttonLabel == "Niveau max ✨")
        #expect(!s.showsMeter)
        #expect(s.nextStage == 9, Comment(rawValue: "Math.min(stage + 1, GROWTH_STAGES − 1)"))
    }

    @Test("growing spends the price and bumps the stage — in the web's order")
    func growSpendsAndBumps() async {
        let h = Harness()
        var flights: [Int] = []
        var celebrated = 0
        h.model.onGrewFlight = { flights.append($0) }
        h.model.onGrewCelebrate = { celebrated += 1 }

        h.model.grow()

        #expect(h.store.profile.config.stage == 1)
        #expect(h.store.profile.balance == 30, Comment(rawValue: "60 − 30"))
        #expect(h.store.profile.stars.spent["test-device"] == 30)
        #expect(flights == [30])
        #expect(celebrated == 1)
        #expect(h.audio.successes == 1)

        await h.model.speechTask?.value
        #expect(h.audio.spoken == ["Tu as grandi !"])
    }

    @Test("growing at the top is a spend-free no-op")
    func growAtMax() {
        let h = Harness()
        h.store.setConfig { c in
            var c = c
            c.stage = 9
            return c
        }
        var fired = false
        h.model.onGrewFlight = { _ in fired = true }

        h.model.grow()

        #expect(h.store.profile.config.stage == 9)
        #expect(h.store.profile.balance == 60)
        #expect(!fired)
        #expect(h.audio.successes == 0)
    }

    @Test("growing one point short is refused by spend() and changes nothing")
    func growOneShort() {
        let h = Harness()
        h.store.spend(cost: 31)  // 29 left; the price is 30
        var fired = false
        h.model.onGrewFlight = { _ in fired = true }

        h.model.grow()

        #expect(h.store.profile.config.stage == 0)
        #expect(h.store.profile.balance == 29)
        #expect(!fired)
    }

    @Test("the last step lands exactly on the cap")
    func lastStepClamps() {
        let h = Harness(awardLevels: 27)  // 27 × 10 = 270 = the stage-8 price
        h.store.setConfig { c in
            var c = c
            c.stage = 8
            return c
        }

        #expect(performGrow(on: h.store) == 270)
        #expect(h.store.profile.config.stage == 9)
        #expect(h.store.profile.balance == 0)
        #expect(GrowthCardSurface(stage: 9, balance: 0).atMax)
    }

    @Test("growth never mints — earned counters are identical after a grow")
    func growthNeverMints() {
        let h = Harness()
        let earnedBefore = h.store.profile.stars.earned
        h.model.grow()
        #expect(h.store.profile.stars.earned == earnedBefore)
    }
}

// MARK: - Flight size

@Suite("Shop — flight size")
struct ShopFlightSizeTests {

    @Test("min(8, max(3, round(cost / 10))) with JS rounding")
    func flightSizeCurve() {
        #expect(shopFlightSize(cost: 10) == 3)
        #expect(shopFlightSize(cost: 22) == 3, Comment(rawValue: "2.2 rounds to 2, clamps up to 3"))
        #expect(shopFlightSize(cost: 30) == 3)
        #expect(shopFlightSize(cost: 40) == 4)
        #expect(shopFlightSize(cost: 45) == 5, Comment(rawValue: "Math.round(4.5) = 5, half toward +∞"))
        #expect(shopFlightSize(cost: 60) == 6)
        #expect(shopFlightSize(cost: 70) == 7)
        #expect(shopFlightSize(cost: 75) == 8)
        #expect(shopFlightSize(cost: 200) == 8, Comment(rawValue: "capped at 8"))
    }
}

// MARK: - Wallet count

@Suite("Shop — wallet count")
struct ShopWalletCountTests {

    @Test("duration is min(900, 350 + |Δ| · 12) ms")
    func durations() {
        #expect(abs(WalletCount(from: 60, to: 20).duration - 0.83) < 1e-9)
        #expect(abs(WalletCount(from: 60, to: 59).duration - 0.362) < 1e-9)
        #expect(WalletCount(from: 0, to: 100).duration == 0.9, Comment(rawValue: "capped at 900 ms"))
    }

    @Test("the count eases with 1 − (1 − k)² and lands exactly")
    func easedValues() {
        let count = WalletCount(from: 60, to: 20)
        #expect(count.value(at: 0) == 60)
        #expect(count.value(at: count.duration) == 20)
        // k = 0.5 → eased = 0.75 → 60 − 40 · 0.75 = 30.
        #expect(count.value(at: count.duration / 2) == 30)
        // Past the end it clamps, exactly as the rAF loop stops at k = 1.
        #expect(count.value(at: count.duration * 3) == 20)
    }

    @Test("an unchanged value does not animate")
    func unchangedValue() {
        #expect(!WalletCount(from: 42, to: 42).animates)
        #expect(WalletCount(from: 42, to: 41).animates)
    }
}

// MARK: - Reduced-motion gates (D29: the shop's set is gated, per anim.ts)

@Suite("Shop — reduced-motion gates")
@MainActor
struct ShopAnimGateTests {

    private func layerTree() -> (root: CALayer, from: CALayer, to: CALayer, overlay: CALayer) {
        let root = CALayer()
        root.frame = CGRect(x: 0, y: 0, width: 400, height: 800)
        let from = CALayer()
        from.frame = CGRect(x: 10, y: 10, width: 50, height: 30)
        let to = CALayer()
        to.frame = CGRect(x: 200, y: 600, width: 80, height: 80)
        let overlay = CALayer()
        overlay.frame = root.frame
        root.addSublayer(from)
        root.addSublayer(to)
        root.addSublayer(overlay)
        return (root, from, to, overlay)
    }

    @Test("press is the shop's own 0.94 squish — and it IS gated, unlike the tile's")
    func pressGate() {
        let layer = CALayer()

        ShopAnim.press(layer, reduceMotion: FixedReduceMotion(true))
        #expect(layer.animation(forKey: "ALShopPress") == nil)

        ShopAnim.press(layer, reduceMotion: FixedReduceMotion(false))
        let animation = layer.animation(forKey: "ALShopPress") as? CAKeyframeAnimation
        #expect(animation?.values as? [Double] == [1, 0.94, 1])
        #expect(animation?.duration == 0.13)
    }

    @Test("pop is 1 → 1.12 → 1 over 260 ms, gated")
    func popGate() {
        let layer = CALayer()

        ShopAnim.pop(layer, reduceMotion: FixedReduceMotion(true))
        #expect(layer.animation(forKey: "ALShopPop") == nil)

        ShopAnim.pop(layer, reduceMotion: FixedReduceMotion(false))
        let animation = layer.animation(forKey: "ALShopPop") as? CAKeyframeAnimation
        #expect(animation?.values as? [Double] == [1, 1.12, 1])
        #expect(animation?.duration == 0.26)
    }

    @Test("starFlight spawns one throwaway container with `count` stars — or nothing under reduced motion")
    func starFlightGate() {
        let reduced = layerTree()
        ShopAnim.starFlight(
            from: reduced.from, to: reduced.to, overlay: reduced.overlay,
            count: 5, reduceMotion: FixedReduceMotion(true))
        #expect(reduced.overlay.sublayers == nil)

        let moving = layerTree()
        ShopAnim.starFlight(
            from: moving.from, to: moving.to, overlay: moving.overlay,
            count: 5, reduceMotion: FixedReduceMotion(false))
        #expect(moving.overlay.sublayers?.count == 1)
        let container = moving.overlay.sublayers?.first
        #expect(container?.sublayers?.count == 5)
        #expect(container?.sublayers?.allSatisfy { $0.animation(forKey: "ALShopStarFlight") != nil } == true)
    }

    @Test("a rect that has not laid out aborts the flight — `a.width === 0`")
    func starFlightZeroRect() {
        let tree = layerTree()
        tree.from.frame = .zero
        ShopAnim.starFlight(
            from: tree.from, to: tree.to, overlay: tree.overlay,
            count: 5, reduceMotion: FixedReduceMotion(false))
        #expect(tree.overlay.sublayers == nil)
    }

    @Test("growBurst spawns 3 clouds + 12 sparkles — or nothing under reduced motion")
    func growBurstGate() {
        let reduced = layerTree()
        ShopAnim.growBurst(
            around: reduced.to, overlay: reduced.overlay, reduceMotion: FixedReduceMotion(true))
        #expect(reduced.overlay.sublayers == nil)

        let moving = layerTree()
        ShopAnim.growBurst(
            around: moving.to, overlay: moving.overlay, reduceMotion: FixedReduceMotion(false))
        #expect(moving.overlay.sublayers?.count == 1)
        #expect(moving.overlay.sublayers?.first?.sublayers?.count == 15)
    }

    @Test("a nil layer is a quiet no-op, never a crash")
    func nilLayers() {
        ShopAnim.press(nil, reduceMotion: FixedReduceMotion(false))
        ShopAnim.pop(nil, reduceMotion: FixedReduceMotion(false))
        ShopAnim.starFlight(from: nil, to: nil, overlay: nil, count: 3, reduceMotion: FixedReduceMotion(false))
        ShopAnim.growBurst(around: nil, overlay: nil, reduceMotion: FixedReduceMotion(false))
    }
}

// MARK: - Particle geometry (the anim.ts numbers)

@Suite("Shop — particle geometry")
struct ShopParticleSpecTests {

    @Test("each star bows alternately and staggers 70 ms / 45 ms per index")
    func starFlightSpec() throws {
        let spec = try #require(StarFlightSpec(
            from: CGRect(x: 0, y: 0, width: 100, height: 100),
            to: CGRect(x: 200, y: 200, width: 100, height: 100),
            count: 3))
        #expect(spec.stars.count == 3)

        // Δ = (200, 200); midpoint base = (+100, +100 − 36) off the start.
        let s0 = spec.stars[0]
        #expect(s0.start == CGPoint(x: 50, y: 50))
        #expect(s0.end == CGPoint(x: 250, y: 250))
        #expect(s0.mid == CGPoint(x: 50 + 100 + 14, y: 50 + 100 - 36), Comment(rawValue: "bow +14, lift −36"))
        #expect(abs(s0.duration - 0.620) < 1e-9)
        #expect(s0.delay == 0)

        let s1 = spec.stars[1]
        #expect(s1.mid == CGPoint(x: 50 + 100 - 21, y: 50 + 100 - 40), Comment(rawValue: "bow −(14+7), lift −(36+4)"))
        #expect(abs(s1.duration - 0.665) < 1e-9)
        #expect(abs(s1.delay - 0.070) < 1e-9)

        let s2 = spec.stars[2]
        #expect(s2.mid == CGPoint(x: 50 + 100 + 28, y: 50 + 100 - 44))
        #expect(abs(s2.duration - 0.710) < 1e-9)
        #expect(abs(s2.delay - 0.140) < 1e-9)
    }

    @Test("a zero-width rect yields no spec at all")
    func zeroRects() {
        #expect(StarFlightSpec(from: .zero, to: CGRect(x: 0, y: 0, width: 10, height: 10), count: 3) == nil)
        #expect(StarFlightSpec(from: CGRect(x: 0, y: 0, width: 10, height: 10), to: .zero, count: 3) == nil)
        #expect(GrowBurstSpec(around: .zero) == nil)
    }

    @Test("the burst: three staggered clouds at 42 % width, and a 12-sparkle ring")
    func growBurstSpec() throws {
        let spec = try #require(GrowBurstSpec(around: CGRect(x: 0, y: 0, width: 100, height: 100)))
        #expect(spec.center == CGPoint(x: 50, y: 50))

        #expect(spec.puffs.map(\.size) == [42, 42, 42])
        #expect(spec.puffs.map(\.dx) == [0, -21, 21])
        #expect(spec.puffs.map(\.delay) == [0, 0.06, 0.12])
        #expect(spec.puffs.map(\.scale) == [1.3, 1, 1])

        #expect(spec.sparks.count == 12)
        // Glyphs cycle ✨⭐🌟💫; sizes cycle 16/22/28; durations 720/840/960 ms.
        #expect(spec.sparks.map(\.glyph) == Array(repeating: ["✨", "⭐", "🌟", "💫"], count: 3).flatMap { $0 })
        #expect(spec.sparks.map(\.size) == Array(repeating: [CGFloat(16), 22, 28], count: 4).flatMap { $0 })
        #expect(spec.sparks.map(\.duration) == Array(repeating: [0.72, 0.84, 0.96], count: 4).flatMap { $0 })

        // Spark 0: angle 0, dist 55 → (dx, dy) = (55, 0 − 100 · 0.12).
        #expect(abs(spec.sparks[0].dx - 55) < 1e-9)
        #expect(abs(spec.sparks[0].dy - (-12)) < 1e-9)
        // Spark 6: angle π, dist 55 → (−55, −12).
        #expect(abs(spec.sparks[6].dx - (-55)) < 1e-6)
        #expect(abs(spec.sparks[6].dy - (-12)) < 1e-6)
    }
}

// MARK: - Shop-seen

@Suite("Shop — shop-seen (the meters' animate-from point)")
@MainActor
struct ShopSeenTests {

    @Test("sinceBalance is captured once, from what this child last saw")
    func sinceCapturedAtMount() throws {
        let h = Harness()
        let childId = try #require(h.store.activeId)
        ProfileStorage.saveShopSeen([childId: 25, "other-child": 7], to: h.kv)

        let model = ShopModel(store: h.store, audio: h.audio, kv: h.kv)
        #expect(model.sinceBalance == 25)
    }

    @Test("a first visit animates from zero")
    func firstVisitSincesZero() {
        let h = Harness()
        #expect(h.model.sinceBalance == 0)
    }

    @Test("recordSeen writes the balance under the active child, preserving siblings")
    func recordSeenWrites() throws {
        let h = Harness()
        let childId = try #require(h.store.activeId)
        ProfileStorage.saveShopSeen(["other-child": 7], to: h.kv)

        h.model.recordSeen()

        let seen = ProfileStorage.loadShopSeen(h.kv)
        #expect(seen[childId] == 60)
        #expect(seen["other-child"] == 7)
    }

    @Test("no active child, no write")
    func recordSeenNeedsAChild() {
        let h = Harness()
        h.store.switchChild()
        h.model.recordSeen()
        #expect(ProfileStorage.loadShopSeen(h.kv) == [:])
    }

    @Test("the meter sweep the shop feeds: since → balance, sparkling only on growth")
    func meterSweepFromSince() {
        // 25 seen last visit, 60 now, saving for 75: the bar sweeps up and
        // sparkles. (The meter itself is Meter.swift's; this pins the shop's
        // inputs to it through the shared helper.)
        let sweep = savingsMeterSweep(balance: 60, since: 25, cost: 75)
        #expect(sweep.animates)
        #expect(sweep.sparkles)
        #expect(sweep.to == 0.8)
    }
}

// MARK: - The preview thumbnails

@Suite("Shop — item previews")
struct ShopItemPreviewTests {

    @Test("every part that is not for sale goes ghost")
    func ghostColors() {
        let ghost = ItemPreviewModel.ghostColors(.unicorn)
        #expect(ghost == [
            "bodyColor": "#DADCE4",
            "hornColor": "#DADCE4",
            "maneColor": "#DADCE4",
            "tailColor": "#DADCE4",
        ])
        #expect(ItemPreviewModel.ghostColors(.dragon).count == 4)
        #expect(ItemPreviewModel.ghostColors(.cat).count == 3)
    }

    @Test("a colour preview paints only its slot over the ghost")
    func colourPreviewConfig() {
        let config = ItemPreviewModel.previewConfig(
            species: .unicorn, category: .color, slot: "maneColor", value: "#FF8A65")
        #expect(config.stage == 4, Comment(rawValue: "SHOWCASE_STAGE"))
        #expect(config.colors["maneColor"] == "#FF8A65")
        #expect(config.colors["bodyColor"] == "#DADCE4")
        #expect(config.styles.isEmpty)
        #expect(config.accessories.isEmpty)
    }

    @Test("a style preview tints its one part with the factory colour")
    func stylePreviewConfig() {
        let config = ItemPreviewModel.previewConfig(
            species: .unicorn, category: .style, slot: "tailStyle", value: "curly")
        #expect(config.styles == ["tailStyle": "curly"])
        // STYLE_COLOR_SLOT.unicorn.tailStyle → tailColor, tinted its default #F49AC2.
        #expect(config.colors["tailColor"] == "#F49AC2")
        #expect(config.colors["bodyColor"] == "#DADCE4")
    }

    @Test("an accessory preview wears exactly that accessory")
    func accessoryPreviewConfig() {
        let config = ItemPreviewModel.previewConfig(
            species: .cat, category: .accessory, slot: "accessory", value: Accessory.Cat.bow)
        #expect(config.accessories == [Accessory.Cat.bow])
        #expect(config.colors.values.allSatisfy { $0 == "#DADCE4" })
    }

    @Test("whole-body changes stay uncropped; part changes zoom")
    func focusChoices() {
        // Whole coat → full body.
        #expect(ItemPreviewModel.focus(species: .unicorn, category: .color, slot: "bodyColor", value: "#FFD6E8") == nil)
        // Whole-image shimmer → full body.
        #expect(ItemPreviewModel.focus(
            species: .unicorn, category: .accessory, slot: "accessory",
            value: Accessory.Unicorn.starClip) == nil)
        // The unicorn horn crops to a square around the horn: layoutFor(4)
        // puts the head at (50, headCY) with headR = 20 → box r = 25.
        let horn = ItemPreviewModel.focus(species: .unicorn, category: .color, slot: "hornColor", value: "#FF8FB1")
        let L = Growth.layoutFor(4)
        #expect(horn == CGRect(
            x: 50 - L.headR * 1.25,
            y: (L.headCY - L.headR * 0.5) - L.headR * 1.25,
            width: L.headR * 2.5,
            height: L.headR * 2.5))
        // The dragon's treasure sits on the ground at x = 18.
        let treasure = ItemPreviewModel.focus(
            species: .dragon, category: .accessory, slot: "accessory",
            value: Accessory.Dragon.treasure)
        #expect(treasure == CGRect(x: 7, y: L.feetY - 3.5 - 11, width: 22, height: 22))
    }

    @Test("the swim-set crops guard the dragon gap — the TS's `\"swimsuit\" in AA`")
    func swimGuards() {
        #expect(ItemPreviewModel.swimsuitId(.dragon) == nil)
        #expect(ItemPreviewModel.swimRingId(.dragon) == nil)
        #expect(ItemPreviewModel.swimsuitId(.unicorn) == Accessory.Unicorn.swimsuit)
        // And a rabbit swim ring still zooms to the torso.
        #expect(ItemPreviewModel.focus(
            species: .rabbit, category: .accessory, slot: "accessory",
            value: Accessory.Rabbit.swimRing) != nil)
    }
}

// MARK: - Paint order, rasterised (see GameFrameZOrderTests / D34)

#if canImport(AppKit) || canImport(UIKit)

@MainActor
private func renderPixels(
    _ size: CGSize,
    @ViewBuilder _ content: () -> some View
) -> (width: Int, height: Int, buffer: [UInt8])? {
    let renderer = ImageRenderer(content: content().frame(width: size.width, height: size.height))
    renderer.scale = 1
    guard let cg = renderer.cgImage else { return nil }
    var buffer = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
    guard let ctx = CGContext(
        data: &buffer, width: cg.width, height: cg.height,
        bitsPerComponent: 8, bytesPerRow: cg.width * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
    return (cg.width, cg.height, buffer)
}

private func pixel(
    _ render: (width: Int, height: Int, buffer: [UInt8]),
    _ x: Int,
    _ y: Int
) -> (r: Int, g: Int, b: Int)? {
    guard x >= 0, y >= 0, x < render.width, y < render.height else { return nil }
    let i = (y * render.width + x) * 4
    return (Int(render.buffer[i]), Int(render.buffer[i + 1]), Int(render.buffer[i + 2]))
}

@Suite("Shop — paint order", .serialized)
@MainActor
struct ShopZOrderTests {

    private static let frame = CGSize(width: 400, height: 700)

    private func makeShop(withDialog: Bool) -> ShopView? {
        let kv = InMemoryKVStore()
        let store = ProfileStore(kv: kv, device: { "test-device" }, now: { 1_000 })
        store.createChild(name: "Test")
        for level in 0..<6 {
            store.award(exercise: .orderSyllables, level: level, perfectRounds: 0, totalRounds: 1)
        }
        let model = ShopModel(store: store, audio: SilentAudioEngine(), kv: kv)
        if withDialog {
            guard let curly = MascotCatalog.catalog.first(where: {
                $0.species == .unicorn && $0.name == "Queue bouclée"
            }) else { return nil }
            model.tryOn(curly)
        }
        return ShopView(model: model, onBack: {})
    }

    @Test("the try-on dialog's scrim covers the shop, header included — z-50 over the sticky z-10")
    func dialogCoversTheHeader() throws {
        let plainShop = try #require(makeShop(withDialog: false))
        let dialogShop = try #require(makeShop(withDialog: true))
        let plain = try #require(renderPixels(Self.frame) {
            plainShop.alViewport(width: Self.frame.width)
        })
        let dialog = try #require(renderPixels(Self.frame) {
            dialogShop.alViewport(width: Self.frame.width)
        })

        // Top-left, inside the header band. The scrim (rgba(74,48,24,0.45))
        // must darken it markedly.
        let before = try #require(pixel(plain, 30, 30))
        let after = try #require(pixel(dialog, 30, 30))
        #expect(
            after.r < before.r - 20,
            Comment(rawValue: "the dialog does not cover the header: \(before) → \(after)"))
    }

    @Test("the try-on card paints over its scrim — the purchase decision is on top")
    func cardPaintsOverTheScrim() throws {
        let dialogShop = try #require(makeShop(withDialog: true))
        let dialog = try #require(renderPixels(Self.frame) {
            dialogShop.alViewport(width: Self.frame.width)
        })

        // A pixel in the scrim's margin (x = 8 is outside the card's 20 pt
        // gutter) vs the brightest pixel along the card's mid row: the card
        // (stage cream / gold button) must beat the darkened scrim.
        let scrim = try #require(pixel(dialog, 8, 350))
        var brightest = 0
        for x in stride(from: 40, through: 360, by: 20) {
            if let p = pixel(dialog, x, 350) {
                brightest = max(brightest, p.r)
            }
        }
        #expect(
            brightest > scrim.r + 20,
            Comment(rawValue: "no card pixel outshines the scrim: card max r=\(brightest), scrim r=\(scrim.r)"))
    }
}

#endif
