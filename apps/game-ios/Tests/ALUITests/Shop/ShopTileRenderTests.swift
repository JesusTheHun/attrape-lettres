import SwiftUI
import Testing

@testable import ALArt
@testable import ALCore
@testable import ALUI

// Every shop tile, drawn, at every growth stage.
//
// The shop crashed when scrolled on a device, and nothing in the host suite
// could see it: a `LazyVGrid` only builds the tiles that scroll into view, so
// the failing one is by definition the one no static check ever evaluated. This
// walks the WHOLE catalog for every species and rasterises each tile, which is
// the same body the grid builds lazily.
//
// `ImageRenderer` runs on macOS (D23) and `LayerHost` is a pass-through there,
// so this covers the DRAWING, not the UIKit hosting.

#if canImport(AppKit) || canImport(UIKit)

@MainActor
private func profileFixture(species: Species, stage: Int, owned: [String]) -> ProfileView {
    var map = blankSpeciesMap()
    for s in Species.allCases {
        var progress = map[s]
        progress.config.stage = s == species ? stage : 0
        progress.owned = s == species ? owned : []
        map[s] = progress
    }
    let persisted = PersistedProfile(
        chosen: true,
        current: species,
        currentRev: .zero,
        species: map,
        stars: StarCounters(earned: [:], spent: [:]),
        clears: [:])
    return ProfileView(of: persisted, balance: 500, ledger: [:])
}

@Suite("Shop — every tile in the catalog rasterises", .serialized)
@MainActor
struct ShopTileRenderTests {

    @MainActor
    private func drew(_ view: some View, _ label: String) -> Bool {
        // Printed BEFORE the draw: a trap takes the process down with it, so
        // the last line of output names the tile that did it.
        FileHandle.standardError.write(Data("render: \(label)\n".utf8))
        let renderer = ImageRenderer(content: view.frame(width: 160, height: 200))
        renderer.scale = 1
        return renderer.cgImage != nil
    }

    /// Every catalog item, at the stage that LOCKS it and the stage that frees
    /// it — the two tiles differ (badge, price panel, preview).
    @Test("every catalog item tile draws, locked and unlocked")
    func everyItemTileDraws() throws {
        var drawn = 0
        for option in MascotCatalog.catalog {
            let minStage = option.minStage ?? 0
            for stage in Set([0, minStage, growthStages - 1]).sorted() {
                let profile = profileFixture(species: option.species, stage: stage, owned: [])
                let surface = ShopItemSurface(option: option, profile: profile, cartId: nil)
                #expect(
                    drew(
                        ShopItemView(
                            option: option, surface: surface,
                            balance: profile.balance, sinceBalance: 0, onTap: {}),
                        "item \(option.species) \(option.id) stage=\(stage)"))
                drawn += 1
            }
        }
        #expect(drawn > 0, Comment(rawValue: "an empty catalog would make this vacuous"))
    }

    /// The same walk for owned items — a bought tile takes a different branch
    /// (sticker, no price panel) and lives in the wardrobe zone.
    @Test("every catalog item draws once owned and worn")
    func everyOwnedItemTileDraws() throws {
        var drawn = 0
        for option in MascotCatalog.catalog {
            let profile = profileFixture(
                species: option.species, stage: growthStages - 1, owned: [option.id])
            let surface = ShopItemSurface(option: option, profile: profile, cartId: option.id)
            #expect(
                drew(
                    ShopItemView(
                        option: option, surface: surface,
                        balance: profile.balance, sinceBalance: 0, onTap: {}),
                    "owned \(option.species) \(option.id)"))
            drawn += 1
        }
        #expect(drawn > 0)
    }

    @Test("every factory look tile draws, for its own species and every stage")
    func everyFactoryTileDraws() throws {
        var drawn = 0
        for species in Species.allCases {
            for look in MascotCatalog.defaultLooks[species] ?? [] {
                for stage in [0, growthStages - 1] {
                    var config = MascotConfig(
                        species: species, stage: stage, colors: [:], styles: [:], accessories: [])
                    config.stage = stage
                    let surface = DefaultLookSurface(look: look, config: config)
                    #expect(
                        drew(
                            DefaultTileView(
                                look: look, species: species, surface: surface, onTap: {}),
                            "factory \(species) \(look.category.rawValue).\(look.slot) stage=\(stage)"
                        ))
                    drawn += 1
                }
            }
        }
        #expect(drawn > 0)
    }
}

#endif
