package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.Roster
import fr.dappit.attrapelettres.core.persistence.SpeciesProgress
import fr.dappit.attrapelettres.core.persistence.StarCounters

/* -------------------------------------------------------------------------- */
/* Cross-device merge — PURE. No storage, no network, no clock reads except     */
/* through the `now` arguments callers pass in.                                 */
/*                                                                             */
/* The problem this solves: one child plays on Dad's phone and Mum's phone, and */
/* both can be offline at once. Whatever arrives from the other device must be  */
/* folded in WITHOUT losing anything the child earned here. Every merge below   */
/* is commutative, associative and idempotent, so sync can run in any order,    */
/* twice, or after a rollback, and still land on the same answer.               */
/*                                                                             */
/* Two classes of field, and the whole design is picking the right one:         */
/*   • counters  — anything a child earns or accumulates. Merged per device,    */
/*                 never overwritten. Lossless.                                 */
/*   • LWW       — cosmetics only (which mascot, its colours, a name). Losing   */
/*                 one of these costs nothing; losing a star costs trust.       */
/*                                                                             */
/* Port of `apps/game-web/src/sync/merge.ts`, function for function. The purity  */
/* is what makes `MergePropertyTest` runnable as ordinary JUnit — and the        */
/* property tests are what make invariant 9 checkable rather than asserted.      */
/* -------------------------------------------------------------------------- */

/* -- counters ---------------------------------------------------------------*/

/**
 * Per-device grow-only counter merge. A device only ever increments its OWN
 * key, so a key present on both sides can only differ by one side being stale —
 * `max` takes the fresher without double-counting. This is the whole trick.
 *
 * The counter is spelled `Map<String, Int>` rather than through a type alias
 * for the same reason `previewReward` spells its ledger out: these are the
 * shapes the stored JSON has, and naming them where they are used keeps the
 * merge readable without a trip to another file.
 */
fun mergeCounter(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
    val out = LinkedHashMap(a)
    for ((device, n) in b) {
        out[device] = maxOf(out[device] ?: 0, n)
    }
    return out
}

fun sumCounter(c: Map<String, Int>): Int = c.values.sum()

/** Add to this device's own slot. The only legal way to change a counter. */
fun bump(c: Map<String, Int>, device: String, by: Int): Map<String, Int> {
    val out = LinkedHashMap(c)
    // NB: a bump of +0 still CREATES the key (the TS spreads `[device]: 0` in).
    // No award passes 0 any more — every finished run pays the curve — but the
    // key-creating behaviour is the TS's and stays ported as-is.
    out[device] = (c[device] ?: 0) + by
    return out
}

fun emptyStars(): StarCounters = StarCounters(earned = emptyMap(), spent = emptyMap())

fun mergeStars(a: StarCounters, b: StarCounters): StarCounters =
    StarCounters(
        earned = mergeCounter(a.earned, b.earned),
        spent = mergeCounter(a.spent, b.spent),
    )

/**
 * Spendable stars = everything ever earned minus everything ever spent.
 *
 * The floor is not paranoia. Two devices offline, both see 10 stars, both buy
 * an 8-star item: after merge Σearned=10, Σspent=16. The child keeps BOTH items
 * and the balance floors at 0 — we never claw a purchase back from a six-year-
 * old to satisfy arithmetic. Overdraw costs us a few stars; a mascot vanishing
 * from the shelf costs us the child.
 */
fun balanceOf(stars: StarCounters): Int =
    maxOf(0, sumCounter(stars.earned) - sumCounter(stars.spent))

fun mergeClears(
    a: Map<String, Map<String, Int>>,
    b: Map<String, Map<String, Int>>,
): Map<String, Map<String, Int>> {
    val out = LinkedHashMap<String, Map<String, Int>>()
    // The TS iterates `new Set([...keys(a), ...keys(b)])`; `+` over two Sets is
    // the same union in the same order, and the result is a map on both sides,
    // so key order is not observable anyway.
    for (key in a.keys + b.keys) {
        out[key] = mergeCounter(a[key] ?: emptyMap(), b[key] ?: emptyMap())
    }
    return out
}

/**
 * Clears per (exercise, level), SUMMED across devices — two devices each
 * clearing level 1 once really is two clears, and the reward curve should decay
 * accordingly. Summing (not max-ing) is what keeps `rewardFor` honest and stops
 * "play it on the other phone" being a way to re-farm the 10-star jackpot.
 */
fun ledgerOf(clears: Map<String, Map<String, Int>>): Map<String, Int> =
    clears.mapValues { (_, byDevice) -> sumCounter(byDevice) }

/* -- last-write-wins --------------------------------------------------------*/

fun newRev(device: String, now: Long): Rev = Rev(now, device)

/** Later stamp wins; equal stamps break on deviceId so both sides agree. */
fun laterRev(a: Rev, b: Rev): Rev {
    if (a.at != b.at) return if (a.at > b.at) a else b
    return if (a.by >= b.by) a else b
}

/**
 * True when `a` is the winning stamp — i.e. the side holding it keeps its value.
 *
 * NB: the TS is `laterRev(a, b) === a` — REFERENCE identity, which Kotlin data
 * classes do not have. The predicate below is equivalent: when the stamps are
 * fully equal both orderings pick the `a`-side by `>=`, and equal stamps carry
 * equal payloads in practice (a device mints a fresh stamp for every LWW write).
 * The tie-break is plain `String.compareTo`: JS compares UTF-16 code units and
 * so does Kotlin, so the two platforms agree byte for byte. Do not "fix" this
 * with a locale-aware comparison.
 */
fun revWins(a: Rev, b: Rev): Boolean =
    if (a.at != b.at) a.at > b.at else a.by >= b.by

/* -- sets -------------------------------------------------------------------*/

/** Grow-only set: union, `a`'s order first. Nothing bought is ever un-bought. */
fun mergeOwned(a: List<String>, b: List<String>): List<String> {
    // Order-preserving union, NOT a Set literal: the array order is observable
    // (it is shop/inventory order) and the JSON round-trips it as written.
    val out = ArrayList(a)
    for (id in b) if (id !in out) out.add(id)
    return out
}

/* -- documents --------------------------------------------------------------*/

fun mergeSpecies(a: SpeciesProgress, b: SpeciesProgress): SpeciesProgress {
    val winner = if (revWins(a.rev, b.rev)) a else b
    return SpeciesProgress(
        // Look is cosmetic → LWW. Items are earned → union, regardless of who won.
        config = winner.config,
        owned = mergeOwned(a.owned, b.owned),
        rev = winner.rev,
    )
}

fun mergeProfile(a: PersistedProfile, b: PersistedProfile): PersistedProfile {
    val currentWinner = if (revWins(a.currentRev, b.currentRev)) a else b
    // The TS iterates `Object.keys(a.species)` and normalised data always holds
    // all five, so a union over both sides is the same thing — and it is the
    // safe same thing: a slot only the other device knows about is folded in
    // instead of dropped.
    val species = LinkedHashMap<Species, SpeciesProgress>(a.species)
    for ((s, theirs) in b.species) {
        val mine = species[s]
        species[s] = if (mine == null) theirs else mergeSpecies(mine, theirs)
    }
    return PersistedProfile(
        // Grow-only: once a child has picked a mascot, no merge un-picks it.
        chosen = a.chosen || b.chosen,
        current = currentWinner.current,
        currentRev = currentWinner.currentRev,
        species = species,
        stars = mergeStars(a.stars, b.stars),
        clears = mergeClears(a.clears, b.clears),
    )
}

fun mergeChild(a: ChildProfile, b: ChildProfile): ChildProfile {
    val nameWinner = if (revWins(a.nameRev, b.nameRev)) a else b
    return ChildProfile(
        id = a.id,
        name = nameWinner.name,
        nameRev = nameWinner.nameRev,
        touchedAt = maxOf(a.touchedAt, b.touchedAt),
        profile = mergeProfile(a.profile, b.profile),
    )
}

/**
 * Fold a remote roster into the local one.
 *
 * Two rules worth stating out loud:
 *
 * `activeId` is NOT merged. Who is holding this tablet says nothing about who
 * is holding the other one; the local value always survives.
 *
 * A delete only wins if nothing happened to that child afterwards
 * (`tombstone > touchedAt`). Delete-always-wins is the textbook rule and it is
 * wrong here: a parent tidying up the roster on one phone would silently erase
 * a week of play that happened on the other. A resurrected child is an
 * annoyance the parent fixes in two taps; a vanished child is unrecoverable.
 * So the tie goes to keeping the data.
 */
fun mergeRoster(local: Roster, remote: Roster): Roster {
    val removed = LinkedHashMap(local.removed)
    for ((id, at) in remote.removed) {
        removed[id] = maxOf(removed[id] ?: 0L, at)
    }

    // The TS folds through a JS `Map`, which is INSERTION-ORDERED: local
    // children keep their positions and remote-only children append in remote
    // order. That order is observable — it is the list of faces a parent taps —
    // so the fold goes through a LinkedHashMap, whose `put` on an existing key
    // keeps that key's position. Exactly the JS semantics.
    val byId = LinkedHashMap<String, ChildProfile>()
    for (c in local.children) byId[c.id] = c
    for (c in remote.children) {
        val mine = byId[c.id]
        byId[c.id] = if (mine == null) c else mergeChild(mine, c)
    }

    val children = byId.values.filter { c ->
        val tombstone = removed[c.id]
        tombstone == null || tombstone <= c.touchedAt
    }

    return Roster(
        children = children,
        activeId = if (children.any { it.id == local.activeId }) local.activeId else null,
        removed = removed,
    )
}
