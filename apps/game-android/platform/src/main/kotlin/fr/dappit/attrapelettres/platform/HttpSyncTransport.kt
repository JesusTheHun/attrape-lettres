package fr.dappit.attrapelettres.platform

import fr.dappit.attrapelettres.core.sync.SyncTransport
import fr.dappit.attrapelettres.core.sync.WireRoster
import fr.dappit.attrapelettres.core.sync.mergeWire
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* -------------------------------------------------------------------------- */
/* STATUS: A SKETCH, DELIBERATELY UNFINISHED, AND NOT WIRED. (A7)               */
/*                                                                             */
/* `services/api`'s contract is being reworked, and the household document      */
/* shape is what a client binds to hardest. So this file ships the halves that  */
/* are stable and refuses the half that is not:                                 */
/*                                                                             */
/* REAL, and written to the frozen-as-described contract:                       */
/*   - the addressing: GET / PUT `{endpoint}/household/{id}` by plain string    */
/*     concatenation (a URL builder would percent-escape the separator);        */
/*   - ETag optimistic concurrency: the pull's ETag rides the push as           */
/*     `if-match`, a stale write comes back 412, and the client re-pulls,       */
/*     re-merges and retries — never forces. The other phone's write is         */
/*     somebody's stars;                                                       */
/*   - the retry loop (3 attempts, then give up and try next resume) and the    */
/*     404-means-no-document-yet case, both 1:1 with `sync/client.ts`;          */
/*   - the fold: core's seam is ONE round trip (`exchange`), so the web's       */
/*     pull-merge-push cycle lives here, and the merge is `mergeWire` — the     */
/*     wire-to-wire fold core exposes precisely so a party without a local      */
/*     `Roster` can merge two household documents without ever materialising a  */
/*     child's name (invariant 10).                                            */
/*                                                                             */
/* DELIBERATELY ABSENT until the contract lands:                                */
/*   - the DOCUMENT CODEC. `encodeDocument` / `decodeDocument` throw            */
/*     [SyncContractNotLanded]. Hand-writing a JSON codec for the whole         */
/*     `WireRoster` tree here would bind this app to the exact shape that is    */
/*     being reworked — and `:core` keeps kotlinx.serialization to itself       */
/*     (`implementation`, not `api`), which is a fence, not an oversight.       */
/*     When the contract lands, the codec belongs in `:core` next to `Wire.kt`  */
/*     where `WireTest` can assert on the serialised bytes; these two privates  */
/*     then collapse to calls.                                                  */
/*   - HOUSEHOLD IDENTITY. The id and the join code are entirely about how the  */
/*     document is addressed, so they wait with the contract. This class takes  */
/*     `household` as an injected read and invents no create/join flow; a null  */
/*     or empty answer means "not in a household", and the exchange is a        */
/*     silent no-op, exactly as a missing endpoint is.                          */
/*   - the ETag CACHE. The TS stores the push's ETag under `ETAG_KEY` but       */
/*     never reads it back — every cycle pushes with the etag of its OWN pull.  */
/*     The vestige is not ported; if the reworked contract gives the stored     */
/*     etag a job, this class will need a `KVStore` it deliberately does not    */
/*     take today.                                                             */
/*                                                                             */
/* Consequences: `Adapters.kt` wires `ProfileStore` with `sync = null`, nothing */
/* constructs this class in production, and if something someday does before    */
/* the codec lands, what escapes is an IOException — an `Exception`, which      */
/* `ProfileStore.syncNow`'s swallow clause catches, so the device just keeps    */
/* playing alone. The test suite pins that.                                    */
/* -------------------------------------------------------------------------- */

/**
 * What the codec stubs throw. An [IOException] on purpose: to the caller it is
 * indistinguishable from an unreachable server, which is exactly the behaviour
 * an unfinished transport should have — sync quietly does nothing, gameplay
 * stays offline-first, nobody waits and nothing crashes (the sync twin of
 * invariant 11's philosophy).
 */
class SyncContractNotLanded :
    IOException("household document codec pending the services/api rework (A7)")

/** TS `throw new Error("sync pull 500")` — same message bytes, typed status. */
class SyncHttpError(operation: String, val status: Int) :
    IOException("sync $operation $status")

/**
 * `SyncTransport` over `HttpURLConnection` — the Android twin of
 * `URLSessionSyncTransport.swift`, reshaped to core's one-round-trip seam.
 *
 * Both reads are LAZY, per call — the `VITE_SYNC_URL` analogue. That keeps
 * "endpoint absent means sync disabled, silently" true, and it is what lets a
 * test vary them. The connection factory is [PrivateHttp]: same timeouts, same
 * no-cookie posture as telemetry, one place to audit.
 *
 * Sync runs on mount and on resume, never on a write; errors travel as
 * exceptions and are `ProfileStore.syncNow`'s to swallow.
 */
class HttpSyncTransport(
    private val endpoint: () -> String?,
    private val household: () -> String?,
    private val open: (String) -> HttpURLConnection = PrivateHttp::open,
) : SyncTransport {

    override suspend fun exchange(payload: WireRoster): WireRoster {
        // TS truthiness on both guards: null and "" alike mean disabled, and a
        // disabled exchange hands the payload straight back — `syncOnce`'s
        // merge of it into the local roster is then the identity.
        val base = endpoint()
        val id = household()
        if (base.isNullOrEmpty() || id.isNullOrEmpty()) return payload
        val url = urlFor(base, id)

        return withContext(Dispatchers.IO) {
            var carried = payload
            repeat(MAX_ATTEMPTS) {
                val remote = pull(url)
                val merged =
                    if (remote == null) carried else mergeWire(remote.document, carried)
                if (push(url, merged, remote?.etag)) return@withContext merged
                // Someone else wrote between our read and our write. Loop:
                // pull their version, merge on top, try again. Never force.
                carried = merged
            }
            // Three collisions in a row: hand back what we merged so the local
            // roster still gains everything we saw, and try again next resume.
            carried
        }
    }

    /** A pulled household document plus the ETag its push must carry. */
    private class Remote(val document: WireRoster, val etag: String)

    private fun pull(url: String): Remote? {
        val connection = open(url)
        try {
            connection.requestMethod = "GET"
            val status = connection.responseCode
            if (status == 404) return null // no household document yet
            if (status !in 200..299) throw SyncHttpError("pull", status)
            val bytes = connection.inputStream.use { stream -> stream.readBytes() }
            return Remote(decodeDocument(bytes), connection.getHeaderField("ETag") ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /** True on success, false on a 412 conflict; anything else throws. */
    private fun push(url: String, document: WireRoster, etag: String?): Boolean {
        // Encode before opening: a payload we cannot serialise must not cost a
        // socket, and today (codec pending) it never does.
        val body = encodeDocument(document)
        val connection = open(url)
        try {
            connection.requestMethod = "PUT"
            applyPushHeaders(connection, etag)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { stream -> stream.write(body) }
            val status = connection.responseCode
            // Optimistic concurrency: the other phone's write is somebody's
            // stars. (The 200 response's fresh ETag is dropped — see the file
            // header on the unported ETag cache.)
            if (status == 412) return false
            if (status !in 200..299) throw SyncHttpError("push", status)
            return true
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------ the unfinished half (A7)

    @Suppress("UNUSED_PARAMETER")
    private fun decodeDocument(bytes: ByteArray): WireRoster = throw SyncContractNotLanded()

    @Suppress("UNUSED_PARAMETER")
    private fun encodeDocument(document: WireRoster): ByteArray = throw SyncContractNotLanded()

    companion object {
        /** TS: `for (let attempt = 0; attempt < 3; attempt++)`. */
        internal const val MAX_ATTEMPTS = 3

        /**
         * `${endpoint}/household/${household}` — plain concatenation, as the
         * TS template does and for the same reason the Swift port spells out:
         * appending through a URL type would percent-escape the separator and
         * change the path the server sees.
         */
        internal fun urlFor(endpoint: String, household: String): String =
            "$endpoint/household/$household"

        /**
         * The push's two headers, and only those. TS spreads
         * `...(etag ? { "if-match": etag } : {})` — an empty string is falsy
         * there, so it sends no header. Mirrored.
         */
        internal fun applyPushHeaders(connection: HttpURLConnection, etag: String?) {
            connection.setRequestProperty("content-type", "application/json")
            if (!etag.isNullOrEmpty()) connection.setRequestProperty("if-match", etag)
        }
    }
}
