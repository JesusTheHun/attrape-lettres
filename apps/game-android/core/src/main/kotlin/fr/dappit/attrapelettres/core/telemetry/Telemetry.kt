package fr.dappit.attrapelettres.core.telemetry

import fr.dappit.attrapelettres.core.platform.AppVersionProvider
import fr.dappit.attrapelettres.core.platform.KVStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/* -------------------------------------------------------------------------- */
/* First-party telemetry. Our server, no SDK, no vendor.                        */
/*                                                                             */
/* Kids Category guideline 1.3 bans sending personally identifiable information */
/* OR DEVICE INFORMATION to THIRD PARTIES. Both halves matter: "third party" is */
/* why this posts to our own endpoint instead of Firebase or PostHog, and       */
/* "device information" is why there is no SDK — every drop-in analytics client */
/* ships device model, OS, locale, screen size and an install id by default.    */
/* On Android that also rules out Crashlytics, whose whole value proposition is */
/* the device context this module refuses to collect: `reportError` below       */
/* carries a message and a stack and nothing else, on purpose.                  */
/*                                                                             */
/* What leaves the device, ever:                                                */
/*   • an event name from a closed list                                         */
/*   • numbers, and exercise ids from a closed list                             */
/*   • the app version                                                          */
/* That is all. No per-install identifier (see persistence/DeviceIdentity.kt —  */
/* NOT imported here, and it never will be), no child id, no name, no free      */
/* text, no timestamps of the child's day. The payload is genuinely anonymous   */
/* rather than merely pseudonymous, which is what lets the privacy policy say   */
/* so plainly and the Play Data safety form stay nearly empty.                  */
/* -------------------------------------------------------------------------- */
//
// Port of `src/telemetry.ts`. Owner of the second half of **invariant 10**;
// `sync/Wire.kt` owns the first.
//
// NOT ported, and deliberately: `installErrorReporting()`. Its three web
// listeners are platform wiring, and Android's twins live in `:app` —
// `Thread.setDefaultUncaughtExceptionHandler` for `window.onerror`, a
// `CoroutineExceptionHandler` for `unhandledrejection`, and a
// `ProcessLifecycleOwner` `ON_STOP` observer for the `visibilitychange` flush.
// The last one is also the `keepalive: true` analogue: `flush()` then
// `awaitPendingSends()` inside a `withContext(NonCancellable)`, so a send
// started as the app goes to background is allowed to finish.

/**
 * MAIN-THREAD BOUND, like everything else that holds mutable state in `:core`
 * (the Swift port spells this `@MainActor`; Kotlin has no such annotation, so
 * it is a contract). The queue is a plain list with no lock: `track()` is
 * called from pointer-down handlers and from screen callbacks, all of which
 * are on the main thread. Only the send itself leaves it.
 *
 * There is deliberately NO process-wide `Telemetry.shared`. The iOS port has
 * one because SwiftUI screens reach for a singleton; here every seam is
 * injected at the root (ARCHITECTURE §5), and a mutable global in `:core`
 * would be a piece of state two tests could race on for no gain.
 *
 * @param endpoint the base URL as a string, e.g. `"https://t.test"`. `null`
 *   **and the empty string** both make the module inert. The empty string
 *   matters: the TS reads `import.meta.env.VITE_TELEMETRY_URL` and guards with
 *   `if (!endpoint()) return`, so `""` is falsy there and the 1:1 test stubs
 *   exactly that.
 * @param scope where the fire-and-forget sends run. The app layer passes a
 *   scope that lives as long as the process; tests pass a `TestScope`, which is
 *   what makes "did anything leave the device?" a deterministic question.
 */
class Telemetry(
    endpoint: String?,
    private val transport: TelemetryTransport,
    kv: KVStore,
    private val appVersion: AppVersionProvider,
    private val scope: CoroutineScope,
) {
    /** Normalised at construction so every guard below is a plain null check. */
    private val baseUrl: String? = if (endpoint.isNullOrEmpty()) null else endpoint

    private val queue = mutableListOf<Queued>()
    private var flushing = false
    private val pending = mutableListOf<Job>()

    private data class Queued(val event: TelemetryEvent, val props: TelemetryProps)

    // ---------------------------------------------------------------- consent

    val consent: TelemetryConsent = TelemetryConsent(kv)

    /** `hasConsent()`. */
    val hasConsent: Boolean get() = consent.has

    /** `consentAnswered()` — unset ≠ refused. */
    val consentAnswered: Boolean get() = consent.answered

    /**
     * The public write path. Withdrawal **drains the queue immediately**:
     * retroactive for anything not yet sent, which is the difference between a
     * parent revoking consent and a parent revoking consent as soon as the next
     * batch happens to fill.
     */
    fun setConsent(on: Boolean) {
        consent.write(on)
        if (!on) queue.clear()
    }

    // ----------------------------------------------------------------- events

    /**
     * Record an event, if the parent opted in. Fire-and-forget: never awaited,
     * never blocks a tap, never throws into the game loop. Synchronous in and
     * out — the network hop happens in a coroutine, because invariant 1 forbids
     * anything on the feedback path behind a hop.
     */
    fun track(event: TelemetryEvent, props: TelemetryProps = TelemetryProps()) {
        if (!hasConsent || baseUrl == null) return
        queue.add(Queued(event, props))
        if (queue.size >= BATCH_SIZE) flush()
    }

    /**
     * Send whatever is queued. Called on backgrounding, and before a hard exit.
     *
     * The `flushing` re-entrancy guard is vestigial in JS but real here once a
     * coroutine is involved — keep it.
     */
    fun flush() {
        if (flushing || queue.isEmpty() || baseUrl == null) return
        flushing = true
        val batch = queue.toList()
        queue.clear()
        post("/events", encodeEnvelope(batch))
        flushing = false
    }

    /**
     * Crash/error reporting — deliberately **NOT** consent-gated.
     *
     * The payload carries no identifier of any kind, so it is not personal data
     * and needs no consent. That split is the point: we still hear about the
     * bug that breaks the game for the ~60% of parents who decline analytics.
     *
     * [message] and [stack] are the only free-form values in this file. They
     * are truncated, and NOTHING from app state is ever attached — the roster
     * holds children's first names, and one careless context dump would ship
     * them here.
     *
     * Truncation is 1:1 with the web: Kotlin's `take` counts UTF-16 code units
     * and so does JS `slice`, so the same input yields the same bytes. (The
     * Swift port had to record a deviation here because `String.prefix` counts
     * grapheme clusters; Kotlin needs none.)
     *
     * @param site the `where` of the wire payload — named `site` because
     *   `where` is a Kotlin soft keyword and reads badly as a named argument.
     */
    fun reportError(message: String, site: String = "unknown", stack: String = "") {
        if (baseUrl == null) return
        post(
            "/errors",
            encodeErrorReport(
                message = message.take(MESSAGE_LIMIT),
                site = site.take(SITE_LIMIT),
                stack = stack.take(STACK_LIMIT),
            ),
        )
    }

    /**
     * `err instanceof Error ? err : new Error(String(err))`. A `Throwable` also
     * brings the stack the web reads off `e.stack`.
     */
    fun reportError(error: Throwable, site: String = "unknown") {
        reportError(
            message = error.message ?: error.toString(),
            site = site,
            stack = error.stackTraceToString(),
        )
    }

    /**
     * Await every send this instance has started.
     *
     * Test support, and the hook the app layer's backgrounding observer uses to
     * reproduce `keepalive: true` — see the file header.
     */
    suspend fun awaitPendingSends() {
        val jobs = pending.toList()
        pending.clear()
        for (job in jobs) job.join()
    }

    // -------------------------------------------------------------- transport

    private fun post(path: String, json: String) {
        // `${endpoint}${path}` — plain concatenation, as the TS does, and the
        // whole reason the seam takes a String: there is no URL builder here to
        // percent-escape the leading slash or to grow a query parameter.
        val url = (baseUrl ?: return) + path
        val body = json.encodeToByteArray()
        val sender = transport
        pending += scope.launch {
            try {
                sender.send(body, url)
            } catch (cancelled: CancellationException) {
                // A cancelled scope is not a telemetry failure; let it unwind.
                throw cancelled
            } catch (_: Exception) {
                // Telemetry must never surface to a child. No log, no retry, no
                // toast: a flat network mid-round is not the child's problem.
            }
        }
    }

    // ------------------------------------------- wire encoding, hand-written

    /** `post("/events", { v, events })`. */
    private fun encodeEnvelope(batch: List<Queued>): String {
        val out = StringBuilder("{\"v\":")
        out.append(TelemetryJson.quoted(appVersion.marketing))
        out.append(",\"events\":[")
        batch.forEachIndexed { index, item ->
            if (index > 0) out.append(",")
            out.append("{\"event\":")
            out.append(TelemetryJson.quoted(item.event.wire))
            out.append(",\"props\":")
            out.append(encodeProps(item.props))
            out.append("}")
        }
        out.append("]}")
        return out.toString()
    }

    private fun encodeErrorReport(message: String, site: String, stack: String): String {
        // Key order matches the TS object literal: v, where, message, stack.
        val out = StringBuilder("{\"v\":")
        out.append(TelemetryJson.quoted(appVersion.marketing))
        out.append(",\"where\":")
        out.append(TelemetryJson.quoted(site))
        out.append(",\"message\":")
        out.append(TelemetryJson.quoted(message))
        out.append(",\"stack\":")
        out.append(TelemetryJson.quoted(stack))
        out.append("}")
        return out.toString()
    }

    /**
     * One `let` per field, in the TS insertion order, so the JSON is
     * byte-comparable with the PWA's and the iOS app's: the `NUMERIC_KEYS`
     * order first (`level, rounds, perfect, points, cost, stage, daysLeft`),
     * then `exercise` — that is exactly what `sanitize` produced and
     * `JSON.stringify` preserved.
     *
     * Hand-written **is the point** (D12). `kotlinx.serialization` would emit
     * fields in declaration order — which happens to differ from the wire order
     * here — and would silently start emitting any field a future edit adds.
     * Adding a field without touching this function produces a field that is
     * never sent, which is the safe direction to fail in.
     */
    private fun encodeProps(p: TelemetryProps): String {
        val parts = mutableListOf<String>()
        p.level?.let { parts.add("\"level\":$it") }
        p.rounds?.let { parts.add("\"rounds\":$it") }
        p.perfect?.let { parts.add("\"perfect\":$it") }
        p.points?.let { parts.add("\"points\":$it") }
        p.cost?.let { parts.add("\"cost\":$it") }
        p.stage?.let { parts.add("\"stage\":$it") }
        p.daysLeft?.let { parts.add("\"daysLeft\":$it") }
        p.exercise?.let { parts.add("\"exercise\":" + TelemetryJson.quoted(it.wire)) }
        return parts.joinToString(",", prefix = "{", postfix = "}")
    }

    companion object {
        /** The batch size at which [track] flushes on its own. */
        const val BATCH_SIZE = 20

        private const val MESSAGE_LIMIT = 300
        private const val SITE_LIMIT = 60
        private const val STACK_LIMIT = 2000
    }
}

/**
 * Minimal RFC 8259 string escaping. Non-ASCII passes through as UTF-8, exactly
 * as `JSON.stringify` does — which is what makes the byte-level "no child's
 * name in the payload" assertion an honest test rather than an artefact of
 * `\u` escaping.
 */
internal object TelemetryJson {
    fun quoted(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append("\"").toString()
    }
}
