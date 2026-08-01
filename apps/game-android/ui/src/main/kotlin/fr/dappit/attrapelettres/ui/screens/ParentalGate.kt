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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown

// ===========================================================================
// `src/components/ParentalGate.tsx` — the adult door.
//
// Kids Category guideline 1.3 (and Google Play's Families policy, which says
// the same thing in different words): an app for children may not put a
// purchase, an external link or any other "distraction" in front of a child
// unless it sits behind one of these. This screen is the ONLY thing standing
// between a six-year-old and the price of the unlock.
//
// A two-digit multiplication is the standard because it is the cheapest thing a
// six-year-old genuinely cannot do and an adult does without thinking. The
// operands are RE-ROLLED ON EVERY OPEN, so a child who watches once learns
// nothing. Deliberately NOT kid-styled: the tone change is half the signal that
// this screen is not for them.
//
// ── THIS GATE PERSISTS NOTHING, and that is the TSX. ─────────────────────────
// `App.tsx` mounts `ParentalGate` nowhere; the only caller is `Paywall.tsx`'s
// `step === "gate"` branch, and the TSX holds the challenge in `useState(roll)`
// — fresh per mount, with no storage read or write anywhere in the file. The
// once-per-device gate that IS persisted is Onboarding, under
// `LicenseStore.ONBOARDED_KEY`. Adding a "gate passed" flag here would be a
// behaviour change AND a security regression: the whole point of the re-roll is
// that passing once buys nothing.
//
// ── INVARIANT 11 ────────────────────────────────────────────────────────────
// This screen holds no entitlement state at all. It reads no licence, no store
// and no clock, so it cannot fail to read one; a wrong answer costs one retry
// and « Annuler » always returns the caller's own screen. `ParentalGateTest`
// scans this file's source for those reads, because the way a gate starts
// failing closed is by growing an opinion about who may play.
//
// ── INVARIANT 3's spirit ────────────────────────────────────────────────────
// A wrong sum is not an error state. The field clears, the border warms, and
// the adult tries again — nothing is locked, nothing is lost, retries are
// unlimited.
// ===========================================================================

// --- The challenge (pure) ----------------------------------------------------

/**
 * Two single digits and the product an adult has to supply.
 *
 * ```ts
 * function roll(): [number, number] {
 *   const d = () => 3 + Math.floor(Math.random() * 7);   // 3..9
 *   return [d(), d()];
 * }
 * ```
 *
 * Operands 3…9, product 9…81 — never a trivial x1 or x2.
 */
data class GateChallenge(val a: Int, val b: Int) {

    /** `Number(value) === answer`. */
    val answer: Int get() = a * b

    /** « Combien font 7 × 4 ? » — U+00D7, not the letter x. */
    val question: String get() = Copy.ParentalGate.question(a, b)

    companion object {
        /**
         * The lowest and highest operand the roll can produce, named so the test
         * asserts the TypeScript's authored range rather than whatever the code
         * happens to do.
         */
        const val LOWEST_OPERAND = 3
        const val OPERAND_SPREAD = 7

        /**
         * Randomness is INJECTED (`core.support.RandomSource`, never a global —
         * ARCHITECTURE §5). The two draws happen in source order, so a seeded
         * source reproduces the TS's `[d(), d()]` left-to-right evaluation.
         */
        fun roll(random: RandomSource): GateChallenge =
            GateChallenge(
                a = LOWEST_OPERAND + random.next(OPERAND_SPREAD),
                b = LOWEST_OPERAND + random.next(OPERAND_SPREAD),
            )

        /** A fresh, non-reproducible roll — one per gate mount. */
        fun roll(): GateChallenge = roll(SystemRandomSource())
    }
}

/**
 * `e.target.value.replace(/\D/g, "").slice(0, 3)`.
 *
 * Filtered per UTF-16 CODE UNIT, which is what a `\D` without the `u` flag
 * does: everything outside U+0030…U+0039 is deleted. Two consequences worth
 * stating, because a `Char.isDigit()` filter would get both wrong:
 *
 *  - non-ASCII digits go (Arabic-Indic ٤٢, full-width ４２, superscript ²).
 *    `Char.isDigit()` KEEPS those and would let through a shape the web field
 *    never accepted, which `Int(value)` would then fail to parse;
 *  - a combining mark attached to a digit goes while the DIGIT STAYS.
 *
 * `.slice(0, 3)` counts code units too, and after the filter every survivor is
 * exactly one unit wide, so `take(3)` is the same cut.
 */
fun sanitizeGateInput(raw: String): String =
    raw.filter { it in '0'..'9' }.take(3)

// --- The state machine (plain, host-tested) ----------------------------------

/**
 * The gate's whole behaviour, with no composition in it: the accept/reject
 * rule, the sanitising, and the clear-the-field-on-a-wrong-answer.
 *
 * The two fields are Compose snapshot state so the renderer below is a thin
 * observer, and a host test reads them straight (A11: `mutableStateOf` works on
 * the unit-test JVM).
 */
@Stable
class ParentalGateModel(val challenge: GateChallenge) {

    /** The digits typed so far, already sanitised. */
    var value: String by mutableStateOf("")
        private set

    /**
     * Set by a failed [submit], cleared by the next keystroke — the TSX clears
     * `wrong` in `onChange`, so the error line disappears the moment the adult
     * starts over.
     */
    var wrong: Boolean by mutableStateOf(false)
        private set

    /** `disabled={value.length === 0}` on « Continuer ». */
    val canSubmit: Boolean get() = value.isNotEmpty()

    /** `onChange` — sanitise, and clear the error. */
    fun type(raw: String) {
        value = sanitizeGateInput(raw)
        wrong = false
    }

    /**
     * `submit` — `true` means the adult passed and the caller should call
     * `onPass()`. A failure clears the field and raises the error line.
     *
     * `Number(value) === answer`: an empty field parses to `0` in JS and to null
     * here, and both fail because the product is at least 9. Leading zeros parse
     * the same in both ("028" -> 28).
     */
    fun submit(): Boolean {
        if (value.toIntOrNull() == challenge.answer) return true
        wrong = true
        value = ""
        return false
    }
}

// --- Metrics (the TSX's Tailwind classes and inline styles, verbatim) --------

object ParentalGateMetrics {
    /** `max-w-sm` on the card, `p-6` inside it, `gap-4` between its rows. */
    val CARD_MAX_WIDTH: Dp = 384.dp
    val CARD_PADDING: Dp = 24.dp
    val CARD_GAP: Dp = 16.dp

    /** `rounded-3xl` on the card, `rounded-2xl` on the field, `p-6` on the scrim. */
    val CARD_RADIUS: Dp = 24.dp
    val FIELD_RADIUS: Dp = 16.dp
    val SCRIM_PADDING: Dp = 24.dp

    /** `shadow-2xl` = `0 25px 50px -12px rgb(0 0 0 / 0.25)`. */
    val CARD_SHADOW = CssShadow(y = 25.dp, blur = 50.dp, opacity = 0.25f, spread = (-12).dp)

    /** `px-4 py-3` on the field and on both buttons; `2px solid` on the border. */
    val FIELD_PADDING_X: Dp = 16.dp
    val FIELD_PADDING_Y: Dp = 12.dp
    val BUTTON_PADDING_X: Dp = 16.dp
    val BUTTON_PADDING_Y: Dp = 12.dp
    val FIELD_BORDER: Dp = 2.dp
    val BUTTON_GAP: Dp = 12.dp

    /** `disabled:opacity-40`. */
    const val DISABLED_OPACITY = 0.4f

    /**
     * The adult controls' tap-target floor.
     *
     * **[DEVIATION] It is 48 dp, not the 92 dp of `Copy.Tile.MINIMUM_SIDE`.**
     * That floor is the CHILD's: a six-year-old aiming at a tile in a grid.
     * These three screens are written for the adult in the room and the web
     * authors them at `py-3`/`py-4`, i.e. 44–56 dp tall; inflating them to 92 dp
     * would change the layout of a screen whose whole job is to look unlike the
     * game. 48 dp is the platform's own minimum touch target and is applied to
     * every control here, so invariant 6's floor is met at the height the
     * audience needs rather than skipped.
     */
    val MINIMUM_TAP: Dp = 48.dp
}

// --- The screen --------------------------------------------------------------

/**
 * The adult door, rendered.
 *
 * **[DEVIATION] It is a screen, not an overlay.** The TSX is `fixed inset-0
 * z-50` over the whole window; `RootView` has no overlay slot (the PWA replaces
 * the whole screen and so does this port), so the scrim covers the card the
 * shell hands this composable rather than the window behind it. The behaviour
 * is identical — nothing underneath is reachable either way, because nothing
 * underneath is composed.
 *
 * @param reason one line telling the adult what they are unlocking. The Paywall
 *   passes `Copy.ParentalGate.PURCHASE_REASON`. It carries NO price: see
 *   `PaywallTest`'s Kids-Category assertion.
 * @param challenge injected only by tests and previews. In the app it is null
 *   and a fresh roll happens on mount — `remember` with no key, so a
 *   recomposition (a keystroke, the error line appearing) cannot re-roll the sum
 *   under the adult's hands.
 */
@Composable
fun ParentalGateView(
    reason: String,
    onPass: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    challenge: GateChallenge? = null,
) {
    val model = remember { ParentalGateModel(challenge ?: GateChallenge.roll()) }
    val focus = remember { FocusRequester() }
    val fontScale = LocalDensity.current.fontScale

    // `autoFocus` on the input. Requested once, at mount.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    val attempt: () -> Unit = {
        if (model.submit()) onPass()
        // A failure needs no branch: the model has already cleared the field and
        // raised the line, and the field keeps focus. Nothing is locked.
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Shell.minimumScreenHeight)
            .background(Palette.gateScrim)
            .padding(ParentalGateMetrics.SCRIM_PADDING)
            // `role="dialog" aria-modal="true" aria-label="Espace parents"`.
            .semantics { contentDescription = Copy.ParentalGate.TITLE },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ParentalGateMetrics.CARD_MAX_WIDTH)
                .fillMaxWidth()
                .cssShadow(
                    ParentalGateMetrics.CARD_SHADOW,
                    RoundedCornerShape(ParentalGateMetrics.CARD_RADIUS),
                )
                .background(
                    Palette.gateCard.color,
                    RoundedCornerShape(ParentalGateMetrics.CARD_RADIUS),
                )
                .padding(ParentalGateMetrics.CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(ParentalGateMetrics.CARD_GAP),
        ) {
            BasicText(
                text = Copy.ParentalGate.TITLE,
                style = Typography.style(
                    size = Typography.Size.lg,
                    weight = Typography.Weight.bold,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )

            BasicText(
                text = reason,
                style = Typography.style(
                    // `<p className="text-sm">` — no weight class, so 400.
                    size = Typography.Size.sm,
                    weight = FontWeight.Normal,
                    color = Palette.inkGate.color,
                    ratio = Typography.LineHeight.snug,
                    fontScale = fontScale,
                ),
            )

            BasicText(
                text = model.challenge.question,
                style = Typography.style(
                    size = Typography.Size.sm,
                    weight = Typography.Weight.semibold,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )

            BasicTextField(
                value = model.value,
                onValueChange = model::type,
                singleLine = true,
                textStyle = Typography.style(
                    size = Typography.Size.xxl,
                    weight = Typography.Weight.bold,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ).copy(textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Palette.ink.color),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { attempt() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ParentalGateMetrics.MINIMUM_TAP)
                    .background(
                        Color.White,
                        RoundedCornerShape(ParentalGateMetrics.FIELD_RADIUS),
                    )
                    .border(
                        width = ParentalGateMetrics.FIELD_BORDER,
                        color = if (model.wrong) {
                            Palette.gateFieldWrong.color
                        } else {
                            Palette.gateField.color
                        },
                        shape = RoundedCornerShape(ParentalGateMetrics.FIELD_RADIUS),
                    )
                    .padding(
                        horizontal = ParentalGateMetrics.FIELD_PADDING_X,
                        vertical = ParentalGateMetrics.FIELD_PADDING_Y,
                    )
                    .focusRequester(focus)
                    .semantics { contentDescription = model.challenge.question },
            )

            if (model.wrong) {
                // `role="alert"` — an assertive live region, so TalkBack speaks
                // the retry without the adult having to hunt for it.
                BasicText(
                    text = Copy.ParentalGate.WRONG,
                    style = Typography.style(
                        size = Typography.Size.sm,
                        weight = FontWeight.Normal,
                        color = Palette.gateError.color,
                        fontScale = fontScale,
                    ),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = Copy.ParentalGate.WRONG
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ParentalGateMetrics.BUTTON_GAP)) {
                GateButton(
                    label = Copy.ParentalGate.CANCEL,
                    fill = Palette.adultSecondary.color,
                    ink = Palette.ink.color,
                    weight = Typography.Weight.semibold,
                    onAct = onCancel,
                    modifier = Modifier.weight(1f),
                )
                GateButton(
                    label = Copy.ParentalGate.CONFIRM,
                    fill = Palette.green.color,
                    ink = Color.White,
                    weight = Typography.Weight.bold,
                    enabled = model.canSubmit,
                    onAct = attempt,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One of the gate's two pills.
 *
 * Both act on the LIFT (`touchDown`'s `onUp`, with an empty down handler), the
 * same call `GameFrame` makes for « ← Menu » (A12): the TSX uses `onClick`
 * here, these are adult navigation rather than the gameplay feedback path, and
 * an adult who lands on « Continuer » and slides off must not have submitted.
 * `Modifier.clickable` is banned module-wide and a source scan enforces it.
 */
@Composable
private fun GateButton(
    label: String,
    fill: Color,
    ink: Color,
    weight: FontWeight,
    onAct: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = ParentalGateMetrics.MINIMUM_TAP)
            .drawBehind {
                // `disabled:opacity-40` — the FILL fades, not the text, which is
                // what a CSS opacity on a solid capsule looks like.
                drawRoundRect(
                    color = fill.copy(
                        alpha = if (enabled) 1f else ParentalGateMetrics.DISABLED_OPACITY,
                    ),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .touchDown(enabled = enabled, onUp = { inside -> if (inside) onAct() }) { }
            .padding(
                horizontal = ParentalGateMetrics.BUTTON_PADDING_X,
                vertical = ParentalGateMetrics.BUTTON_PADDING_Y,
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
                weight = weight,
                color = ink.copy(
                    alpha = if (enabled) 1f else ParentalGateMetrics.DISABLED_OPACITY,
                ),
                fontScale = fontScale,
            ),
        )
    }
}
