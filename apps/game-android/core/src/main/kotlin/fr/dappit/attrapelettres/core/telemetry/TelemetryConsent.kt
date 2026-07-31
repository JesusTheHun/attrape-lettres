package fr.dappit.attrapelettres.core.telemetry

import fr.dappit.attrapelettres.core.platform.KVStore

/**
 * Analytics consent, given by the PARENT. France set the GDPR Art. 8 digital
 * consent age at 15, so a six-year-old tapping « Oui » is not consent — which
 * is why the toggle only ever appears on parent-facing screens (Onboarding,
 * and the parental-gated settings so withdrawal is as easy as giving).
 *
 * Opt-IN and it starts unticked. Pre-ticked consent has been invalid since
 * CJEU Planet49 (C-673/17), so the absence of a stored value must read as "no",
 * and it does: [has] compares against `"1"`, and an absent key is not `"1"`.
 * Nothing in this module sends an event before that flips.
 *
 * Device-scoped, never synced: consent belongs to the adult holding this phone,
 * not to the household. It is therefore NOT in the roster blob and does not
 * appear in `sync/Wire.kt`.
 *
 * Three states, and the distinction is tested: unset (never asked), `"0"`
 * (refused), `"1"` (given). **Unanswered is not consent, and it is not refusal
 * either** — [answered] is what an onboarding screen asks to decide whether to
 * put the question.
 *
 * NB: this type reads and writes the key; it deliberately has no public setter.
 * Withdrawal must also **drain the pending queue**, which only [Telemetry] can
 * do, so the public write path is `Telemetry.setConsent`. `write` is
 * `internal`, so a screen in `:ui` — a different module — cannot reach past it
 * and make a withdrawal non-retroactive.
 */
class TelemetryConsent(private val kv: KVStore) {

    /** `hasConsent()`. */
    val has: Boolean
        get() = kv.string(KEY) == "1"

    /** `consentAnswered()` — has the parent been asked at all yet? (unset ≠ refused) */
    val answered: Boolean
        get() = kv.string(KEY) != null

    /** Internal on purpose. See the note above: go through `Telemetry.setConsent`. */
    internal fun write(on: Boolean) {
        kv.set(KEY, if (on) "1" else "0")
    }

    companion object {
        /**
         * Byte-identical to the web app's `CONSENT_KEY`. It is not shared with
         * anything (consent never syncs), but a family that migrates from the
         * PWA to this app on the same device keeps its answer, and one name for
         * one concept across three codebases is worth more than the freedom to
         * rename it.
         */
        const val KEY = "attrape-lettres:consent:v1"
    }
}
