package fr.dappit.attrapelettres.core.telemetry

/**
 * The network seam. `:core` makes no network call; it hands finished bytes and
 * a finished URL to an adapter that lives in `:platform` (W17).
 *
 * The signature is the privacy boundary: `(ByteArray, String)` and nothing
 * else. There is no header bag, no credential, no cookie jar, no session — so
 * the web's `credentials: "omit"` is not a flag a caller can forget to pass, it
 * is the absence of any way to attach one. The adapter's own obligations: an
 * OkHttp client with `cookieJar = CookieJar.NO_COOKIES`, no
 * `Authenticator`, no interceptor that adds an identifier, and no header beyond
 * `content-type: application/json`. Responses ignored; errors swallowed —
 * telemetry must never surface to a child.
 *
 * `suspend`, like `SyncTransport` and for the same reason: this is one of the
 * two things in `:core` that is explicitly NOT on the tap path. `track()`
 * itself stays synchronous (invariant 1) and merely enqueues; the hop happens
 * later, in the scope [Telemetry] was handed.
 *
 * NB: ARCHITECTURE.md §5 sketches this as `suspend fun send(body: ByteArray)`.
 * The `url` parameter is added because the module posts to two paths
 * (`/events` and `/errors`) and the tests assert which; the sketch was not
 * exhaustive (the same latitude `AudioPort.kt` took with `AudioEngine`). It is
 * a `String` rather than a `java.net.URI` because the base is a plain
 * concatenation of two strings the module already holds and the adapter builds
 * whatever request type its client wants — `:core` has no business owning a URL
 * type.
 */
interface TelemetryTransport {
    suspend fun send(body: ByteArray, url: String)
}

/**
 * A transport that goes nowhere. The shipping default when no endpoint is
 * configured, and the reason a dev build posts nowhere.
 */
object NullTelemetryTransport : TelemetryTransport {
    override suspend fun send(body: ByteArray, url: String) {
        // Deliberately empty: there is no endpoint behind this build.
    }
}

/**
 * Tests. Records every send in order.
 *
 * Synchronised, unlike `InMemoryKVStore`: everything else in `:core` is
 * main-thread bound, but a send runs inside a coroutine on whatever dispatcher
 * the app layer's scope carries, so this one double really can be touched from
 * two threads.
 */
class RecordingTelemetryTransport(
    /** When true, every [send] throws — proving the swallow path. */
    var failing: Boolean = false,
) : TelemetryTransport {

    /**
     * One recorded call. Not a `data class`: the payload is a `ByteArray`, and
     * a generated `equals` over one compares identities, which is exactly the
     * confusion this class does not need. The bytes are kept as bytes on
     * purpose — the "no child's name in a payload" assertion is only honest if
     * it runs on what would actually have left the device.
     */
    class Sent(val url: String, val body: ByteArray) {
        val json: String get() = body.decodeToString()
    }

    private val lock = Any()
    private val storage = mutableListOf<Sent>()

    val sent: List<Sent>
        get() = synchronized(lock) { storage.toList() }

    fun clear() {
        synchronized(lock) { storage.clear() }
    }

    override suspend fun send(body: ByteArray, url: String) {
        synchronized(lock) { storage.add(Sent(url, body)) }
        if (failing) throw TelemetryTransportFailure()
    }
}

/** The failure a [RecordingTelemetryTransport] raises when it is asked to. */
class TelemetryTransportFailure : Exception("telemetry transport failed")
