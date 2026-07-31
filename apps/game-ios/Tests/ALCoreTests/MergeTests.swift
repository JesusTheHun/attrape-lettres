import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Port of `src/sync/merge.test.ts`, case for case.                             */
/*                                                                             */
/* The scenario every test here is really about: Léa plays on Dad's phone in    */
/* the car and on Mum's phone at home. Neither device has signal. Whatever we   */
/* do when they finally meet, she must not lose a single star.                  */
/* -------------------------------------------------------------------------- */

private let DAD = "dad-phone"
private let MUM = "mum-phone"
private let PAD = "family-ipad"

private func progress(
    _ s: Species,
    owned: [String] = [],
    rev: Rev = .zero,
    config: MascotConfig? = nil
) -> SpeciesProgress {
    SpeciesProgress(config: config ?? blankConfig(s), owned: owned, rev: rev)
}

private func mapWith(_ s: Species, _ p: SpeciesProgress) -> SpeciesMap {
    var m = blankSpeciesMap()
    m[s] = p
    return m
}

private func profile(
    chosen: Bool = false,
    current: Species = .unicorn,
    currentRev: Rev = .zero,
    species: SpeciesMap = blankSpeciesMap(),
    stars: StarCounters = StarCounters(earned: [:], spent: [:]),
    clears: ClearCounters = [:]
) -> PersistedProfile {
    PersistedProfile(
        chosen: chosen,
        current: current,
        currentRev: currentRev,
        species: species,
        stars: stars,
        clears: clears
    )
}

private func kid(
    _ id: String,
    touchedAt: Millis = 0,
    profile p: PersistedProfile = profile()
) -> ChildProfile {
    ChildProfile(id: id, name: id, nameRev: .zero, touchedAt: touchedAt, profile: p)
}

private func roster(
    children: [ChildProfile] = [],
    activeId: String? = nil,
    removed: [String: Millis] = [:]
) -> Roster {
    Roster(children: children, activeId: activeId, removed: removed)
}

/// Léa earned `n` stars on `device` and spent `spent`.
private func earned(_ device: String, _ n: Int, spent: Int = 0) -> PersistedProfile {
    profile(stars: StarCounters(earned: [device: n], spent: spent > 0 ? [device: spent] : [:]))
}

@Suite struct MergeCounterTests {
    @Test func takesTheFresherCountPerDeviceNeverTheSumOfTheSameDevice() {
        // Dad's phone at 10 stars syncs, earns 3 more, syncs again. Not 23.
        #expect(mergeCounter([DAD: 10], [DAD: 13]) == [DAD: 13])
    }

    @Test func keepsBothDevicesEarnings() {
        // The whole point.
        let dad = earned(DAD, 10)
        let mum = earned(MUM, 3)
        #expect(balanceOf(mergeProfile(dad, mum).stars) == 13)
    }

    @Test func isCommutative() {
        // Sync order cannot change the answer.
        let a = earned(DAD, 10)
        let b = earned(MUM, 3)
        #expect(mergeProfile(a, b) == mergeProfile(b, a))
    }

    @Test func isIdempotent() {
        // Syncing twice is not earning twice.
        let a = earned(DAD, 10)
        let b = earned(MUM, 3)
        let once = mergeProfile(a, b)
        #expect(mergeProfile(once, b) == once)
        #expect(balanceOf(mergeProfile(mergeProfile(once, b), b).stars) == 13)
    }

    @Test func isOrderIndependentAcrossThreeDevices() {
        let a = earned(DAD, 10)
        let b = earned(MUM, 3)
        let c = earned(PAD, 7)
        let left = mergeProfile(mergeProfile(a, b), c)
        let right = mergeProfile(a, mergeProfile(b, c))
        #expect(balanceOf(left.stars) == 20)
        #expect(left == right)
    }

    @Test func bumpOnlyEverTouchesThisDevicesOwnSlot() {
        let after = bump([DAD: 10, MUM: 3], device: DAD, by: 5)
        #expect(after == [DAD: 15, MUM: 3])
    }
}

@Suite struct MergeStarsTests {
    @Test func subtractsSpendingFromEarningsWhereverEachHappened() {
        let dad = earned(DAD, 10, spent: 4)
        let mum = earned(MUM, 6)
        #expect(balanceOf(mergeProfile(dad, mum).stars) == 12)
    }

    @Test func floorsAtZeroWhenTwoOfflineDevicesBothSpendTheSameStars() {
        // Both see 10, both buy an 8-star item. 10 earned, 16 spent.
        let dad = StarCounters(earned: [PAD: 10], spent: [DAD: 8])
        let mum = StarCounters(earned: [PAD: 10], spent: [MUM: 8])
        let merged = mergeStars(dad, mum)

        #expect(merged.spent == [DAD: 8, MUM: 8])
        // The arithmetic says -6. The child sees 0 and keeps both items: we
        // never claw a purchase back from a six-year-old to balance a ledger.
        #expect(balanceOf(merged) == 0)
    }

    @Test func keepsBothItemsBoughtDuringThatOverdraw() {
        let dad = profile(species: mapWith(.unicorn, progress(.unicorn, owned: ["horn"])))
        let mum = profile(species: mapWith(.unicorn, progress(.unicorn, owned: ["tail"])))
        #expect(mergeProfile(dad, mum).species.unicorn.owned == ["horn", "tail"])
    }
}

@Suite struct MergeClearsTests {
    @Test func sumsClearsAcrossDevicesSoTheRewardCurveKeepsDecaying() {
        let dad = profile(clears: ["read-image:1": [DAD: 1]])
        let mum = profile(clears: ["read-image:1": [MUM: 1]])
        let ledger = ledgerOf(mergeProfile(dad, mum).clears)

        // Two clears really happened, so the next one pays the third rung —
        // playing the same level on the other phone must not re-open the
        // 10-star jackpot.
        #expect(ledger["read-image:1"] == 2)
        #expect(Rewards.rewardFor(priorClears: ledger["read-image:1"]!) == Rewards.rewardFor(priorClears: 2))
        #expect(Rewards.rewardFor(priorClears: ledger["read-image:1"]!) < Rewards.rewardFor(priorClears: 0))
    }

    @Test func mergesIndependentLevelsWithoutTouchingEachOther() {
        let dad = profile(clears: ["read-image:1": [DAD: 3]])
        let mum = profile(clears: ["syllable-grid:2": [MUM: 1]])
        #expect(ledgerOf(mergeProfile(dad, mum).clears) == [
            "read-image:1": 3,
            "syllable-grid:2": 1,
        ])
    }
}

@Suite struct MergeCosmeticsTests {
    @Test func keepsTheLaterMascotLook() {
        let older = progress(
            .unicorn,
            rev: Rev(at: 100, by: DAD),
            config: MascotConfig(species: .unicorn, stage: 2, colors: ["hornColor": "#F0A"], styles: [:], accessories: [])
        )
        let newer = progress(
            .unicorn,
            rev: Rev(at: 200, by: MUM),
            config: MascotConfig(species: .unicorn, stage: 5, colors: ["hornColor": "#0FA"], styles: [:], accessories: [])
        )
        let a = profile(species: mapWith(.unicorn, older))
        let b = profile(species: mapWith(.unicorn, newer))

        #expect(mergeProfile(a, b).species.unicorn.config.stage == 5)
        #expect(mergeProfile(b, a).species.unicorn.config.stage == 5)
    }

    @Test func unionsOwnedItemsEvenWhenTheOtherSidesLookWins() {
        let loser = progress(.unicorn, owned: ["horn"], rev: Rev(at: 100, by: DAD))
        let winner = progress(.unicorn, owned: ["tail"], rev: Rev(at: 200, by: MUM))
        let merged = mergeProfile(
            profile(species: mapWith(.unicorn, loser)),
            profile(species: mapWith(.unicorn, winner))
        )
        // Look came from Mum's phone; nothing bought on Dad's was dropped.
        #expect(merged.species.unicorn.owned == ["horn", "tail"])
    }

    @Test func breaksAnExactTimestampTieTheSameWayFromBothSides() {
        let a = profile(current: .fox, currentRev: Rev(at: 500, by: DAD))
        let b = profile(current: .cat, currentRev: Rev(at: 500, by: MUM))
        #expect(mergeProfile(a, b).current == mergeProfile(b, a).current)
    }

    @Test func neverUnpicksASpeciesOnceChosen() {
        let picked = profile(chosen: true)
        let fresh = profile(chosen: false)
        #expect(mergeProfile(picked, fresh).chosen == true)
        #expect(mergeProfile(fresh, picked).chosen == true)
    }

    @Test func mergeOwnedIsASetUnionThatKeepsTheLocalOrderFirst() {
        #expect(mergeOwned(["a", "b"], ["b", "c"]) == ["a", "b", "c"])
    }
}

@Suite struct MergeRosterTests {
    @Test func bringsInASiblingCreatedOnTheOtherDevice() {
        let here = roster(children: [kid("lea")])
        let there = roster(children: [kid("tom")])
        #expect(mergeRoster(here, there).children.map(\.id).sorted() == ["lea", "tom"])
    }

    @Test func mergesTheSameChildsProgressFromBothDevices() {
        let here = roster(children: [kid("lea", profile: earned(DAD, 10))])
        let there = roster(children: [kid("lea", profile: earned(MUM, 3))])
        let merged = mergeRoster(here, there)
        #expect(merged.children.count == 1)
        #expect(balanceOf(merged.children[0].profile.stars) == 13)
    }

    @Test func neverAdoptsTheOtherDevicesActivePlayer() {
        let here = roster(children: [kid("lea")], activeId: "lea")
        let there = roster(children: [kid("lea"), kid("tom")], activeId: "tom")
        // Tom being at the wheel on Mum's phone says nothing about this tablet.
        #expect(mergeRoster(here, there).activeId == "lea")
    }

    @Test func clearsActiveIdIfThatChildWasDeletedElsewhere() {
        let here = roster(children: [kid("lea", touchedAt: 10)], activeId: "lea")
        let there = roster(removed: ["lea": 50])
        let merged = mergeRoster(here, there)
        #expect(merged.children.isEmpty)
        #expect(merged.activeId == nil)
    }

    @Test func propagatesADeleteToTheOtherDevice() {
        let here = roster(children: [kid("lea", touchedAt: 10), kid("tom", touchedAt: 10)])
        let there = roster(children: [kid("tom", touchedAt: 10)], removed: ["lea": 99])
        #expect(mergeRoster(here, there).children.map(\.id) == ["tom"])
    }

    @Test func refusesADeleteThatWouldErasePlayDoneAfterwards() {
        // Parent tidies the roster on Mum's phone at t=50. Léa keeps playing on
        // the iPad until t=200. A vanished child is unrecoverable; a resurrected
        // one is two taps. The tie goes to keeping the data.
        let here = roster(children: [kid("lea", touchedAt: 200, profile: earned(PAD, 40))])
        let there = roster(removed: ["lea": 50])
        let merged = mergeRoster(here, there)
        #expect(merged.children.count == 1)
        #expect(balanceOf(merged.children[0].profile.stars) == 40)
    }

    @Test func keepsTombstonesSoAThirdDeviceAlsoHonoursTheDelete() {
        let here = roster(children: [kid("lea", touchedAt: 10)])
        let there = roster(removed: ["lea": 99])
        let merged = mergeRoster(here, there)
        // The iPad still has Léa and no tombstone; merging must not resurrect her.
        let ipad = roster(children: [kid("lea", touchedAt: 10)])
        #expect(mergeRoster(merged, ipad).children.isEmpty)
    }

    @Test func isIdempotentAndAgreesOnMembershipInEitherDirection() {
        let here = roster(children: [kid("lea", profile: earned(DAD, 10))])
        let there = roster(children: [kid("lea", profile: earned(MUM, 3)), kid("tom")])
        let once = mergeRoster(here, there)
        #expect(mergeRoster(once, there) == once)
        #expect(
            mergeRoster(there, here).children.map(\.id).sorted()
                == once.children.map(\.id).sorted()
        )
    }
}
