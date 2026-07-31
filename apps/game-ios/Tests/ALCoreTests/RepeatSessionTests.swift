import Testing

@testable import ALCore

/// Port of the `repeatSession` block of `src/levels.test.ts`, plus the two
/// port-specific properties the TypeScript could not have (index identity, and
/// the give-up after 64 rerolls).
///
/// Every case runs under a FRESH seeded source keyed by the loop index, so a
/// failure is reproducible from the printed seed rather than being a flake.
@Suite struct RepeatSessionTests {

    // MARK: - Shape

    @Test func lengthIsPickPlusRepeats() {
        for seed in 0..<40 {
            let out = repeatSessionIndices(count: 12, pick: 6, repeats: 3, .seeded(UInt64(seed)))
            #expect(out.count == 9, "seed \(seed)")
        }
    }

    @Test func picksAreDistinctAndRepeatsAppearExactlyTwice() {
        for seed in 0..<60 {
            let out = repeatSessionIndices(count: 12, pick: 6, repeats: 3, .seeded(UInt64(seed)))
            var counts: [Int: Int] = [:]
            for i in out { counts[i, default: 0] += 1 }
            #expect(counts.count == 6, "seed \(seed): distinct picks")
            #expect(counts.values.filter { $0 == 2 }.count == 3, "seed \(seed): doubled items")
            #expect(counts.values.allSatisfy { $0 <= 2 }, "seed \(seed): nothing thrice")
        }
    }

    @Test func everyItemComesFromThePool() {
        let pool = Content.syllableBank
        for seed in 0..<40 {
            let out = repeatSession(pool, pick: 8, repeats: 4, .seeded(UInt64(seed)))
            #expect(out.count == 12)
            #expect(out.allSatisfy { pool.contains($0) }, "seed \(seed)")
        }
    }

    // MARK: - Clamping (both counts clamp to the pool)

    /// `(["a","b","c"], 8, 4)` → 6, collision-free. Straight from `levels.test.ts`.
    @Test func bothCountsClampToThePool() {
        let pool = ["a", "b", "c"]
        for seed in 0..<60 {
            let out = repeatSession(pool, pick: 8, repeats: 4, .seeded(UInt64(seed)))
            #expect(out.count == 6, "seed \(seed)")
            #expect(!hasAdjacentEqual(out), "seed \(seed): \(out)")
        }
    }

    @Test func repeatsZeroIsAPlainShuffleOfThePicks() {
        let pool = ["a", "b", "c"]
        for seed in 0..<40 {
            let out = repeatSession(pool, pick: 3, repeats: 0, .seeded(UInt64(seed)))
            #expect(out.count == 3, "seed \(seed)")
            #expect(out.sorted() == pool, "seed \(seed)")
        }
    }

    @Test func aPoolShorterThanPickJustYieldsAShorterRun() {
        for seed in 0..<20 {
            let out = repeatSessionIndices(count: 4, pick: 10, repeats: 0, .seeded(UInt64(seed)))
            #expect(out.sorted() == [0, 1, 2, 3], "seed \(seed)")
        }
    }

    // MARK: - No two identical rounds in a row

    @Test func neverTwoIdenticalRoundsAdjacent() {
        // A sweep over the shapes the shipped ladders actually use.
        let configs: [(count: Int, pick: Int, repeats: Int)] = [
            (21, 5, 2), (42, 6, 2), (42, 8, 4), (12, 6, 3), (18, 4, 2),
            (6, 4, 2), (5, 5, 5), (3, 3, 3), (2, 2, 2), (2, 2, 1),
        ]
        for cfg in configs {
            for seed in 0..<80 {
                let out = repeatSessionIndices(
                    count: cfg.count, pick: cfg.pick, repeats: cfg.repeats,
                    .seeded(UInt64(seed))
                )
                #expect(
                    !hasAdjacentEqual(out),
                    "cfg \(cfg) seed \(seed): \(out)"
                )
            }
        }
    }

    @Test func runsOverTheRealContentPoolsAreCollisionFree() {
        for seed in 0..<40 {
            let letters = repeatSession(Content.letterWords, pick: 6, repeats: 2, .seeded(UInt64(seed)))
            #expect(letters.count == 8, "seed \(seed)")
            let words = repeatSession(Content.syllableWords, pick: 5, repeats: 2, .seeded(UInt64(seed)))
            #expect(words.count == 7, "seed \(seed)")
            // Compare by index-free identity: these tables have no duplicate rows,
            // so value adjacency is a valid proxy here.
            #expect(!hasAdjacentEqual(words.map(\.word)), "seed \(seed)")
        }
    }

    /// A one-element pool yields `[a, a]` — back-to-back and unavoidable. The TS
    /// accepts it (the 64 rerolls give up) and so do we; there is no failure here.
    @Test func aOneElementPoolYieldsTheUnavoidablePair() {
        for seed in 0..<20 {
            let out = repeatSession(["solo"], pick: 3, repeats: 3, .seeded(UInt64(seed)))
            #expect(out == ["solo", "solo"], "seed \(seed)")
        }
    }

    // MARK: - Index identity ≡ TS reference identity (the port-specific property)

    /// The TS adjacency test is `x === out[i - 1]` — REFERENCE identity. Two
    /// structurally identical pool rows are still two different items there, and
    /// must be here too. If this ever fails, someone re-ported the algorithm on
    /// `==` and the day a table grows a value-duplicate row (`SOUND_TARGETS[3]`
    /// is one `word` away from having four) the run shape changes silently.
    @Test func adjacencyIsByIndexNotByValue() {
        let pool = ["a", "a", "b"]  // two DISTINCT items that happen to be equal
        var sawEqualNeighbours = false
        for seed in 0..<200 {
            let out = repeatSession(pool, pick: 3, repeats: 1, .seeded(UInt64(seed)))
            #expect(out.count == 4, "seed \(seed)")
            if hasAdjacentEqual(out) { sawEqualNeighbours = true }
        }
        #expect(
            sawEqualNeighbours,
            "value-equal neighbours never appeared — the adjacency check is comparing values, not indices"
        )
    }

    @Test func indicesAndElementsAgree() {
        let pool = ["w", "x", "y", "z"]
        for seed in 0..<30 {
            let idx = repeatSessionIndices(count: pool.count, pick: 3, repeats: 1, .seeded(UInt64(seed)))
            let els = repeatSession(pool, pick: 3, repeats: 1, .seeded(UInt64(seed)))
            #expect(idx.map { pool[$0] } == els, "seed \(seed)")
        }
    }

    // MARK: - Determinism

    @Test func theSameSeedProducesTheSameRun() {
        for seed in 0..<20 {
            let a = repeatSessionIndices(count: 20, pick: 8, repeats: 4, .seeded(UInt64(seed)))
            let b = repeatSessionIndices(count: 20, pick: 8, repeats: 4, .seeded(UInt64(seed)))
            #expect(a == b, "seed \(seed)")
        }
    }

    @Test func differentSeedsProduceDifferentRuns() {
        let a = repeatSessionIndices(count: 20, pick: 8, repeats: 4, .seeded(1))
        let b = repeatSessionIndices(count: 20, pick: 8, repeats: 4, .seeded(2))
        #expect(a != b)
    }
}

private func hasAdjacentEqual<T: Equatable>(_ xs: [T]) -> Bool {
    guard xs.count > 1 else { return false }
    for i in 1..<xs.count where xs[i] == xs[i - 1] { return true }
    return false
}
