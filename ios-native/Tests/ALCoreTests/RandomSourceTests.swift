import Testing

@testable import ALCore

/// D9. The injected RNG is what makes every round builder testable, so the
/// determinism it promises is itself under test — a seeded source that quietly
/// stopped being seeded would turn the whole `Levels` suite into noise that
/// passes.
@Suite struct RandomSourceTests {

    // MARK: - Seeded determinism

    @Test func sameSeedProducesTheSameDrawSequence() {
        let a = RandomSource.seeded(1234)
        let b = RandomSource.seeded(1234)
        for _ in 0..<500 {
            #expect(a.int(below: 97) == b.int(below: 97))
        }
    }

    @Test func sameSeedProducesTheSameShuffle() {
        let deck = (0..<40).map { "item-\($0)" }
        let a = RandomSource.seeded(99)
        let b = RandomSource.seeded(99)
        for _ in 0..<20 {
            #expect(a.shuffled(deck) == b.shuffled(deck))
        }
    }

    @Test func sameSeedProducesTheSameBoolsAndPicks() {
        let pool = ["A", "B", "C", "D", "E"]
        let a = RandomSource.seeded(7)
        let b = RandomSource.seeded(7)
        for _ in 0..<200 {
            #expect(a.bool() == b.bool())
            #expect(a.element(of: pool) == b.element(of: pool))
        }
    }

    /// Catches an RNG accidentally wired to a constant, which would make every
    /// determinism test above pass for the wrong reason.
    @Test func differentSeedsDiverge() {
        let deck = (0..<40).map { $0 }
        let a = RandomSource.seeded(1).shuffled(deck)
        let b = RandomSource.seeded(2).shuffled(deck)
        #expect(a != b)
    }

    @Test func aSeededSourceIsNotFrozen() {
        let rng = RandomSource.seeded(5)
        let draws = (0..<200).map { _ in rng.int(below: 1000) }
        #expect(Set(draws).count > 1)
    }

    // MARK: - int(below:)

    @Test func intStaysInRange() {
        let rng = RandomSource.seeded(42)
        for n in 1...20 {
            for _ in 0..<200 {
                let v = rng.int(below: n)
                #expect(v >= 0 && v < n)
            }
        }
    }

    /// The TS analogue `(Math.random() * n) | 0` yields 0 for n = 0 (NaN|0), and
    /// the call sites rely on never trapping.
    @Test func intBelowZeroOrLessIsZero() {
        let rng = RandomSource.seeded(3)
        #expect(rng.int(below: 0) == 0)
        #expect(rng.int(below: -5) == 0)
    }

    @Test func intCoversEveryValue() {
        let rng = RandomSource.seeded(11)
        var seen = Set<Int>()
        for _ in 0..<600 { seen.insert(rng.int(below: 6)) }
        #expect(seen == Set(0..<6))
    }

    @Test func boolIsNotStuck() {
        let rng = RandomSource.seeded(17)
        let flips = (0..<400).map { _ in rng.bool() }
        #expect(flips.contains(true))
        #expect(flips.contains(false))
    }

    /// ARCHITECTURE.md §5 spells this `next(upperBound:)`; data-core.md §4.1
    /// spells it `int(below:)`. Both must reach the same draw.
    @Test func nextUpperBoundIsTheSameFunctionAsIntBelow() {
        let a = RandomSource.seeded(2024)
        let b = RandomSource.seeded(2024)
        for _ in 0..<100 {
            #expect(a.next(upperBound: 13) == b.int(below: 13))
        }
    }

    // MARK: - shuffled

    @Test func shuffledIsAPermutation() {
        let deck = (0..<50).map { $0 }
        let rng = RandomSource.seeded(8)
        for _ in 0..<50 {
            let out = rng.shuffled(deck)
            #expect(out.count == deck.count)
            #expect(out.sorted() == deck)
        }
    }

    @Test func shuffledHandlesDegenerateInputs() {
        let rng = RandomSource.seeded(1)
        #expect(rng.shuffled([Int]()) == [])
        #expect(rng.shuffled([9]) == [9])
    }

    @Test func shuffledActuallyMoves() {
        let deck = (0..<20).map { $0 }
        let rng = RandomSource.seeded(4)
        let outs = (0..<10).map { _ in rng.shuffled(deck) }
        #expect(outs.contains { $0 != deck })
    }

    // MARK: - element(of:)

    @Test func elementOfEmptyIsNil() {
        #expect(RandomSource.seeded(1).element(of: [String]()) == nil)
    }

    @Test func elementIsAlwaysAMember() {
        let pool = ["CHA", "TON", "LA", "PIN"]
        let rng = RandomSource.seeded(6)
        for _ in 0..<200 {
            let picked = rng.element(of: pool)
            #expect(picked != nil)
            #expect(pool.contains(picked!))
        }
    }

    // MARK: - orderedUnique — the Swift-Set trap (data-core.md §4.3)

    @Test func orderedUniquePreservesFirstSeenOrder() {
        #expect(orderedUnique(["CHA", "TON", "LA", "CHA", "PIN", "TON"])
            == ["CHA", "TON", "LA", "PIN"])
    }

    @Test func orderedUniqueKeepsTheFirstOccurrenceNotTheLast() {
        #expect(orderedUnique([3, 1, 3, 2, 1]) == [3, 1, 2])
    }

    @Test func orderedUniqueOnEmptyAndAllDistinct() {
        #expect(orderedUnique([Int]()) == [])
        #expect(orderedUnique([1, 2, 3]) == [1, 2, 3])
    }

    /// The regression guard for the trap itself: a plain `Set` round-trip is
    /// randomised per process, so this same input through `Set` would be a
    /// different array on some launches. `orderedUnique` must be stable across
    /// repetitions within a process AND equal to the authored order.
    @Test func orderedUniqueIsStableAcrossRepetitions() {
        let xs = (0..<200).map { "s\($0 % 37)" }
        let first = orderedUnique(xs)
        for _ in 0..<50 {
            #expect(orderedUnique(xs) == first)
        }
        #expect(first == (0..<37).map { "s\($0)" })
    }
}
