import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Port of `src/hooks/useProfile.test.tsx`, against                             */
/* `ProfileStore(kv: InMemoryKVStore(), device: fixed, now: controllable)`.     */
/*                                                                             */
/* Every call below is a plain synchronous method call and asserts the returned */
/* value and the resulting state in the same expression — that is invariant 1's */
/* test-level guard for this scope (nothing on the award/spend path can await). */
/* -------------------------------------------------------------------------- */

private let device = "this-phone"
private let t0: Millis = 1_700_000_000_000

private let HORN = CustomizationOption(
    id: "unicorn.horn.rainbow",
    species: .unicorn,
    category: .color,
    slot: "hornColor",
    value: "#F0A",
    name: "Corne arc-en-ciel",
    cost: 4
)

@MainActor
private func makeStore(
    kv: KVStore = InMemoryKVStore(),
    now: @escaping () -> Millis = { t0 }
) -> ProfileStore {
    ProfileStore(kv: kv, device: { device }, now: now)
}

@MainActor
@Suite struct ProfileStoreRosterGateTests {
    @Test func startsWithNoChildrenAndNoActivePlayer() {
        let store = makeStore()
        #expect(store.children.isEmpty)
        #expect(store.activeId == nil)
        // The exposed profile is a safe default until someone is playing.
        #expect(store.profile.chosen == false)
        #expect(store.profile.balance == 0)
    }

    @Test func createChildAddsAPlayerAndMakesThemActiveSpeciesUnchosen() {
        let store = makeStore()
        store.createChild(name: "Léa")
        #expect(store.children.count == 1)
        #expect(store.activeId != nil)
        #expect(store.profile.chosen == false)
    }

    @Test func switchChildReturnsToTheWelcomeScreenSelectChildResumes() throws {
        let store = makeStore()
        store.createChild(name: "Léa")
        let id = try #require(store.activeId)
        store.switchChild()
        #expect(store.activeId == nil)
        store.selectChild(id: id)
        #expect(store.activeId == id)
    }

    @Test func renameChildChangesTheNameTrimmedAndIgnoresEmptyInput() throws {
        let store = makeStore()
        store.createChild(name: "Léa")
        let id = try #require(store.activeId)
        store.renameChild(id: id, name: "  Léo  ")
        #expect(store.children[0].name == "Léo")
        store.renameChild(id: id, name: "   ")
        #expect(store.children[0].name == "Léo") // unchanged
    }

    @Test func renameChildClampsTo14CharactersButCreateChildDoesNot() throws {
        // TS asymmetry, ported as-is: only renameChild clamps.
        let long = "Anne-Charlotte-Éléonore"
        let store = makeStore()
        store.createChild(name: long)
        #expect(store.children[0].name == long)
        let id = try #require(store.activeId)
        store.renameChild(id: id, name: long)
        #expect(store.children[0].name == String(long.prefix(14)))
    }
}

@MainActor
@Suite struct ProfileStorePointsTests {
    @Test func awardGrantsTheCurveValueAndDecaysOnRepeat() {
        let store = makeStore()
        store.createChild(name: "Léa")
        let first = store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8)
        let second = store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8)
        #expect(first == Rewards.curve[0])
        #expect(second == Rewards.curve[1])
        #expect(store.profile.balance == Rewards.curve[0] + Rewards.curve[1])
    }

    @Test func firstTryRoundsAddTheAccuracyBonusOnTopOfTheCurve() {
        let store = makeStore()
        store.createChild(name: "Léa")
        // read-image has difficulty 2: a full-perfect 8-round run = curve + 2.
        let pts = store.award(exercise: .readImage, level: 1, perfectRounds: 8, totalRounds: 8)
        #expect(pts == Rewards.curve[0] + 2)
    }

    @Test func trainingExercisesAwardNothingButStillCountInTheLedger() {
        let store = makeStore()
        store.createChild(name: "Léa")
        let pts = store.award(exercise: .firstLetter, level: 1, perfectRounds: 8, totalRounds: 8) // even full-perfect
        #expect(pts == 0)
        #expect(store.profile.balance == 0)
        #expect(store.profile.ledger["first-letter:1"] == 1)
        #expect(store.preview(exercise: .firstLetter, level: 1) == 0) // hub shows no pill
    }

    @Test func aZeroPointAwardStillCreatesThisDevicesEarnedKey() {
        // TS `bump(earned, device, 0)` writes a `+0`, creating the key — the
        // counter's presence (not its value) is what records "this device
        // played". Ported as-is.
        let store = makeStore()
        store.createChild(name: "Léa")
        store.award(exercise: .firstLetter, level: 1, perfectRounds: 8, totalRounds: 8)
        #expect(store.profile.stars.earned[device] == 0)
    }

    @Test func awardWithNoActiveChildReturnsThePointsButWritesNothing() {
        // TS oddity, ported as-is: `updateActive` guards, `award` doesn't.
        let store = makeStore()
        let pts = store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8)
        #expect(pts == Rewards.curve[0])
        #expect(store.children.isEmpty)
        #expect(store.profile.balance == 0)
    }

    @Test func spendFailsWhenUnaffordableAndSucceedsOtherwise() {
        let store = makeStore()
        store.createChild(name: "Léa")
        #expect(store.spend(cost: 5) == false)
        store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8) // +10
        #expect(store.spend(cost: 5) == true)
        #expect(store.profile.balance == Rewards.curve[0] - 5)
    }

    @Test func buyDebitsOnceThenReEquipsFreeWhenAlreadyOwned() {
        let store = makeStore()
        store.createChild(name: "Léa")
        store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8) // +10
        #expect(store.buy(HORN) == true)
        #expect(store.profile.owned.contains(HORN.id))
        #expect(store.profile.config.colors["hornColor"] == "#F0A")
        let afterFirst = store.profile.balance
        store.buy(HORN) // owned -> free re-equip
        #expect(store.profile.balance == afterFirst)
    }

    @Test func buyFailsWhenUnaffordableAndWritesNothing() {
        let store = makeStore()
        store.createChild(name: "Léa")
        #expect(store.buy(HORN) == false)
        #expect(!store.profile.owned.contains(HORN.id))
        #expect(store.profile.config.colors["hornColor"] == nil)
    }
}

@MainActor
@Suite struct ProfileStoreMascotTests {
    @Test func keepsEachSpeciesGrowthAndLookStarsStayGlobal() {
        let store = makeStore()
        store.createChild(name: "Léa")
        store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8) // +10, global to the child
        store.chooseSpecies(.unicorn)
        store.buy(HORN) // unicorn owns a horn colour
        store.setConfig { c in
            var c = c
            c.stage = 3
            return c
        }
        let starsAfterBuy = store.profile.balance

        // Switch to the fox: it's a fresh baby, but stars are unchanged.
        store.chooseSpecies(.fox)
        #expect(store.profile.config.species == .fox)
        #expect(store.profile.config.stage == 0)
        #expect(!store.profile.owned.contains(HORN.id))
        #expect(store.profile.balance == starsAfterBuy)

        // Switch back to the unicorn: everything is exactly as we left it.
        store.chooseSpecies(.unicorn)
        #expect(store.profile.config.stage == 3)
        #expect(store.profile.config.colors["hornColor"] == "#F0A")
        #expect(store.profile.owned.contains(HORN.id))
    }

    @Test func setConfigValueOverloadReplacesTheCurrentSpeciesConfig() {
        let store = makeStore()
        store.createChild(name: "Léa")
        store.chooseSpecies(.unicorn)
        var next = store.profile.config
        next.stage = 7
        store.setConfig(next)
        #expect(store.profile.config.stage == 7)
    }
}

@MainActor
@Suite struct ProfileStoreSiblingTests {
    @Test func eachChildKeepsTheirOwnStarsAndProgress() throws {
        let store = makeStore()
        store.createChild(name: "Léa")
        let lea = try #require(store.activeId)
        store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8) // Léa: +10

        store.createChild(name: "Tom") // Tom becomes active, fresh
        #expect(store.profile.balance == 0)

        store.selectChild(id: lea)
        #expect(store.profile.balance == Rewards.curve[0])
    }
}

/* -------------------------------------------------------------------------- */
/* Upgrade path. A child who has been playing for months is sitting on a v3     */
/* blob; the v4 counters must reproduce her stars EXACTLY, not approximately.   */
/* Per storage.ts, the old keys and readers stay intact so a rollback still     */
/* finds data it understands. Fixtures are byte-for-byte from                   */
/* useProfile.test.tsx.                                                         */
/* -------------------------------------------------------------------------- */

private let v3Key = "attrape-lettres:roster:v3"
private let v1Key = "attrape-lettres:profile:v1"

private let v3Roster = """
{
  "children": [
    {
      "id": "lea-v3",
      "name": "Léa",
      "profile": {
        "chosen": true,
        "current": "fox",
        "species": {
          "fox": {
            "config": {
              "species": "fox",
              "stage": 3,
              "colors": { "furColor": "#F80" },
              "styles": {},
              "accessories": []
            },
            "owned": ["fox.fur.orange"]
          }
        },
        "balance": 17,
        "ledger": { "read-image:1": 2 }
      }
    }
  ],
  "activeId": "lea-v3"
}
"""

@MainActor
@Suite struct ProfileStoreV3MigrationTests {
    private func seeded() -> (ProfileStore, InMemoryKVStore) {
        let kv = InMemoryKVStore()
        kv.set(v3Roster, for: v3Key)
        return (makeStore(kv: kv), kv)
    }

    @Test func carriesStarsClearsLookAndItemsAcrossUntouched() {
        let (store, _) = seeded()
        #expect(store.children.count == 1)
        #expect(store.children[0].name == "Léa")
        #expect(store.activeId == "lea-v3")

        #expect(store.profile.balance == 17)
        #expect(store.profile.ledger["read-image:1"] == 2)
        #expect(store.profile.config.species == .fox)
        #expect(store.profile.config.stage == 3)
        #expect(store.profile.config.colors["furColor"] == "#F80")
        #expect(store.profile.owned.contains("fox.fur.orange"))
    }

    @Test func keepsTheRewardCurveWhereItWasNoReopeningTheJackpot() {
        let (store, _) = seeded()
        #expect(store.preview(exercise: .readImage, level: 1) == Rewards.rewardFor(priorClears: 2))
        let pts = store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8)
        #expect(pts == Rewards.rewardFor(priorClears: 2))
        #expect(store.profile.balance == 17 + Rewards.rewardFor(priorClears: 2))
    }

    @Test func leavesTheV3BlobInPlaceSoARollbackStillReadsData() {
        let (_, kv) = seeded()
        #expect(kv.string(v3Key) != nil)
    }

    @Test func migratedStarsMergeWithAnotherDeviceInsteadOfOverwriting() {
        let (store, _) = seeded()
        let migrated = store.children[0].profile
        // Mum's phone migrated its own v3 blob: two genuinely separate
        // progressions that only meet now, so they sum rather than one
        // replacing the other.
        var mum = migrated
        mum.stars = StarCounters(earned: ["mum-phone": 6], spent: [:])
        #expect(balanceOf(mergeProfile(migrated, mum).stars) == 23)
    }
}

@MainActor
@Suite struct ProfileStoreV1MigrationTests {
    @Test func promotesTheSingleLegacyMascotIntoItsSpeciesSlotWithItsStars() {
        let kv = InMemoryKVStore()
        kv.set(
            """
            {
              "chosen": true,
              "config": { "species": "cat", "stage": 1, "colors": {}, "styles": {}, "accessories": [] },
              "balance": 5,
              "ledger": { "first-letter:1": 1 },
              "owned": ["cat.whiskers.long"]
            }
            """,
            for: v1Key
        )
        let store = makeStore(kv: kv)
        #expect(store.children.count == 1)
        #expect(store.profile.balance == 5)
        #expect(store.profile.ledger["first-letter:1"] == 1)
        #expect(store.profile.config.species == .cat)
        #expect(store.profile.config.stage == 1)
        #expect(store.profile.owned.contains("cat.whiskers.long"))
    }
}

@MainActor
@Suite struct ProfileStoreDeleteTests {
    @Test func tombstonesTheIdSoTheFamilysOtherDeviceCantHandThemBack() throws {
        let kv = InMemoryKVStore()
        let store = makeStore(kv: kv)
        store.createChild(name: "Léa")
        let id = try #require(store.activeId)
        store.deleteChild(id: id)

        #expect(store.children.isEmpty)
        #expect(store.activeId == nil)
        let raw = try #require(kv.string(ProfileStorage.rosterKey))
        let saved = try #require(
            try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any]
        )
        let removed = try #require(saved["removed"] as? [String: Double])
        #expect(removed[id] ?? 0 > 0)
    }
}

@MainActor
@Suite struct ProfileStorePersistenceTests {
    @Test func persistsTheRosterAndRehydratesAFreshStore() {
        let kv = InMemoryKVStore()
        let store = makeStore(kv: kv)
        store.createChild(name: "Léa")
        store.award(exercise: .readImage, level: 1, perfectRounds: 0, totalRounds: 8)
        store.chooseSpecies(.fox)

        let reloaded = makeStore(kv: kv)
        #expect(reloaded.children.count == 1)
        #expect(reloaded.activeId != nil)
        #expect(reloaded.profile.chosen == true)
        #expect(reloaded.profile.config.species == .fox)
        #expect(reloaded.profile.balance == Rewards.curve[0])
    }

    @Test func initialRosterComputesButDoesNotSave() {
        // TS: `useState(initialRoster)` saves nothing until a write; a v3
        // migration must not eagerly mint a v4 blob.
        let kv = InMemoryKVStore()
        kv.set(v3Roster, for: v3Key)
        _ = makeStore(kv: kv)
        #expect(kv.string(ProfileStorage.rosterKey) == nil)
    }
}

/* -------------------------------------------------------------------------- */
/* The sync trigger — pull → merge → push on demand, NEVER on a write. The     */
/* full transport semantics live in SyncClientTests; here: the store folds a   */
/* merged roster back in, persists it, and a missing/disabled client no-ops.   */
/* -------------------------------------------------------------------------- */

private final class SeededServer: SyncTransport {
    var doc: WireRoster?
    private var version = 0

    init(_ initial: WireRoster?) {
        self.doc = initial
    }

    func pull(household: String) async throws -> (roster: WireRoster, etag: String)? {
        doc.map { ($0, String(version)) }
    }

    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult {
        let expected = doc != nil ? String(version) : nil
        guard etag == expected else { return .conflict }
        doc = roster
        version += 1
        return .ok(etag: String(version))
    }
}

@MainActor
@Suite struct ProfileStoreSyncTests {
    /// Let the detached sync Task run to completion.
    private func pump(_ times: Int = 50) async {
        for _ in 0..<times { await Task.yield() }
    }

    @Test func syncNowFoldsTheHouseholdIntoTheRosterAndPersists() async throws {
        let kv = InMemoryKVStore()
        // The other phone already uploaded Tom.
        let tom = ChildProfile(
            id: "tom",
            name: "Tom",
            nameRev: Rev(at: 10, by: "mum-phone"),
            touchedAt: 10,
            profile: defaultProfile
        )
        let server = SeededServer(toWire(Roster(children: [tom], activeId: nil, removed: [:])))
        let sync = SyncClient(kv: kv, transport: server, endpoint: { "https://sync.test" })
        sync.joinHousehold("family-1")

        let store = ProfileStore(kv: kv, device: { device }, now: { t0 }, sync: sync)
        store.createChild(name: "Léa")
        store.syncNow()
        await pump()

        #expect(store.children.map(\.id).sorted().contains("tom"))
        // The never-seen sibling arrives as the placeholder, not a name.
        #expect(store.children.first(where: { $0.id == "tom" })?.name == "Enfant")
        // And the merged roster was committed to disk.
        let raw = try #require(kv.string(ProfileStorage.rosterKey))
        #expect(raw.contains("tom"))
    }

    @Test func syncNowWithoutAClientOrHouseholdIsANoOp() async {
        let kv = InMemoryKVStore()
        let store = ProfileStore(kv: kv, device: { device }, now: { t0 }, sync: nil)
        store.createChild(name: "Léa")
        let before = store.roster
        store.syncNow()
        await pump()
        #expect(store.roster == before)
    }
}
