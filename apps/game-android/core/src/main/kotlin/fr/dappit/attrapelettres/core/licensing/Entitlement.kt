package fr.dappit.attrapelettres.core.licensing

/* -------------------------------------------------------------------------- */
/* What the family is allowed to play right now. PURE — no storage, no store,   */
/* no clock except the `now` passed in.                                         */
/*                                                                             */
/* The model Apple wrote a rule for (App Review 3.1.1): a non-subscription app  */
/* may run a free time-based trial before a full unlock, via a price-0          */
/* non-consumable named "14-day Trial", provided the duration, what stops       */
/* working, and the eventual charge are all disclosed BEFORE the trial starts.  */
/* Onboarding is that disclosure; this file is the clock.                       */
/* -------------------------------------------------------------------------- */
//
// Port of `apps/game-web/src/licensing/entitlement.ts`, cross-checked against
// `apps/game-ios/Sources/ALCore/Licensing/Entitlement.swift`. Owner of
// **invariant 11 — money never fails closed**. An unreachable store, a
// timed-out receipt check or a flat network may never lock a child out.
//
// Time is epoch MILLISECONDS as `Long` throughout, so the arithmetic is
// byte-identical to the TypeScript's and the persisted blob stays
// interchangeable with the PWA's and the iOS app's. There is no clock read
// anywhere in this package — `now` is always a parameter, and EntitlementModel
// takes an injected `TimeSource`. That is not a style opinion: a 14-day offline
// grace whose clock cannot be advanced in a test is a 14-day offline grace
// nobody has ever verified. `LicensingSourceScanTest` enforces it.
//
// Deliberately NOT `@Serializable`, unlike the Swift port's `Codable`
// conformance: this file stays free of kotlinx.serialization, and LicenseStore
// owns the JSON on both directions. See the note on `LicenseState` below.

const val TRIAL_DAYS = 7
const val DAY_MS = 24L * 60L * 60L * 1000L
const val TRIAL_MS = TRIAL_DAYS * DAY_MS

/** Price in euros, TTC. Displayed copy lives in the screens; this is the truth. */
const val UNLOCK_PRICE_EUR = 11.99

/**
 * The two product identifiers, shared with the iOS app on purpose (A4): the
 * Android `applicationId` cannot carry the hyphen the iOS bundle id has, so
 * rather than invent a third name the store products keep one spelling on both
 * platforms.
 *
 * `PRODUCT_TRIAL` exists for iOS only and is declared here so the two ports
 * stay diffable. Google Play has no price-0 in-app product, so nothing on
 * Android ever asks for it — see `LicenseState.trialStartedAt`.
 */
const val PRODUCT_TRIAL = "fr.dappit.attrapelettres.trial7"
const val PRODUCT_UNLOCK = "fr.dappit.attrapelettres.unlock"

/**
 * How long a "paid" verdict survives without the store confirming it again.
 *
 * This is a fail-OPEN window and it is deliberate. A child on a plane, or in a
 * kitchen with no wifi, must never be told the game they own is locked. We
 * would rather give away a fortnight of play to someone who genuinely refunded
 * than show one paying six-year-old a paywall because the store timed out.
 */
const val OFFLINE_GRACE_MS = 14L * DAY_MS

/**
 * The whole persisted licence: four fields about one household's purchase.
 *
 * Every default is the fail-open one, because [BLANK_LICENSE] — which is what a
 * missing, empty, truncated or wrong-typed blob degrades to — must mean *full
 * trial*, i.e. the child plays.
 *
 * Not `@Serializable`: LicenseStore encodes and decodes it by hand off a
 * `JsonElement` tree. Two reasons, both the same ones LooseDecoding.kt gives
 * for the roster. A generated decoder is all-or-nothing, so one wrong-typed
 * field would fail the whole blob — harmless here (blank plays) but it hides
 * the field that survived. And a generated *encoder* under kotlinx's default
 * `encodeDefaults = false` would drop every field equal to its default, so a
 * blank licence would serialise as `{}` and stop being interchangeable with the
 * PWA's `JSON.stringify`, which writes all four keys and writes `null`
 * explicitly.
 */
data class LicenseState(
    /** The store says the unlock is owned by this Apple Account / Google account. */
    val paid: Boolean = false,
    /**
     * When the store last CONFIRMED [paid]. Drives the offline grace window.
     *
     * `null` is the TypeScript `null`, and it means "never confirmed" — which is
     * treated as *paid forever*, not as *not paid*: there is nothing to age the
     * flag against, so we believe it. See [entitlementOf].
     */
    val verifiedAt: Long? = null,
    /**
     * When the trial began.
     *
     * iOS: the price-0 in-app product's signed StoreKit `purchaseDate` — minted
     * by Apple, survives a reinstall, and follows the Apple Account across
     * devices. Never the app's own timestamp, which a reinstall would reset.
     *
     * **Android is not symmetric, and the difference lives here.** Google Play
     * has no price-0 in-app product, so there is no signed receipt to date the
     * trial from and no server of ours to ask (this app has no accounts, by
     * design). The stamp is therefore purely LOCAL: written once by
     * `EntitlementModel.beginTrial`, persisted by LicenseStore into the app's
     * preferences, and carried across a reinstall or a new phone only by
     * Android Auto Backup (A5, which is why `allowBackup` is on and
     * `shared_prefs` is included).
     *
     * That is strictly weaker than the iOS mechanism, in two named ways:
     *  - clearing app data, or declining backup, resets it, so the fortnight is
     *    re-rollable by anyone who knows that;
     *  - it is a plain integer this app wrote, so it is not evidence of
     *    anything to anybody.
     *
     * We accept it. The alternatives are an account system this app
     * deliberately does not have (invariant 10: nothing identifying leaves the
     * device) or a server-side trial ledger keyed to something that identifies
     * a household, which is the same thing wearing a hat. The population that
     * would farm fortnightly trials — parents of six-year-olds, re-installing
     * a €9.99 reading game every two weeks — is not one worth building an
     * identity system for, and the cost of guessing wrong is that somebody
     * plays free. Invariant 11 says that is the direction to be wrong in.
     */
    val trialStartedAt: Long? = null,
    /**
     * Highest `now` ever observed. Winding the device clock back is the one
     * trial-extension trick that costs nothing to try, and three lines to
     * defeat. It is also the ONLY deliberately fail-CLOSED rule in this module.
     */
    val clockHighWater: Long = 0L,
)

/**
 * `BLANK_LICENSE`. NB: blank means *full trial*, i.e. the child plays. Every
 * decode failure in LicenseStore degrades to this on purpose.
 */
val BLANK_LICENSE = LicenseState()

/**
 * What the app should do. A sealed hierarchy so [canPlay] and every `when` over
 * it is exhaustiveness-checked by the compiler.
 */
sealed interface Entitlement {
    /**
     * Nothing decided yet — the store has not answered. Never render a paywall
     * on this.
     *
     * NB: [entitlementOf] NEVER returns this. It exists only as a literal and
     * only [canPlay] reads it. **Do not delete it as dead code.** It is the
     * documented "the store has not answered" value; deleting it would let a
     * future refactor make "no answer" mean "locked", which is exactly the
     * failure invariant 11 exists to prevent.
     */
    data object Unknown : Entitlement

    data class Trial(val daysLeft: Int, val endsAt: Long) : Entitlement

    data object Expired : Entitlement

    data object Paid : Entitlement
}

/** Monotonic clock: a device clock that moved backwards is ignored. */
fun effectiveNow(state: LicenseState, now: Long): Long = maxOf(now, state.clockHighWater)

/**
 * Fold the licence into what the app should do.
 *
 * Order matters: paid beats everything, and an expired trial only bites once we
 * are sure the unlock is NOT owned. A merely unverified paid flag stays paid
 * until the grace window runs out.
 */
fun entitlementOf(state: LicenseState, now: Long): Entitlement {
    val t = effectiveNow(state, now)

    if (state.paid) {
        val verifiedAt = state.verifiedAt
        val stale = verifiedAt != null && t - verifiedAt > OFFLINE_GRACE_MS
        if (!stale) return Entitlement.Paid
        // Grace exhausted: fall THROUGH and let the trial clock decide, rather
        // than hard-locking. Worst case the family sees the paywall and taps
        // "Restaurer" — and a family whose trial never started still gets a
        // trial out of this branch, not a lockout.
    }

    // Trial not started yet (pre-onboarding): treat as a full trial so nothing
    // is ever gated before the parent has even seen the terms.
    //
    // NB: `endsAt` is recomputed from `t` on every call in this branch, so it
    // SLIDES FORWARD — it is not a stable date. Nothing renders it. Ported
    // as-is from the TypeScript rather than "fixed", because the two apps
    // agreeing is worth more than a field with no reader being tidy.
    val startedAt = state.trialStartedAt ?: return Entitlement.Trial(TRIAL_DAYS, t + TRIAL_MS)

    val endsAt = startedAt + TRIAL_MS
    // `Math.ceil`, including for negatives: `kotlin.math.ceil` rounds toward
    // +∞, which is what JS does and what makes 13.5 days elapsed read as "1 day
    // left" rather than "0". Epoch ms fit exactly in a Double (< 2^53), so the
    // conversion is lossless.
    val daysLeft = kotlin.math.ceil((endsAt - t).toDouble() / DAY_MS).toInt()
    return if (daysLeft > 0) Entitlement.Trial(daysLeft, endsAt) else Entitlement.Expired
}

/**
 * Can a new exercise round be started? The only gate the child ever feels.
 *
 * NB: this is a BLACKLIST of one, and it must stay one. Never invert it into a
 * whitelist (`e is Paid || e is Trial`) — that is exactly the refactor that
 * breaks invariant 11, because a future fifth case would default to locked.
 * [Entitlement.Unknown] plays.
 */
fun canPlay(e: Entitlement): Boolean = e != Entitlement.Expired

/** Advance the high-water mark. Call whenever the licence is touched. */
fun withClock(state: LicenseState, now: Long): LicenseState =
    if (now > state.clockHighWater) state.copy(clockHighWater = now) else state

/**
 * French, parent-facing, for the trial countdown chip.
 *
 * Copy byte for byte with the web and iOS: em dash U+2014 with ordinary ASCII
 * spaces around it, ASCII apostrophe in « d'essai ».
 */
fun trialNotice(e: Entitlement): String? {
    if (e !is Entitlement.Trial) return null
    if (e.daysLeft == 1) return "Dernier jour d'essai"
    return "Essai gratuit — ${e.daysLeft} jours restants"
}
