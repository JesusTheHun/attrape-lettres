package fr.dappit.attrapelettres.platform

import fr.dappit.attrapelettres.core.telemetry.TelemetryTransport
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* -------------------------------------------------------------------------- */
/* The network half of first-party telemetry — the Android twin of              */
/* URLSessionTelemetryTransport.swift, porting the POST in `src/telemetry.ts`.  */
/*                                                                             */
/* INVARIANT 10 — NOTHING IDENTIFYING LEAVES THE DEVICE.                       */
/*                                                                             */
/* Upstream of here it is already structural: `TelemetryProps` has no free-text */
/* field and no String key, and `Telemetry` hands this class finished bytes and */
/* a finished URL. This file's job is to not reopen the hole at the wire:       */
/*                                                                             */
/*   - The web's `credentials: "omit"` translates to: no cookie, no credential, */
/*     no session. `HttpURLConnection` attaches cookies only when a process-    */
/*     wide `CookieHandler` has been installed, and THIS APP NEVER INSTALLS     */
/*     ONE — there is no WebView, no OkHttp, no login, nothing that would. That */
/*     promise is recorded here because it cannot be revoked per-connection:    */
/*     whoever one day adds a `CookieHandler.setDefault(...)` anywhere in the   */
/*     app has reopened this seam and owes it a privacy review.                 */
/*   - No `Authorization`, no custom header beyond `content-type:              */
/*     application/json`, no query parameter, no user-agent string we compose.  */
/*   - The device id is never read here — it is not imported and not named.     */
/*     (It rides the SYNC payload as a counter KEY, by design: that is what     */
/*     makes two phones' stars mergeable. It never rides telemetry.)           */
/*                                                                             */
/* This is a Kids Category app: no third-party analytics, no PII, no device     */
/* information to third parties. There is no third party at all.               */
/*                                                                             */
/* NB: core's `TelemetryTransport` KDoc sketches the adapter's obligations in   */
/* OkHttp vocabulary ("cookieJar = NO_COOKIES, no Authenticator"). This port    */
/* deliberately took no HTTP dependency — the app makes two request shapes —    */
/* so the obligations land on `HttpURLConnection` as above: same promises,      */
/* different spelling.                                                         */
/* -------------------------------------------------------------------------- */

/**
 * The one way both transports open a connection — the Kotlin twin of
 * `PrivateURLSession`. Centralised so the timeout and cache posture cannot
 * drift between telemetry and sync.
 *
 * Timeouts are a deliberate deviation from both siblings: `fetch` has none and
 * `URLSession` defaults to 60 s, but `HttpURLConnection`'s default is ZERO,
 * which means "wait forever". A telemetry send that never returns would pin a
 * job in `Telemetry`'s pending list forever, and the backgrounding flush that
 * awaits those jobs (the `keepalive: true` analogue) would hang with it. A
 * fire-and-forget POST that has not answered in fifteen seconds is a POST that
 * is not going to.
 *
 * `useCaches = false` because neither request shape is cacheable and an HTTP
 * cache is one more place bytes linger on disk.
 */
internal object PrivateHttp {
    const val CONNECT_TIMEOUT_MS: Int = 15_000
    const val READ_TIMEOUT_MS: Int = 15_000

    fun open(url: String): HttpURLConnection {
        // URI first: `URL(String)` is deprecated since Java 20, and URI's
        // stricter parsing rejects garbage before a socket is ever touched. A
        // malformed endpoint throws here, inside the send, where the callers
        // already swallow (Telemetry) or tolerate (ProfileStore.syncNow) it —
        // the Swift port's `badURL` reaches the same place.
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        return connection
    }
}

/**
 * `TelemetryTransport` over `HttpURLConnection`. The signature it implements is
 * the privacy boundary: `(ByteArray, String)` and nothing else — there is no
 * header bag, no credential and no cookie jar for a caller to fill in.
 *
 * Error behaviour, 1:1 with the siblings and asymmetric on purpose:
 *
 *   - A TRANSPORT failure (no route, DNS, timeout) throws, exactly like
 *     `URLSession` does and like `fetch` rejects — and `Telemetry.post`
 *     swallows it, because telemetry must never surface to a child. Throwing
 *     here rather than swallowing locally is what lets a test see the failure.
 *   - An HTTP error STATUS does not throw. `fetch` resolves on a 500 and the
 *     web ignores the response entirely; matching that keeps "what the server
 *     thinks of the payload" from ever becoming this app's problem.
 *
 * The response body is drained and discarded — draining is connection hygiene,
 * discarding is the contract.
 *
 * `open` is injectable so the tests can assert on the exact request that would
 * have gone out — method, the single header, the bytes — without a socket,
 * the same seam the Swift port exposes as its internal `request` builder.
 */
class HttpTelemetryTransport(
    private val open: (String) -> HttpURLConnection = PrivateHttp::open,
) : TelemetryTransport {

    override suspend fun send(body: ByteArray, url: String) {
        // Off the caller's thread before any blocking I/O: `Telemetry` launches
        // sends into whatever scope the app layer gave it, and nothing obliges
        // that scope to be off the main thread. The hop lives in the adapter so
        // no caller can forget it. (The blocking read inside is not cancellable
        // mid-flight; the read timeout above bounds how long that can matter.)
        withContext(Dispatchers.IO) {
            val connection = open(url)
            try {
                connection.requestMethod = "POST"
                // The ONLY header. Anything else here is a privacy review.
                connection.setRequestProperty("content-type", "application/json")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { stream -> stream.write(body) }
                // Complete the exchange; ignore what came back.
                val status = connection.responseCode
                val response =
                    if (status >= 400) connection.errorStream else connection.inputStream
                response?.use { stream -> stream.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
