import Foundation
import Testing

@testable import ALCore

/// D8: one owner for the mascot vocabulary. `Species` raw values are persisted
/// AND synced (they key `PersistedProfile.species`), so they are frozen for the
/// same reason `ExerciseId`'s are.
@Suite struct MascotTypeTests {
    @Test func speciesRawValuesAreFrozen() {
        #expect(Species.allCases.map(\.rawValue) == ["unicorn", "cat", "fox", "rabbit", "dragon"])
    }

    @Test func tenGrowthStages() {
        #expect(growthStages == 10)
    }

    @Test func customizationCategories() {
        #expect(CustomizationCategory.allCases.map(\.rawValue) == ["accessory", "color", "style"])
    }

    /// `catalog.ts` only spreads `minStage` in when it was passed, and
    /// `catalog.test.ts` distinguishes "absent" from 0 — so it stays Optional.
    @Test func minStageAbsentIsNotZero() {
        let ungated = CustomizationOption(
            id: "unicorn.color.bodyColor.lilas", species: .unicorn, category: .color,
            slot: "bodyColor", value: "#F5ECFF", name: "Corps lilas", cost: 15)
        #expect(ungated.minStage == nil)
        #expect(ungated.minStage ?? 0 == 0)

        let gated = CustomizationOption(
            id: "unicorn.color.hornColor.doree", species: .unicorn, category: .color,
            slot: "hornColor", value: "#FFD54F", name: "Corne dorée", emoji: "🔺", cost: 20,
            minStage: 2)
        #expect(gated.minStage == 2)
    }

    /// An unknown slot key written by a newer build must survive a round trip
    /// through an older one — which is why colours/styles are `[String: String]`
    /// and not enums.
    @Test func configRoundTripsUnknownSlots() throws {
        let config = MascotConfig(
            species: .dragon,
            stage: 7,
            colors: ["bodyColor": "#7DB874", "someFutureSlot": "#123456"],
            styles: ["hornStyle": "curved"],
            accessories: ["dragon.accessory.treasure"]
        )
        let data = try JSONEncoder().encode(config)
        let back = try JSONDecoder().decode(MascotConfig.self, from: data)
        #expect(back == config)
        #expect(back.colors["someFutureSlot"] == "#123456")
    }

    @Test func speciesRoundTripsThroughJSON() throws {
        for species in Species.allCases {
            let data = try JSONEncoder().encode(species)
            #expect(try JSONDecoder().decode(Species.self, from: data) == species)
        }
    }
}
