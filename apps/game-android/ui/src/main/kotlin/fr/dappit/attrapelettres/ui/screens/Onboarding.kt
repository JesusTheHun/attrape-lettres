package fr.dappit.attrapelettres.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.licensing.EntitlementModel
import fr.dappit.attrapelettres.core.licensing.TRIAL_DAYS
import fr.dappit.attrapelettres.core.licensing.UNLOCK_PRICE_EUR
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.telemetry.Telemetry
import fr.dappit.attrapelettres.core.telemetry.TelemetryEvent
import fr.dappit.attrapelettres.core.telemetry.TelemetryProps
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.liftedPill
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.rememberTileMotion
import fr.dappit.attrapelettres.ui.interaction.tileMotion
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ===========================================================================
// `src/components/Onboarding.tsx` — first launch, and the first of the three
// screens written for the grown-up in the room.
//
// It does three jobs at once, on purpose. App Review 3.1.1 (and Play's
// equivalent subscription/trial disclosure rules) require that a time-based
// trial disclose its duration, what stops working, and the eventual charge
// BEFORE the trial starts. GDPR Art. 8 — France sets the age at 15 — means the
// analytics answer has to come from the parent. A second modal for consent
// would be one interruption too many, so both happen here.
//
// THREE THINGS THIS SCREEN MUST KEEP, worst-to-lose first:
//
//  1. The consent control starts OFF. [OnboardingDefaults.INITIAL_CONSENT] is
//     a named constant, not a literal in the `remember`, precisely so a test can
//     hold it still — seeding it from `telemetry.hasConsent` would be a
//     one-word edit and would make the box pre-ticked, invalid since CJEU
//     *Planet49*. An asymmetric button pair reads as a dark pattern to a store
//     reviewer and to the CNIL alike, so « Commencer » carries no hidden opt-in
//     and declining costs the parent nothing.
//  2. The terms render ABOVE the button, and `beginTrial()` is only ever called
//     from [startOnboarding].
//  3. [startOnboarding]'s order is load-bearing: `setConsent` -> `track` ->
//     `beginTrial`. Consent first, so the very first event already respects the
//     answer (a refusal means `track` drops it on the floor).
//
// ONCE PER DEVICE. This screen — not `ParentalGateView` — is the gate the shell
// runs once (`shellGate`'s `onboarded` branch). The "once" is persisted by
// `EntitlementModel.beginTrial()` under `LicenseStore.ONBOARDED_KEY` =
// `attrape-lettres:onboarded:v1`, a name shared byte for byte with the shipped
// PWA. Invariant 11 is satisfied by the READ being total: `loadOnboarded()` is
// `kv.string(key) == "1"`, so an unreadable or absent flag answers `false` and
// the family sees this screen again — one tap and they are playing. Nothing
// here can resolve to "locked".
//
// ANDROID IS NOT iOS ON TWO POINTS, both in the copy and both deliberate:
//
//  - THE SCOPE. `Copy.Onboarding.SCOPE` is « sur vos appareils », never « pour
//    toute la famille ». Google Play Family Library explicitly does not share
//    in-app purchases, ever; a Play restore is per Google account. iOS's
//    `Copy.swift` may make the family promise because Family Sharing keeps it.
//    `CopyTest.no string in the ui module promises family sharing` greps every
//    non-comment line of :ui for the word.
//  - THE TRIAL CLOCK. There is no price-0 in-app product on Play, so the clock
//    is a LOCAL stamp carried by Auto Backup (A5), not a signed receipt. No
//    copy on this screen implies a receipt or a restore flow.
//
// Invariant 10: the only telemetry this screen emits is `trial_started` with a
// single numeric property. It never touches the roster, so a child's first name
// has no path from here to a transport.
// ===========================================================================

// --- `<strong>` runs, without re-typing the copy -----------------------------

/** One span of a paragraph, and whether the TSX wrapped it in `<strong>`. */
data class EmphasisRun(val text: String, val bold: Boolean)

/**
 * Splits an authored sentence around the substrings the TSX emphasises.
 *
 * `Copy.Onboarding.trialParagraph` is deliberately ONE string (its `<strong>`
 * runs are emphasis, not separate copy), so the bold spans are recovered by
 * SEARCHING rather than by re-typing the sentence in two halves. Re-typing is
 * how the displayed copy and the tested copy drift apart.
 *
 * Returns runs in document order whose `text` concatenates back to [full]
 * exactly — asserted in `OnboardingTest`.
 */
fun emphasisRuns(full: String, bold: List<String>): List<EmphasisRun> {
    val out = mutableListOf<EmphasisRun>()
    var rest = full
    while (rest.isNotEmpty()) {
        var hitAt = -1
        var hitNeedle = ""
        for (needle in bold) {
            if (needle.isEmpty()) continue // an empty needle would loop forever
            val at = rest.indexOf(needle)
            if (at >= 0 && (hitAt < 0 || at < hitAt)) {
                hitAt = at
                hitNeedle = needle
            }
        }
        if (hitAt < 0) {
            out.add(EmphasisRun(rest, bold = false))
            break
        }
        if (hitAt > 0) out.add(EmphasisRun(rest.substring(0, hitAt), bold = false))
        out.add(EmphasisRun(hitNeedle, bold = true))
        rest = rest.substring(hitAt + hitNeedle.length)
    }
    return out
}

// --- What the screen says, given what the store answered ---------------------

/**
 * The disclosure copy, resolved. Pure, so a host test can assert the wording and
 * the branch without a renderer (A11).
 *
 * @param storeAvailable `EntitlementModel.storeAvailable`.
 * @param priceLabel the store's own localised label, or null while it has not
 *   answered. This build ships `StubPurchaseStore`, so in practice it is null
 *   and the fallback below is what a parent reads.
 * @param days `TRIAL_DAYS`. A parameter only so a test can prove the copy is
 *   built from the constant rather than from a hard-coded "14".
 * @param scope `Copy.Onboarding.SCOPE`. Never « pour toute la famille » — see
 *   the file header.
 */
class OnboardingPlan(
    storeAvailable: Boolean,
    priceLabel: String?,
    days: Int = TRIAL_DAYS,
    priceEur: Double = UNLOCK_PRICE_EUR,
    scope: String = Copy.Onboarding.SCOPE,
) {
    /** `priceLabel ?? "9,99 €"` — the store's label wins once it has answered. */
    val price: String = priceLabel ?: Copy.fallbackPriceLabel(priceEur)

    /**
     * The paragraphs in render order, each already split into its emphasis runs.
     * Two when a store exists (the trial terms, then what pauses), one when it
     * does not.
     */
    val paragraphs: List<List<EmphasisRun>>

    /** « Commencer les 14 jours » / « Commencer ». */
    val buttonTitle: String

    init {
        if (storeAvailable) {
            val terms = Copy.Onboarding.trialParagraph(days, price, scope)
            paragraphs = listOf(
                emphasisRuns(terms, listOf("$days jours", price)),
                emphasisRuns(Copy.Onboarding.pauseParagraph(days), emptyList()),
            )
            buttonTitle = Copy.Onboarding.startTrial(days)
        } else {
            paragraphs = listOf(emphasisRuns(Copy.Onboarding.NO_STORE_PARAGRAPH, emptyList()))
            buttonTitle = Copy.Onboarding.START
        }
    }
}

// --- `start()` ---------------------------------------------------------------

/**
 * The TSX's `start()`, extracted whole:
 *
 * ```tsx
 * const start = () => {
 *   setConsent(analytics);
 *   track("trial_started", { daysLeft: TRIAL_DAYS });
 *   beginTrial();
 * };
 * ```
 *
 * The order IS the behaviour and it is tested. `setConsent` first means a
 * refusal is already recorded when `track` runs, so `trial_started` is dropped
 * rather than sent-then-regretted; `beginTrial` last means the screen only
 * dismisses after the answer is stored.
 *
 * Synchronous, all three steps — `beginTrial()` is non-`suspend` because on
 * Android the local stamp IS the whole mechanism (there is no price-0 in-app
 * product to date the trial from). `EntitlementModel.confirmTrialStart()`, the
 * asynchronous half that exists so the two ports keep one shape, is a permanent
 * no-op here; the screen still launches it, and its cancellation costs nothing.
 */
fun startOnboarding(
    analytics: Boolean,
    telemetry: Telemetry,
    entitlement: EntitlementModel,
    days: Int = TRIAL_DAYS,
) {
    telemetry.setConsent(analytics)
    telemetry.track(TelemetryEvent.TRIAL_STARTED, TelemetryProps(daysLeft = days))
    entitlement.beginTrial()
}

// --- Metrics (the TSX's Tailwind classes and inline styles, verbatim) --------

object OnboardingMetrics {
    /** `clamp(44px,13vw,64px)` — the 👋. */
    val WAVE = FluidSpec(min = 44f, vw = 13f, max = 64f)

    /** `clamp(26px,7vw,36px)` — the headline. */
    val TITLE = FluidSpec(min = 26f, vw = 7f, max = 36f)

    /** `p-6` on the stage, `gap-5` between blocks, `gap-3` between paragraphs. */
    val STAGE_PADDING: Dp = 24.dp
    val BLOCK_GAP: Dp = 20.dp
    val PARAGRAPH_GAP: Dp = 12.dp

    /** `px-8 py-4` on « Commencer ». */
    val BUTTON_PADDING_X: Dp = 32.dp
    val BUTTON_PADDING_Y: Dp = 16.dp

    /** `0 8px 0 #43A047` then `0 14px 24px rgba(0,0,0,0.2)`. */
    val BUTTON_LIP_DROP: Dp = 8.dp
    val BUTTON_SOFT_SHADOW = CssShadow(y = 14.dp, blur = 24.dp, opacity = 0.2f)

    /** `rounded-2xl p-4` on the consent card, over `rgba(255,255,255,0.7)`. */
    val CONSENT_RADIUS: Dp = 16.dp
    val CONSENT_PADDING: Dp = 16.dp

    /** `h-5 w-5` on the checkbox, `mt-1` above it, `gap-3` beside it. */
    val CHECKBOX_SIDE: Dp = 20.dp
    val CHECKBOX_TOP_INSET: Dp = 4.dp
    val CHECKBOX_GAP: Dp = 12.dp
    val CHECKBOX_RADIUS: Dp = 4.dp
    val CHECKBOX_BORDER: Dp = 2.dp
}

/** Defaults a test pins without composing. */
object OnboardingDefaults {

    /**
     * **The consent control's initial state, and it is `false`.**
     *
     * A named constant rather than a literal inside the `remember`: seeding it
     * from `telemetry.hasConsent` would delete this line, which is what
     * `OnboardingTest` is watching for. Do not make it a function of anything.
     * Mirrors `Copy.Onboarding.CONSENT_INITIALLY_CHECKED`, and the test asserts
     * the two agree so neither can drift alone.
     */
    const val INITIAL_CONSENT = false
}

// --- The screen --------------------------------------------------------------

/**
 * First launch. Terms, then the button, then the (unticked) consent card.
 *
 * @param entitlement mutated here — this is one of the only two screens that
 *   may. `beginTrial()` flips `onboarded`, which is what makes the shell's gate
 *   fall through.
 * @param telemetry consent is written here before anything is tracked.
 * @param reduceMotion accepted for the shell's uniform screen signature and
 *   deliberately unused: this screen animates nothing that invariant 6 gates.
 *   The button's press scale is the web's `active:scale-95`, which the web
 *   leaves ungated (D29 — reduce-motion covers the mascot and the confetti,
 *   never press/shake).
 * @param onDone MUST be called after `beginTrial()`. `EntitlementModel` is not
 *   observable by Compose (A1: `:core` has no Compose on its classpath), so the
 *   shell re-reads the licence only when it is told to.
 */
@Composable
fun OnboardingView(
    entitlement: EntitlementModel,
    telemetry: Telemetry,
    reduceMotion: ReduceMotionSource,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    val scope = rememberCoroutineScope()

    var analytics by remember { mutableStateOf(OnboardingDefaults.INITIAL_CONSENT) }

    // Read once, at mount. `storeAvailable` is a `val` on the model and cannot
    // change; `priceLabel` can arrive later, and a plan built a beat early shows
    // the computed fallback — which is the same money, in the same currency.
    val plan = remember(entitlement.priceLabel) {
        OnboardingPlan(
            storeAvailable = entitlement.storeAvailable,
            priceLabel = entitlement.priceLabel,
        )
    }

    val start: () -> Unit = {
        startOnboarding(analytics, telemetry, entitlement)
        // The no-op half, launched rather than awaited (see [startOnboarding]).
        // If the gate falls through first this coroutine is cancelled with the
        // composition, and nothing is lost: the trial clock is already stamped.
        scope.launch {
            try {
                entitlement.confirmTrialStart()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Invariant 11: a store that throws where its contract forbids
                // it must not stop a family that has just accepted the terms.
            }
        }
        onDone()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Shell.minimumScreenHeight)
            .drawBehind { drawRect(Palette.stageAdult.brush(size)) }
            .padding(OnboardingMetrics.STAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.BLOCK_GAP),
    ) {
        // `aria-hidden` on the web: decoration, and a screen reader saying
        // "waving hand" before the terms helps nobody.
        BasicText(
            text = Copy.Onboarding.WAVE,
            style = Typography.style(
                size = OnboardingMetrics.WAVE.resolve(viewport),
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )

        BasicText(
            text = Copy.Onboarding.TITLE,
            style = Typography.style(
                size = OnboardingMetrics.TITLE.resolve(viewport),
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )

        Column(verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.PARAGRAPH_GAP)) {
            for (runs in plan.paragraphs) {
                BasicText(
                    text = buildAnnotatedString {
                        for (run in runs) {
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
                        // The web sets no font-weight on this prose, so it is
                        // 400. `Typography.style` defaults to 900, which would
                        // shout the terms at a parent.
                        size = Typography.Size.base,
                        weight = FontWeight.Normal,
                        color = Palette.inkProse.color,
                        ratio = Typography.LineHeight.snug,
                        fontScale = fontScale,
                    ),
                )
            }
        }

        AdultPrimaryButton(
            label = plan.buttonTitle,
            paddingX = OnboardingMetrics.BUTTON_PADDING_X,
            paddingY = OnboardingMetrics.BUTTON_PADDING_Y,
            onAct = start,
            modifier = Modifier.fillMaxWidth(),
        )

        ConsentCard(
            checked = analytics,
            title = Copy.Onboarding.CONSENT_TITLE,
            body = Copy.Onboarding.CONSENT_BODY,
            onToggle = { analytics = it },
        )
    }
}

// --- Shared adult chrome (used by Onboarding AND Paywall) --------------------

/**
 * The lifted green pill: `rounded-full … text-white` over
 * `0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)`.
 *
 * Acts on the LIFT (`touchDown`'s `onUp`), because the TSX uses `onClick` and
 * because a parent who lands on a button that spends money and slides off has
 * not agreed to anything. The press scale fires at DOWN, which is where all
 * feedback belongs (invariant 1) even on a screen that is not the game.
 * `Modifier.clickable` is banned module-wide (A12).
 */
@Composable
internal fun AdultPrimaryButton(
    label: String,
    paddingX: Dp,
    paddingY: Dp,
    onAct: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledOpacity: Float = 1f,
) {
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberTileMotion()
    val alpha = if (enabled) 1f else disabledOpacity

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = ParentalGateMetrics.MINIMUM_TAP)
            .tileMotion(motion)
            .liftedPill(
                fill = { SolidColor(Palette.green.color.copy(alpha = alpha)) },
                lip = Palette.greenLip.color.copy(alpha = alpha),
                drop = OnboardingMetrics.BUTTON_LIP_DROP,
                soft = OnboardingMetrics.BUTTON_SOFT_SHADOW,
            )
            .touchDown(
                enabled = enabled,
                onUp = { inside -> if (inside) onAct() },
            ) { motion.press() }
            .padding(horizontal = paddingX, vertical = paddingY)
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
                size = Typography.Size.xl,
                weight = Typography.Weight.extrabold,
                color = Color.White.copy(alpha = alpha),
                fontScale = fontScale,
            ),
        )
    }
}

/**
 * The quiet underlined adult link — `text-base font-semibold underline` on
 * `#8A6A4A`. No background, no border; the underline is the whole affordance,
 * which is deliberate: these are the controls a child should not find inviting.
 */
@Composable
internal fun AdultLink(
    label: String,
    onAct: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = ParentalGateMetrics.MINIMUM_TAP)
            .touchDown(onUp = { inside -> if (inside) onAct() }) { }
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
                color = Palette.inkQuiet.color,
                fontScale = fontScale,
            ).copy(textDecoration = TextDecoration.Underline),
        )
    }
}

/**
 * `<label><input type="checkbox" …/><span>…</span></label>`.
 *
 * The WHOLE label is the hit target on the web, so the whole row carries the
 * touch handler and one merged set of semantics with `Role.Checkbox` and a
 * `ToggleableState` — TalkBack then announces "case à cocher, non coché" rather
 * than "bouton", which is the checkbox's own semantics reproduced.
 *
 * [title] is null on the Paywall, whose card is one shorter sentence.
 *
 * The tick is drawn as two strokes rather than a `Path`: Compose's `Path` is
 * `android.graphics.Path` under the hood and is unusable in a host test (A11),
 * and two `drawLine`s are the whole glyph anyway.
 */
@Composable
internal fun ConsentCard(
    checked: Boolean,
    body: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    val label = if (title == null) body else "$title. $body"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ParentalGateMetrics.MINIMUM_TAP)
            .background(
                Color.White.copy(alpha = Palette.White.o70),
                RoundedCornerShape(OnboardingMetrics.CONSENT_RADIUS),
            )
            .touchDown(onUp = { inside -> if (inside) onToggle(!checked) }) { }
            .padding(OnboardingMetrics.CONSENT_PADDING)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                onClick { onToggle(!checked); true }
            },
        horizontalArrangement = Arrangement.spacedBy(OnboardingMetrics.CHECKBOX_GAP),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = OnboardingMetrics.CHECKBOX_TOP_INSET)
                .size(OnboardingMetrics.CHECKBOX_SIDE)
                .background(
                    if (checked) Palette.green.color else Color.White,
                    RoundedCornerShape(OnboardingMetrics.CHECKBOX_RADIUS),
                )
                .border(
                    width = OnboardingMetrics.CHECKBOX_BORDER,
                    color = if (checked) Palette.green.color else Palette.inkFaint.color,
                    shape = RoundedCornerShape(OnboardingMetrics.CHECKBOX_RADIUS),
                )
                .drawBehind {
                    if (!checked) return@drawBehind
                    val w = size.width
                    val h = size.height
                    val stroke = w * 0.16f
                    drawLine(
                        color = Color.White,
                        start = Offset(w * 0.24f, h * 0.52f),
                        end = Offset(w * 0.44f, h * 0.72f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(w * 0.44f, h * 0.72f),
                        end = Offset(w * 0.76f, h * 0.30f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                },
        )

        BasicText(
            text = buildAnnotatedString {
                if (title != null) {
                    withStyle(
                        SpanStyle(
                            fontWeight = Typography.Weight.bold,
                            color = Palette.ink.color,
                        ),
                    ) {
                        append(title)
                    }
                    append("\n")
                }
                append(body)
            },
            style = Typography.style(
                size = Typography.Size.sm,
                weight = FontWeight.Normal,
                color = Palette.inkProse.color,
                ratio = Typography.LineHeight.snug,
                fontScale = fontScale,
            ),
        )
    }
}
