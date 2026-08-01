package fr.dappit.attrapelettres.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.licensing.EntitlementModel
import fr.dappit.attrapelettres.core.licensing.UNLOCK_PRICE_EUR
import fr.dappit.attrapelettres.core.telemetry.Telemetry
import fr.dappit.attrapelettres.core.telemetry.TelemetryEvent
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ===========================================================================
// `src/components/Paywall.tsx` — end of the trial.
//
// THREE LAYERS, AND THE ORDER IS THE WHOLE DESIGN. A child who taps an exercise
// after the fortnight sees a calm, kid-legible « demande à un grand » — never a
// price, never a buy button. Kids Category 1.3 (and Play's Families policy)
// forbid putting a purchase in front of a child, and invariant 3 forbids making
// a child feel they failed at something. The parental gate is the door. Only
// behind it does money appear, and `PaywallTest` proves that as a property of
// [paywallLayer] rather than as a claim about the layout.
//
// Nothing is confiscated: the hub, the mascot, the shop and every star stay
// exactly where they were. Only starting a NEW round is paused.
//
// ── INVARIANT 11 — money never fails closed. ────────────────────────────────
//
// This screen SELLS; it never GATES. Read that as a structural claim, because
// it is one: there is NO entitlement read anywhere in this file. No
// `entitlementOf`, no `canPlay`, no `model.entitlement`, no clock, no store
// snapshot. The router decides who sees this screen (`openOutcome` in
// `screens/Router.kt`), and `:core` already guarantees that every failure —
// unreachable store, timed-out receipt check, flat network, billing exception,
// pending approval — resolves to *playable*:
//
//   • a `PurchaseStore` adapter maps every throw and timeout to `UNREACHABLE` /
//     null / false, never to a locked verdict;
//   • `applySnapshot` changes NOTHING but the clock when `reachable` is false;
//   • `entitlementOf` keeps a paid family paid through a 14-day offline grace,
//     and when even that runs out it FALLS THROUGH to the trial clock rather
//     than hard-locking;
//   • `canPlay` is a blacklist of one, so `Unknown` plays.
//
// The one way this screen could break that chain is by growing a branch of its
// own — "if the store did not answer, hide the way back", "if the purchase
// failed, stay here". It has neither. Every layer offers an exit, and a failed
// purchase leaves the child's side untouched. `PaywallTest` scans this file's
// source for the reads, because a review note does not survive a refactor.
//
// ── ANDROID IS NOT iOS ON MONEY ─────────────────────────────────────────────
// « Restaurer un achat » restores for the signed-in GOOGLE ACCOUNT and nothing
// else: Play Family Library never shares in-app purchases. That is why
// `Copy.Paywall.Note.NOTHING_TO_RESTORE` says « sur ce compte » and why no
// string on this screen promises a family. This build also has no store behind
// it at all (`:app` wires `StubPurchaseStore`), so a real adapter drops in
// under `:core`'s `PurchaseStore` seam without touching a line here.
//
// Invariant 10: the four events this screen emits carry NO properties at all.
// The roster is never read here, so a child's first name has no path from this
// file to a transport.
// ===========================================================================

// --- The three layers --------------------------------------------------------

/**
 * ```ts
 * type Step = "child" | "gate" | "parent";
 * ```
 *
 * The order is the design: a child may only ever reach [CHILD], and the money
 * lives two deliberate taps away.
 */
enum class PaywallStep {
    /** « Les jeux font une pause » — no price, no purchase, no failure. */
    CHILD,

    /** `<ParentalGate reason=… />`. */
    GATE,

    /** Behind the gate: the price, the two store controls, the consent toggle. */
    PARENT,
}

/**
 * Everything one layer puts on screen, as data.
 *
 * This exists so the Kids-Category claim is TESTABLE: a host test cannot
 * compose, so "no price is reachable without passing the gate" has to be a
 * property of a value. The composable renders FROM this, field by field, so the
 * test is not asserting about a parallel description of the screen — it is
 * asserting about the screen.
 *
 * [texts] is every user-visible string of the layer, in render order.
 */
sealed interface PaywallLayer {

    val texts: List<String>

    /** Layer 1. The only one a child can reach. */
    data class Child(
        val moon: String = Copy.Paywall.Child.MOON,
        val title: String = Copy.Paywall.Child.TITLE,
        val body: String = Copy.Paywall.Child.BODY,
        val seeCompanion: String = Copy.Paywall.Child.SEE_COMPANION,
        val iAmAnAdult: String = Copy.Paywall.Child.I_AM_AN_ADULT,
    ) : PaywallLayer {
        override val texts: List<String>
            get() = listOf(moon, title, body, seeCompanion, iAmAnAdult)
    }

    /** Layer 2. The door — see `ParentalGateView`. */
    data class Gate(
        val reason: String = Copy.ParentalGate.PURCHASE_REASON,
    ) : PaywallLayer {
        override val texts: List<String>
            get() = listOf(
                reason,
                Copy.ParentalGate.TITLE,
                Copy.ParentalGate.CANCEL,
                Copy.ParentalGate.CONFIRM,
            )
    }

    /** Layer 3. Behind the gate, and the only layer that names a price. */
    data class Parent(
        val title: String,
        val body: String,
        /** « Débloquer — 9,99 € », or « … » while the store is answering. */
        val primary: String,
        /** « Restaurer un achat » — null when this build has no store. */
        val restore: String?,
        /** The no-store explanation — null when there IS a store. */
        val noStore: String?,
        /** The one status line. Never an error: it says what did NOT happen. */
        val note: String?,
        val consentBody: String,
        val backToGame: String,
    ) : PaywallLayer {
        override val texts: List<String>
            get() = listOfNotNull(
                title, body, primary, restore, noStore, note, consentBody, backToGame,
            )
    }
}

/**
 * The copy of one layer, resolved. Pure.
 *
 * @param price already resolved by [paywallPrice] — the store's label if it has
 *   answered, the computed French fallback otherwise.
 */
fun paywallLayer(
    step: PaywallStep,
    price: String,
    storeAvailable: Boolean,
    busy: Boolean = false,
    note: String? = null,
): PaywallLayer = when (step) {
    PaywallStep.CHILD -> PaywallLayer.Child()
    PaywallStep.GATE -> PaywallLayer.Gate()
    PaywallStep.PARENT -> PaywallLayer.Parent(
        title = Copy.Paywall.Parent.TITLE,
        body = Copy.Paywall.Parent.body(price),
        primary = if (busy) Copy.Paywall.Parent.BUSY else Copy.Paywall.Parent.buy(price),
        restore = if (storeAvailable) Copy.Paywall.Parent.RESTORE else null,
        noStore = if (storeAvailable) null else Copy.Paywall.Parent.NO_STORE,
        note = note,
        consentBody = Copy.Paywall.Parent.CONSENT_BODY,
        backToGame = Copy.Paywall.Parent.BACK_TO_GAME,
    )
}

/**
 * `priceLabel ?? `${PRICE} €`` — the store's localised label wins; the fallback
 * is `UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",")` plus « € ».
 *
 * No literal price lives in this file: the number comes from `:core` and the
 * formatting from `Copy.fallbackPriceLabel`.
 */
fun paywallPrice(priceLabel: String?, fallbackEur: Double = UNLOCK_PRICE_EUR): String =
    priceLabel ?: Copy.fallbackPriceLabel(fallbackEur)

// --- The state machine (plain, host-tested) ----------------------------------

/**
 * `useState` x 4 plus the two async handlers, lifted out of the composable.
 *
 * The collaborators are passed PER CALL rather than held, which is what keeps
 * this object constructible — and therefore assertable — from a test with
 * nothing but a `Telemetry` and an `EntitlementModel` in hand.
 */
@Stable
class PaywallModel(step: PaywallStep = PaywallStep.CHILD, analytics: Boolean = false) {

    /** `const [step, setStep] = useState<Step>("child")`. */
    var step: PaywallStep by mutableStateOf(step)
        private set

    /** `const [busy, setBusy] = useState(false)`. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** `const [note, setNote] = useState<string | null>(null)`. */
    var note: String? by mutableStateOf(null)
        private set

    /**
     * `const [analytics, setAnalytics] = useState(hasConsent)`.
     *
     * NB the contrast with [OnboardingDefaults.INITIAL_CONSENT], which is a hard
     * `false`: that screen ASKS for the first time (pre-ticked consent has been
     * invalid since CJEU *Planet49*), this one SHOWS the answer already given so
     * the parent can withdraw it (GDPR Art. 7(3) — withdrawal must be as easy as
     * consent). Both are deliberate and they must not be unified.
     */
    var analytics: Boolean by mutableStateOf(analytics)
        private set

    /**
     * `useState(hasConsent)` is a LAZY initialiser: it runs once, at mount. The
     * composable seeds on first composition and this flag makes it happen once —
     * a recomposition must not clobber a toggle the parent has already moved.
     */
    private var seeded = false

    /** The mount read of `hasConsent`. Idempotent. */
    fun seedAnalytics(consent: Boolean) {
        if (seeded) return
        seeded = true
        analytics = consent
    }

    // -- Navigation between the layers ---------------------------------------

    /**
     * « Je suis un adulte » — the only door out of the child layer that is not
     * « Voir ma mascotte ». Note what it does NOT do: no telemetry. A child
     * tapping around does not count as a paywall impression.
     */
    fun askForAnAdult() {
        step = PaywallStep.GATE
    }

    /**
     * ```tsx
     * onPass={() => { setStep("parent"); track("paywall_shown"); }}
     * ```
     * In that order. `paywall_shown` means *an adult saw the price*, which is
     * why it fires here and nowhere else.
     */
    fun gatePassed(telemetry: Telemetry) {
        step = PaywallStep.PARENT
        telemetry.track(TelemetryEvent.PAYWALL_SHOWN)
    }

    /** `onCancel={() => setStep("child")}` — back to the calm screen. */
    fun gateCancelled() {
        step = PaywallStep.CHILD
    }

    // -- The two store buttons -----------------------------------------------

    /**
     * ```tsx
     * const buy = async () => {
     *   setBusy(true); setNote(null);
     *   const ok = await purchase();
     *   setBusy(false);
     *   track(ok ? "purchase_completed" : "purchase_failed");
     *   if (!ok) setNote("L'achat n'a pas abouti. Rien n'a été débité.");
     * };
     * ```
     *
     * Every non-success collapses to the same `false` — cancel, billing error,
     * an unverified purchase, and a PENDING parental approval alike. All of them
     * therefore land on a note that blames nobody and confiscates nothing, with
     * the game exactly as playable as it was a second earlier (invariant 11).
     */
    suspend fun buy(entitlement: EntitlementModel, telemetry: Telemetry) {
        busy = true
        note = null
        val ok = runStore { entitlement.purchase() }
        busy = false
        telemetry.track(
            if (ok) TelemetryEvent.PURCHASE_COMPLETED else TelemetryEvent.PURCHASE_FAILED,
        )
        if (!ok) note = Copy.Paywall.Note.PURCHASE_FAILED
    }

    /**
     * ```tsx
     * const redo = async () => {
     *   setBusy(true); setNote(null);
     *   const ok = await restore();
     *   setBusy(false);
     *   track("purchase_restored");
     *   setNote(ok ? "Achat restauré." : "Aucun achat trouvé sur ce compte.");
     * };
     * ```
     *
     * Two asymmetries with [buy], both in the TypeScript and both correct:
     * `purchase_restored` fires whatever the outcome, and a note is set either
     * way. (`EntitlementModel.restore()` also refreshes unconditionally, because
     * a restore can materialise an entitlement even when the call itself reports
     * that nothing was restored — so « Aucun achat trouvé » can be shown by a
     * run that nonetheless just unlocked the app. Harmless: the shell re-reads
     * the licence and the paywall stops being reachable.)
     */
    suspend fun redo(entitlement: EntitlementModel, telemetry: Telemetry) {
        busy = true
        note = null
        val ok = runStore { entitlement.restore() }
        busy = false
        telemetry.track(TelemetryEvent.PURCHASE_RESTORED)
        note = if (ok) Copy.Paywall.Note.RESTORED else Copy.Paywall.Note.NOTHING_TO_RESTORE
    }

    /**
     * INVARIANT 11, at the one place this screen touches a store.
     *
     * `PurchaseStore`'s contract says an adapter never throws; a Play Billing
     * adapter that lets a `BillingClient` exception escape would break it, and
     * that exception would otherwise unwind through `busy = true` and leave the
     * parent staring at a permanently disabled « Débloquer ». A throw is
     * therefore the same outcome as a refusal: `false`, a note, and a screen
     * that still has a way back to the game.
     */
    private suspend fun runStore(call: suspend () -> Boolean): Boolean = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    // -- The consent checkbox ------------------------------------------------

    /**
     * ```tsx
     * onChange={(e) => { setAnalytics(e.target.checked); setConsent(e.target.checked); }}
     * ```
     * Local state first, storage second — and `setConsent(false)` drains the
     * queue, so withdrawal is retroactive for anything not yet sent.
     */
    fun setAnalytics(on: Boolean, telemetry: Telemetry) {
        analytics = on
        telemetry.setConsent(on)
    }
}

// --- Metrics (the TSX's Tailwind classes and inline styles, verbatim) --------

object PaywallMetrics {
    /** Both layers: `min-h-[100dvh] w-full … p-6`. */
    val STAGE_PADDING: Dp = 24.dp

    /** Child: `flex-col items-center justify-center gap-6 text-center`. */
    val CHILD_SPACING: Dp = 24.dp

    /** `clamp(56px,17vw,90px)` — the 🌙. */
    val MOON = FluidSpec(min = 56f, vw = 17f, max = 90f)

    /** `clamp(24px,7vw,34px)` — « Les jeux font une pause ». */
    val CHILD_TITLE = FluidSpec(min = 24f, vw = 7f, max = 34f)

    /** `max-w-xs` on the child's paragraph and on its green button. */
    val CHILD_MAX_WIDTH: Dp = 320.dp

    /** Parent: `w-full max-w-md flex-col gap-5`. */
    val PARENT_SPACING: Dp = 20.dp
    val PARENT_MAX_WIDTH: Dp = 448.dp

    /**
     * `clamp(24px,7vw,32px)` — « Débloquer Attrape-Lettres ». Two points shorter
     * at the top than the child headline; both are in the TSX.
     */
    val PARENT_TITLE = FluidSpec(min = 24f, vw = 7f, max = 32f)

    /** The green button, both layers: `px-8 py-4 text-xl font-extrabold`. */
    val PRIMARY_PADDING_X: Dp = 32.dp
    val PRIMARY_PADDING_Y: Dp = 16.dp

    /** « Restaurer un achat »: `px-6 py-3 font-semibold` on `#F0E6D8`, no lip. */
    val RESTORE_PADDING_X: Dp = 24.dp
    val RESTORE_PADDING_Y: Dp = 12.dp

    /** `disabled:opacity-50` on both store buttons while busy. */
    const val DISABLED_OPACITY = 0.5f
}

// --- The screen --------------------------------------------------------------

/**
 * The paywall, all three layers.
 *
 * @param entitlement the store seam. Read for `storeAvailable` / `priceLabel`
 *   and called for `purchase` / `restore` — never read for a VERDICT. See the
 *   invariant-11 block in the file header.
 * @param onBack « Voir ma mascotte » and « Retour au jeu ». Present on EVERY
 *   layer: a child must never meet a dead end.
 * @param onLicenseChanged MUST be called after a purchase or a restore, or the
 *   shell keeps rendering the previous licence (`EntitlementModel` is invisible
 *   to Compose — A1).
 * @param model injected only by tests and previews; the app lets the screen mint
 *   its own, exactly as `useState` does.
 */
@Composable
fun PaywallView(
    entitlement: EntitlementModel,
    telemetry: Telemetry,
    onBack: () -> Unit,
    onLicenseChanged: () -> Unit,
    modifier: Modifier = Modifier,
    model: PaywallModel? = null,
) {
    val state = remember { model ?: PaywallModel() }
    val scope = rememberCoroutineScope()
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale

    // `useState(hasConsent)`'s lazy initialiser, once.
    LaunchedEffect(Unit) { state.seedAnalytics(telemetry.hasConsent) }

    val price = paywallPrice(entitlement.priceLabel)
    val layer = paywallLayer(
        step = state.step,
        price = price,
        storeAvailable = entitlement.storeAvailable,
        busy = state.busy,
        note = state.note,
    )

    when (layer) {
        is PaywallLayer.Gate -> ParentalGateView(
            reason = layer.reason,
            onPass = { state.gatePassed(telemetry) },
            onCancel = state::gateCancelled,
            modifier = modifier,
        )

        is PaywallLayer.Child -> Column(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Shell.minimumScreenHeight)
                .drawBehind { drawRect(Palette.stageAdult.brush(size)) }
                .padding(PaywallMetrics.STAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(PaywallMetrics.CHILD_SPACING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = layer.moon,
                style = Typography.style(
                    size = PaywallMetrics.MOON.resolve(viewport),
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )
            BasicText(
                text = layer.title,
                style = Typography.style(
                    size = PaywallMetrics.CHILD_TITLE.resolve(viewport),
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ).copy(textAlign = TextAlign.Center),
            )
            BasicText(
                text = layer.body,
                style = Typography.style(
                    size = Typography.Size.lg,
                    weight = FontWeight.Normal,
                    color = Palette.inkProse.color,
                    ratio = Typography.LineHeight.snug,
                    fontScale = fontScale,
                ).copy(textAlign = TextAlign.Center),
                modifier = Modifier.widthIn(max = PaywallMetrics.CHILD_MAX_WIDTH),
            )

            // The way out, and it is always here.
            AdultPrimaryButton(
                label = layer.seeCompanion,
                paddingX = PaywallMetrics.PRIMARY_PADDING_X,
                paddingY = PaywallMetrics.PRIMARY_PADDING_Y,
                onAct = onBack,
                modifier = Modifier
                    .widthIn(max = PaywallMetrics.CHILD_MAX_WIDTH)
                    .fillMaxWidth(),
            )

            AdultLink(label = layer.iAmAnAdult, onAct = state::askForAnAdult)
        }

        is PaywallLayer.Parent -> Column(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Shell.minimumScreenHeight)
                .drawBehind { drawRect(Palette.stageAdult.brush(size)) }
                .padding(PaywallMetrics.STAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(PaywallMetrics.PARENT_SPACING),
        ) {
            BasicText(
                text = layer.title,
                style = Typography.style(
                    size = PaywallMetrics.PARENT_TITLE.resolve(viewport),
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )

            // `Un achat unique de <strong>{price}</strong>. …` — the emphasis is
            // recovered by search rather than by re-typing the sentence, the
            // same rule `OnboardingPlan` follows.
            BasicText(
                text = buildAnnotatedString {
                    for (run in emphasisRuns(layer.body, listOf(price))) {
                        if (run.bold) {
                            withStyle(SpanStyle(fontWeight = Typography.Weight.bold)) {
                                append(run.text)
                            }
                        } else {
                            append(run.text)
                        }
                    }
                },
                style = Typography.style(
                    size = Typography.Size.base,
                    weight = FontWeight.Normal,
                    color = Palette.inkProse.color,
                    ratio = Typography.LineHeight.snug,
                    fontScale = fontScale,
                ),
            )

            // `storeAvailable ? <buy/><restore/> : <noStore/>` — the two
            // controls exist together or not at all, which is why one null
            // check drives both.
            if (layer.restore != null) {
                AdultPrimaryButton(
                    label = layer.primary,
                    paddingX = PaywallMetrics.PRIMARY_PADDING_X,
                    paddingY = PaywallMetrics.PRIMARY_PADDING_Y,
                    enabled = !state.busy,
                    disabledOpacity = PaywallMetrics.DISABLED_OPACITY,
                    onAct = {
                        scope.launch {
                            state.buy(entitlement, telemetry)
                            onLicenseChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Play, like the App Store, needs a restore control to exist for
                // a non-consumable. On Android it restores per Google account.
                SecondaryPill(
                    label = layer.restore,
                    enabled = !state.busy,
                    onAct = {
                        scope.launch {
                            state.redo(entitlement, telemetry)
                            onLicenseChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (layer.noStore != null) {
                BasicText(
                    text = layer.noStore,
                    style = Typography.style(
                        size = Typography.Size.base,
                        weight = FontWeight.Normal,
                        color = Palette.inkProse.color,
                        fontScale = fontScale,
                    ),
                )
            }

            if (layer.note != null) {
                // `role="status"` — a polite live region, so TalkBack speaks the
                // outcome without the parent having to hunt for it.
                BasicText(
                    text = layer.note,
                    style = Typography.style(
                        size = Typography.Size.sm,
                        weight = FontWeight.Normal,
                        color = Palette.inkQuiet.color,
                        fontScale = fontScale,
                    ),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = layer.note
                    },
                )
            }

            ConsentCard(
                checked = state.analytics,
                body = layer.consentBody,
                onToggle = { state.setAnalytics(it, telemetry) },
            )

            AdultLink(label = layer.backToGame, onAct = onBack)
        }
    }
}

/**
 * « Restaurer un achat » — the deliberately quieter control: a flat `#F0E6D8`
 * capsule with no lip and no shadow, so the primary action stays the loud one.
 */
@Composable
private fun SecondaryPill(
    label: String,
    onAct: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    val alpha = if (enabled) 1f else PaywallMetrics.DISABLED_OPACITY

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = ParentalGateMetrics.MINIMUM_TAP)
            .drawBehind {
                drawRoundRect(
                    color = Palette.adultSecondary.color.copy(alpha = alpha),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .touchDown(enabled = enabled, onUp = { inside -> if (inside) onAct() }) { }
            .padding(
                horizontal = PaywallMetrics.RESTORE_PADDING_X,
                vertical = PaywallMetrics.RESTORE_PADDING_Y,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { onAct(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = Typography.style(
                size = Typography.Size.base,
                weight = Typography.Weight.semibold,
                color = Palette.ink.color.copy(alpha = alpha),
                fontScale = fontScale,
            ),
        )
    }
}
