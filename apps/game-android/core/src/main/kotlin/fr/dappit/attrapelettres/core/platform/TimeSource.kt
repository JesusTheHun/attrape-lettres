package fr.dappit.attrapelettres.core.platform

/* -------------------------------------------------------------------------- */
/* Injectable time. The trial clock, the LWW stamps in sync/Merge and the       */
/* reward curve all need "now" — any of them reading the system clock directly  */
/* is untestable, and for licensing that is not a style opinion: a 14-day       */
/* offline grace whose clock cannot be advanced in a test is a 14-day offline   */
/* grace nobody has ever verified.                                              */
/* -------------------------------------------------------------------------- */

/**
 * Epoch MILLISECONDS as `Long`, everywhere in `:core`. Not `Instant`, not
 * `Duration`: the arithmetic stays byte-identical to the TypeScript's
 * `Date.now()` / `TRIAL_MS` / `DAY_MS`, the persisted blob stays
 * interchangeable with the PWA's and the iOS app's, and an integer cannot
 * accumulate the drift a floating-point seconds value would.
 */
interface TimeSource {
    val nowMillis: Long
}

class SystemTimeSource : TimeSource {
    override val nowMillis: Long
        get() = System.currentTimeMillis()
}

/** Tests only. Settable, so a spec can walk a fortnight in four lines. */
class MutableTimeSource(start: Long) : TimeSource {
    override var nowMillis: Long = start

    fun advance(days: Double) {
        nowMillis += (days * 86_400_000).toLong()
    }

    fun advance(millis: Long) {
        nowMillis += millis
    }
}
