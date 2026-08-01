package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.rememberTileMotion
import fr.dappit.attrapelettres.ui.interaction.tileMotion
import fr.dappit.attrapelettres.ui.interaction.touchDown

// The big « Ecouter » button, once — the twin of iOS's `ListenPill.swift` (D55).
//
// Every exercise puts one under its mascot, and all eight TSX files author it
// with the same class list:
//
//   rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E]
//   shadow [touch-action:none]
//
// The Swift port grew FOUR copies of that before anybody noticed, which is
// exactly the divergence the root CLAUDE.md warns about: eight identical lines
// of JSX ported four times, so a fix lands in one of them and nobody sees the
// other three. It is one composable here from the start, and an engine that
// paints its own white capsule is a bug, not a variation.
//
// It PRESSES — the deviation iOS reported and kept. The web's does not:
// `Tile.tsx` is the only file that calls `el.animate`, so on the web this pill
// is visually inert and the only acknowledgement of a tap is the voice that
// follows it. On a phone that reads as a dead button — the clip may still be
// warming, and a child who gets nothing back taps again, which cuts the line
// they just asked for. It runs the same 130 ms squish a tile does, through the
// same `TileMotion.press()`, and never a shake: asking to hear something has no
// verdict (invariant 3).
//
// INVARIANT 1 lives in the `touchDown` below. The web fires this button on
// `onPointerDown`, not `onClick`, and so do we: the press and the call to
// [onListen] both happen inside the pointer dispatch, before anything commits.
// The `locked` guard that stops a replay cutting a success line belongs to the
// engine, not here.

/**
 * Tailwind `text-lg` sets a line-height of 28 px alongside its 18 px font size,
 * and both places that wear the class here — this pill and « ← Menu » — depend
 * on it: 28 + 8 + 8 = 44 dp of button, which is how the web's own arithmetic
 * clears the 44 pt tap floor without a single explicit height.
 *
 * `Typography.LineHeight` carries the `leading-*` utilities; this is the ratio
 * baked into the SIZE utility, so it lives beside the two call sites.
 */
const val TEXT_LG_LEADING: Float = 28f / 18f

/** The pill's authored metrics — the Tailwind classes, once. */
object ListenPillMetrics {

    /** `px-5`. */
    val paddingX: Dp = 20.dp

    /** `py-2`. */
    val paddingY: Dp = 8.dp

    /** [TEXT_LG_LEADING] — `text-lg`'s own 28 px line box. */
    const val textRatio: Float = TEXT_LG_LEADING

    /** Tailwind's plain `shadow` utility, both layers. */
    val shadow = Shadows.tailwind

    /**
     * The resulting height: `py-2` twice plus the 28 px line box.
     *
     * Recorded rather than enforced with a `heightIn` — the web's own arithmetic
     * already clears 44 dp, and pinning it in a test is how we find out if a
     * typography change ever stops it (invariant 6).
     */
    val height: Dp = 44.dp
}

/**
 * The « Ecouter » button.
 *
 * @param text what the button SHOWS — `Copy.Exercise.LISTEN`, or
 *   `Copy.Exercise.listenWord(word)` where the engine prints the prompt on the
 *   button itself.
 * @param contentDescription what a screen reader announces — the per-engine
 *   wording from `Copy.Exercise` (« Répéter le mot », « Réécouter le son », …).
 * @param onListen runs at touch-DOWN, right after the press starts.
 */
@Composable
fun ListenPill(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onListen: () -> Unit,
) {
    val motion = rememberTileMotion()
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier
            // One accessible element carrying the engine's wording, plus a real
            // click ACTION so TalkBack's double-tap activates it: `touchDown`
            // deliberately adds no semantics of its own, and an element with no
            // click action is one an assistive gesture has to fall through to.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick {
                    if (enabled) onListen()
                    true
                }
            }
            .tileMotion(motion)
            .cssShadow(ListenPillMetrics.shadow, CircleShape)
            .background(Color.White.copy(alpha = Palette.White.o70), CircleShape)
            .touchDown(enabled = enabled) {
                // Press FIRST, then speak — the child sees the squish before the
                // clip has decided whether it is ready.
                motion.press()
                onListen()
            }
            .padding(
                horizontal = ListenPillMetrics.paddingX,
                vertical = ListenPillMetrics.paddingY,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.bold,
                color = Palette.ink.color,
                ratio = ListenPillMetrics.textRatio,
                fontScale = fontScale,
            ),
        )
    }
}
