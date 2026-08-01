package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// The page scrolls — because on the web, the page always scrolled.
//
// Two screens need it, for what look like different reasons and are the same
// one: on the web the document scrolls, so neither the soft keyboard nor a long
// list can put content permanently out of reach.
//
//   - the first-run name form (iOS D42) — an IME inset that would otherwise
//     push the greeting off the top of a stage pinned to 620 dp;
//   - the species picker (iOS D46) — five companion cards in a stage pinned to
//     `minHeight`, of which the fifth sat below the screen with no way to reach
//     it. The child could not choose the last animal at all.
//
// There is no TSX counterpart to this file, and that is the point. The web has
// no notch and no keyboard inset: when a soft keyboard opens the visual viewport
// shrinks and the browser scrolls the focused field into view, and the page top
// is never clipped. `adjustResize` plus a scrollable page restores exactly that.
//
// WHY IT IS A GATE AND NOT SOMETHING APPLIED AT THE SHELL — invariant 1.
//
// A scrollable ancestor competes for the down event. `ui/interaction/TouchDown.kt`
// takes the down on `PointerEventPass.Initial`, ahead of every ancestor, so a
// scroller cannot DELAY the feedback; but it then watches `PointerEventPass.Final`
// for a consumed change and treats it as the web's `pointercancel`, so a flick
// that happens to lift over a tile is correctly not a tap. Both halves are
// deliberate, and neither is a licence to put a scroller over an exercise.
// ARCHITECTURE section 3 row 1 forbids a scrollable ancestor over an exercise
// tile grid outright: on those screens nothing must ever consume, because a
// six-year-old's wobbly press is not a scroll and must still count.
//
// So: the enabled branch is for the two ADULT-ish screens above, and the
// disabled branch has to be the exact identity — `PageScrollTest` asserts that,
// because "simplify the gate away and wrap unconditionally" is the one edit that
// silently puts a scroller over the roster grid's cards.
//
// Never apply this at the app root: the exercise engines live under it.

/**
 * Make this subtree scroll vertically when [enabled], and leave it completely
 * untouched when not.
 *
 * The [state] is explicit so the whole gate is a plain function a host test can
 * call — [pageScroll] with a `remember`ed state is the composable convenience
 * below.
 *
 * NOT for a subtree containing exercise tiles. See the file header.
 */
fun Modifier.pageScroll(enabled: Boolean, state: ScrollState): Modifier =
    if (enabled) this.verticalScroll(state) else this

/** [pageScroll] with a remembered [ScrollState]. */
@Composable
fun Modifier.pageScroll(enabled: Boolean = true): Modifier {
    // Remembered unconditionally: a state that appears and disappears with the
    // gate would reset the scroll position on every toggle.
    val state = rememberScrollState()
    return pageScroll(enabled, state)
}
