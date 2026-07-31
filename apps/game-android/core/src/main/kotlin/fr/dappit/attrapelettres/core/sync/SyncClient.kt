package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.persistence.Roster

/* -------------------------------------------------------------------------- */
/* Household sync — the transport around Merge.kt.                              */
/*                                                                             */
/* No accounts, no e-mail, no login. One device creates a household and shows a */
/* short code; another types it in. That is the entire identity model, and it   */
/* is why the server holds nothing but opaque ids and integers.                 */
/*                                                                             */
/* Sync runs on mount and on resume, NEVER on a write — gameplay stays          */
/* offline-first, and a child mid-round must never wait on the network.         */
/*                                                                             */
/* A7: there is NO HTTP in this file and no URL anywhere in `:core`. The        */
/* `services/api` contract is being reworked and the household document shape   */
/* is what a client binds to hardest, so what ships now is the seam and a stub  */
/* behind it. Merge.kt is unaffected by that wait — it is pure and takes no     */
/* transport, so the invariant-9-critical half of sync is portable and          */
/* property-tested today, and only the last few hundred bytes of plumbing are   */
/* deferred. Household identity (the id, the join code, the ETag) is deferred   */
/* with the contract: it is entirely about how the document is addressed.       */
/* -------------------------------------------------------------------------- */

/**
 * The transport seam, exactly as ARCHITECTURE.md §5 declares it: one round
 * trip, our document up, the household's document back.
 *
 * `suspend` — and this is the one place in `:core` where that is right. Every
 * storage and gameplay method is deliberately synchronous (invariant 1 has
 * nowhere to await), but sync is explicitly the thing that never runs on the
 * tap path, so the network's asynchrony belongs in the type.
 *
 * Errors travel as exceptions and are the caller's to swallow: an unreachable
 * server must never stop a child playing, so the profile store logs and carries
 * on with the local roster. Invariant 11's philosophy, applied to sync.
 */
interface SyncTransport {
    suspend fun exchange(payload: WireRoster): WireRoster
}

/**
 * An in-memory household document, standing in for the server until the
 * contract lands.
 *
 * It MERGES what it is handed rather than storing it, because that is the
 * behaviour a household document has to have: two phones that exchange in
 * either order must both come home with everything. (The real endpoint reaches
 * the same place through optimistic concurrency — it rejects a write built on a
 * stale read and the client re-pulls — but a stub that merged nothing would let
 * the tests pass while hiding exactly the star-losing bug this module exists to
 * prevent.)
 *
 * Not thread-safe, and does not need to be: it is a test double.
 */
class StubSyncTransport(initial: WireRoster? = null) : SyncTransport {

    /** The stored household document — null until the first device pushes one. */
    var document: WireRoster? = initial
        private set

    /** How many round trips have been served. Handy for "did it even call?" tests. */
    var exchanges: Int = 0
        private set

    override suspend fun exchange(payload: WireRoster): WireRoster {
        exchanges++
        val current = document
        val next = if (current == null) payload else mergeWire(current, payload)
        document = next
        return next
    }

    /** Simulate the other phone landing a write while this one was mid-sync. */
    fun interleave(next: WireRoster) {
        val current = document
        document = if (current == null) next else mergeWire(current, next)
    }
}

/**
 * Pull, merge, push — the one operation.
 *
 * Idempotent and safe to call on every resume: Merge.kt guarantees that
 * repeating it changes nothing, so there is no "have I synced already?" state
 * to keep and no way for a double resume to double a star.
 *
 * The order matters and is not the obvious one. What comes back from
 * [SyncTransport.exchange] is folded into the LOCAL roster, not the other way
 * round, because the two asymmetries in `mergeRoster` are both about this
 * device: `activeId` stays whoever is holding this tablet, and local children
 * keep their positions in the list. Everything that is actually a count is
 * symmetric, so nothing is lost either way.
 */
class SyncClient(private val transport: SyncTransport) {

    suspend fun syncOnce(local: Roster): Roster {
        val household = transport.exchange(toWire(local))
        return mergeRoster(local, fromWire(household, local))
    }
}
