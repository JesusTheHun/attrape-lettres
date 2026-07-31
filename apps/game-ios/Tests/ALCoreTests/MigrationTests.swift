import Foundation
import Testing

@testable import ALCore

// The upgrade path — port of the migration half of `useProfile.test.tsx`.
// A child who has been playing for months is sitting on a v3 blob; the v4
// counters must reproduce her stars EXACTLY, not approximately. Per
// storage.ts, the old keys and readers stay intact so a rollback still finds
// data it understands.

private let device = "this-phone"
private let now: Millis = 1_700_000_000_000

/// The exact V3 fixture from `useProfile.test.tsx`.
private let v3RosterJSON = """
{"children":[{"id":"lea-v3","name":"Léa","profile":{"chosen":true,"current":"fox","species":{"fox":{"config":{"species":"fox","stage":3,"colors":{"furColor":"#F80"},"styles":{},"accessories":[]},"owned":["fox.fur.orange"]}},"balance":17,"ledger":{"read-image:1":2}}}],"activeId":"lea-v3"}
"""

/// The exact V1 fixture from `useProfile.test.tsx`.
private let v1ProfileJSON = """
{"chosen":true,"config":{"species":"cat","stage":1,"colors":{},"styles":{},"accessories":[]},"balance":5,"ledger":{"first-letter:1":1},"owned":["cat.whiskers.long"]}
"""

@Suite struct MigrationV3Tests {
    private func migrated() -> (kv: InMemoryKVStore, roster: Roster) {
        let kv = InMemoryKVStore([ProfileStorage.v3Key: v3RosterJSON])
        return (kv, initialRoster(kv: kv, device: device, now: now))
    }

    @Test func carriesStarsClearsLookAndItemsAcrossUntouched() {
        let (_, roster) = migrated()
        #expect(roster.children.count == 1)
        let lea = roster.children[0]
        #expect(lea.name == "Léa")
        #expect(lea.id == "lea-v3")
        #expect(roster.activeId == "lea-v3")

        // The flat totals became "everything earned on THIS device" — the
        // exact seeding that lets two migrated phones SUM when they meet.
        #expect(lea.profile.stars.earned == [device: 17])
        #expect(lea.profile.stars.spent == [:])
        #expect(lea.profile.clears["read-image:1"] == [device: 2])

        #expect(lea.profile.chosen)
        #expect(lea.profile.current == .fox)
        let fox = lea.profile.species[.fox]
        #expect(fox.config.stage == 3)
        #expect(fox.config.colors["furColor"] == "#F80")
        #expect(fox.owned == ["fox.fur.orange"])
        // No stamps existed pre-v4: everything starts at the always-losing rev.
        #expect(fox.rev == .zero)
        #expect(lea.nameRev == .zero)
        #expect(lea.profile.currentRev == .zero)
    }

    // "Fresh migration: nothing can have tombstoned these yet, and marking
    // them touched keeps a future stale tombstone from erasing them."
    @Test func stampsTouchedAtNowAndNoTombstones() {
        let (_, roster) = migrated()
        #expect(roster.children[0].touchedAt == now)
        #expect(roster.removed.isEmpty)
    }

    // The untouched species slots come out blank, not absent.
    @Test func otherSpeciesSlotsAreBlank() {
        let (_, roster) = migrated()
        let p = roster.children[0].profile
        for s in Species.allCases where s != .fox {
            #expect(p.species[s] == blankProgress(s))
        }
    }

    // "leaves the v3 blob in place so a rollback still reads data" — and
    // initialRoster computes without saving (the first commit persists).
    @Test func leavesTheV3BlobInPlaceAndWritesNothing() {
        let (kv, _) = migrated()
        #expect(kv.string(ProfileStorage.v3Key) == v3RosterJSON)
        #expect(kv.string(ProfileStorage.rosterKey) == nil)
    }

    @Test func v3ActiveIdPointingNowhereIsDropped() {
        let json = v3RosterJSON.replacingOccurrences(of: "\"activeId\":\"lea-v3\"", with: "\"activeId\":\"ghost\"")
        let kv = InMemoryKVStore([ProfileStorage.v3Key: json])
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.count == 1)
        #expect(roster.activeId == nil)
    }

    // TS `c.id || newId()` — an empty string id is replaced too.
    @Test func emptyStringIdGetsAFreshOne() {
        let json = v3RosterJSON.replacingOccurrences(of: "\"id\":\"lea-v3\"", with: "\"id\":\"\"")
        let kv = InMemoryKVStore([ProfileStorage.v3Key: json])
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.count == 1)
        #expect(!roster.children[0].id.isEmpty)
        // The stale activeId no longer matches anything.
        #expect(roster.activeId == nil)
    }

    // TS gate is `v3?.children?.length` — an empty roster falls through.
    @Test func emptyChildrenV3FallsThroughToV1() {
        let kv = InMemoryKVStore([
            ProfileStorage.v3Key: #"{"children":[],"activeId":null}"#,
            ProfileStorage.v1Key: v1ProfileJSON,
        ])
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.count == 1)
        #expect(roster.children[0].name == "Joueur 1")
        #expect(roster.children[0].profile.current == .cat)
    }

    // Zero or negative legacy values produce NO key, not a zero key.
    @Test func zeroAndNegativeLegacyValuesSeedNoKeys() {
        let flat = LegacyFlatProfile(balance: 0, ledger: ["a:1": 0, "b:2": -3])
        let p = migrateFlatProfile(flat, device: device)
        #expect(p.stars.earned.isEmpty)
        #expect(p.clears.isEmpty)
    }

    @Test func flatChosenDefaultsFalse() {
        let p = migrateFlatProfile(LegacyFlatProfile(), device: device)
        #expect(p.chosen == false)
        #expect(p.current == .unicorn)
    }
}

@Suite struct MigrationV1Tests {
    @Test func promotesTheSingleLegacyMascotIntoItsSpeciesSlot() {
        let kv = InMemoryKVStore([ProfileStorage.v1Key: v1ProfileJSON])
        let roster = initialRoster(kv: kv, device: device, now: now)

        #expect(roster.children.count == 1)
        let c = roster.children[0]
        #expect(c.name == "Joueur 1")
        #expect(roster.activeId == c.id)
        #expect(c.nameRev == Rev(at: now, by: device))
        #expect(c.touchedAt == now)

        #expect(c.profile.chosen)
        #expect(c.profile.current == .cat)
        #expect(c.profile.species[.cat].config.stage == 1)
        #expect(c.profile.species[.cat].owned == ["cat.whiskers.long"])
        #expect(c.profile.stars.earned == [device: 5])
        #expect(c.profile.clears["first-letter:1"] == [device: 1])
    }

    // v1 users had necessarily chosen — TS `l.chosen ?? true`.
    @Test func v1ChosenDefaultsTrue() {
        let p = migrateV1Profile(LegacyV1Profile(config: LooseMascotConfig(species: "cat")), device: device)
        #expect(p.chosen)
        #expect(p.current == .cat)
    }

    @Test func v1MissingConfigDefaultsToUnicorn() {
        let p = migrateV1Profile(LegacyV1Profile(balance: 3), device: device)
        #expect(p.current == .unicorn)
        #expect(p.stars.earned == [device: 3])
    }
}

@Suite struct MigrationV2Tests {
    @Test func wrapsTheSingleChildAroundTheFlatProfile() {
        let v2JSON = """
        {"chosen":true,"current":"fox","species":{"fox":{"config":{"species":"fox","stage":2,"colors":{},"styles":{},"accessories":[]},"owned":[]}},"balance":9,"ledger":{"read-image:1":1}}
        """
        let kv = InMemoryKVStore([ProfileStorage.v2Key: v2JSON])
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.count == 1)
        let c = roster.children[0]
        #expect(c.name == "Joueur 1")
        #expect(roster.activeId == c.id)
        #expect(c.profile.current == .fox)
        #expect(c.profile.species[.fox].config.stage == 2)
        #expect(c.profile.stars.earned == [device: 9])
        #expect(c.profile.clears["read-image:1"] == [device: 1])
        // v2 blob left in place (forward, additive migration).
        #expect(kv.string(ProfileStorage.v2Key) == v2JSON)
    }
}

@Suite struct MigrationV4Tests {
    @Test func v4WinsOverEveryOlderBlob() {
        let v4 = Roster(
            children: [child(name: "Léa", profile: defaultProfile, device: device, now: now)],
            activeId: nil,
            removed: [:]
        )
        let kv = InMemoryKVStore([ProfileStorage.v3Key: v3RosterJSON, ProfileStorage.v1Key: v1ProfileJSON])
        ProfileStorage.saveRoster(v4, to: kv)
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.count == 1)
        #expect(roster.children[0].name == "Léa")
        // The v3 name would have been "Léa" too — pin the v4 provenance by id.
        #expect(roster.children[0].id == v4.children[0].id)
    }

    @Test func normalizeKeepsTombstonesAndDropsAStaleActiveId() {
        let json = #"{"children":[],"activeId":"gone","removed":{"gone":123}}"#
        let kv = InMemoryKVStore([ProfileStorage.rosterKey: json])
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.isEmpty)
        #expect(roster.activeId == nil)
        #expect(roster.removed == ["gone": 123])
    }

    // Spec risk #1 — the two documented deviations from JS truthiness, pinned:
    // unknown species keys are dropped (same as JS), an unknown `current`
    // becomes .unicorn (JS kept the garbage string; the enum cannot).
    @Test func unknownSpeciesKeyDroppedAndUnknownCurrentBecomesUnicorn() {
        let json = """
        {"children":[{"id":"c1","name":"Léa","nameRev":{"at":1,"by":"d"},"touchedAt":2,"profile":{"chosen":true,"current":"dodo","currentRev":{"at":1,"by":"d"},"species":{"dodo":{"config":{"species":"dodo","stage":9,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":1,"by":"d"}}},"stars":{"earned":{},"spent":{}},"clears":{}}}],"activeId":"c1","removed":{}}
        """
        let kv = InMemoryKVStore([ProfileStorage.rosterKey: json])
        let roster = initialRoster(kv: kv, device: device, now: now)
        let p = roster.children[0].profile
        #expect(p.current == .unicorn)
        for s in Species.allCases {
            #expect(p.species[s].config.stage == 0) // the dodo's 9 went nowhere
        }
    }

    // TS: `{ ...blankConfig(s), ...src.config, species: s }` — the slot key
    // always wins over whatever species the stored config claims.
    @Test func speciesSlotKeyOverridesTheStoredConfigSpecies() {
        let loose = ["fox": LooseSpeciesProgress(config: LooseMascotConfig(species: "cat", stage: 4))]
        let map = normalizeSpecies(loose)
        #expect(map[.fox].config.species == .fox)
        #expect(map[.fox].config.stage == 4)
    }

    @Test func partialRevNormalisesFieldWise() {
        let rev = normalizeRev(LooseRev(at: 5))
        #expect(rev == Rev(at: 5, by: ""))
        #expect(normalizeRev(nil) == .zero)
    }

    @Test func emptyStorageGivesTheEmptyRoster() {
        let roster = initialRoster(kv: InMemoryKVStore(), device: device, now: now)
        #expect(roster == Roster(children: [], activeId: nil, removed: [:]))
    }
}

@Suite struct ChildHelperTests {
    @Test func trimsAndFallsBackToJoueur() {
        let trimmed = child(name: "  Léo  ", profile: defaultProfile, device: device, now: now)
        #expect(trimmed.name == "Léo")
        let blank = child(name: "   ", profile: defaultProfile, device: device, now: now)
        #expect(blank.name == "Joueur")
    }

    // NB oddity (spec §7.8), ported as-is: `child()` (and so createChild) does
    // NOT clamp to 14 characters — only renameChild does.
    @Test func doesNotClampLongNames() {
        let long = String(repeating: "a", count: 30)
        #expect(child(name: long, profile: defaultProfile, device: device, now: now).name == long)
    }

    @Test func stampsNameRevAndTouchedAtWithTheGivenClock() {
        let c = child(name: "Léa", profile: defaultProfile, device: device, now: now)
        #expect(c.nameRev == Rev(at: now, by: device))
        #expect(c.touchedAt == now)
        #expect(!c.id.isEmpty)
        #expect(c.id == c.id.lowercased()) // ids mint lowercase, like JS uuids
    }

    @Test func mintsDistinctIds() {
        let a = child(name: "Léa", profile: defaultProfile, device: device, now: now)
        let b = child(name: "Tom", profile: defaultProfile, device: device, now: now)
        #expect(a.id != b.id)
    }
}
