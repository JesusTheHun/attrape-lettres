import Testing

@testable import ALCore

/// The tile-id counters from `src/levels.ts`. Monotonicity is not cosmetic: it
/// is what stops SwiftUI structurally identifying round *n*'s tile 0 with round
/// *n+1*'s tile 0 and reusing the view, which would carry press/shake layer
/// state across rounds (invariants 1 and 2).
@Suite struct TileIDTests {

    @Test func idsAreMonotonicAndStartAtTheGivenValue() {
        let ids = TileIDAllocator()
        #expect((0..<5).map { _ in ids.next() } == [0, 1, 2, 3, 4])

        let seeded = TileIDAllocator(next: 100)
        #expect((0..<3).map { _ in seeded.next() } == [100, 101, 102])
    }

    @Test func idsNeverRepeatWithinAnAllocator() {
        let ids = TileIDAllocator()
        let drawn = (0..<1000).map { _ in ids.next() }
        #expect(Set(drawn).count == 1000)
    }

    @Test func allocatorsAreIndependent() {
        let a = TileIDAllocator()
        let b = TileIDAllocator()
        _ = a.next()
        _ = a.next()
        #expect(b.next() == 0)
        #expect(a.next() == 2)
    }

    /// The shared allocator is a reference: two handles advance the same counter.
    /// (Asserted relatively — the suite order must not decide the absolute value.)
    @Test func sharedAllocatorIsOneCounter() {
        let first = TileIDAllocator.shared.next()
        let second = TileIDAllocator.shared.next()
        #expect(second == first + 1)
    }
}
