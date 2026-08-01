package fr.dappit.attrapelettres.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * An `HttpURLConnection` that never opens a socket — the seam both transports
 * expose (`open: (String) -> HttpURLConnection`) exists exactly so these suites
 * can assert on the request that WOULD have gone out: the method, the headers,
 * the body bytes. The Swift port does the same through its internal `request`
 * builders; here the base class itself records what the adapter set on it.
 *
 * The base `URLConnection` machinery is real — `setRequestProperty` stores into
 * the same header map production uses, and `connected` stays false because the
 * overrides never flip it — so `requestProperties` in an assertion reads the
 * genuine article, not a parallel bookkeeping list that could drift.
 */
internal class RecordingConnection(
    url: String,
    private val status: Int = 200,
    private val responseBody: ByteArray = ByteArray(0),
    private val responseHeaders: Map<String, String> = emptyMap(),
    /** When set, [getResponseCode] throws it — the flat-network case. */
    private val failResponse: IOException? = null,
) : HttpURLConnection(URI(url).toURL()) {

    /** Every byte the adapter wrote as the request body. */
    val requestBody = ByteArrayOutputStream()

    var disconnected = false
        private set

    override fun connect() {
        // Never flips `connected`: the base class would then refuse
        // `getRequestProperties()`, which the assertions need.
    }

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getOutputStream(): OutputStream = requestBody

    override fun getResponseCode(): Int {
        failResponse?.let { throw it }
        return status
    }

    override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody)

    override fun getErrorStream(): InputStream? =
        if (status >= 400) ByteArrayInputStream(responseBody) else null

    override fun getHeaderField(name: String?): String? =
        responseHeaders.entries
            .firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }
            ?.value
}
