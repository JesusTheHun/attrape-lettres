package fr.dappit.attrapelettres.ui.interaction

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

// The ONE touch-down primitive, app-wide. The Android port of the PWA's
// `onPointerDown` and the twin of iOS's `TouchDown.swift` (D5) — every
// interactive gameplay element (tiles, the Ecouter button, slot-remove buttons,
// child cards, the shop) routes through this and nothing else.
//
// INVARIANT 1 — feedback fires on pointer-DOWN, before Compose commits.
//
// The web does SFX plus the press animation synchronously inside the pick
// handler (`src/components/Tile.tsx`, `onPointerDown` -> `el.animate(press)` on
// line 59). So `onDown` here runs SYNCHRONOUSLY inside the pointer dispatch:
// `awaitFirstDown` resumes its continuation from inside
// `SuspendingPointerInputModifierNode`'s event dispatch, which happens while
// the input event is being delivered — before measure, before draw, and above
// all before any recomposition. Nothing on this path goes through
// `mutableStateOf`, a `LaunchedEffect`, a `snapshotFlow` or a dispatched
// coroutine.
//
// NEVER `Modifier.clickable`. It is the obvious thing to reach for and it is
// wrong on three counts: it fires on pointer-UP, it fires after gesture
// arbitration (so a scrollable ancestor can delay it by the touch-slop
// timeout), and it drags Material's ripple `Indication` behind it, which is one
// more commit between the child's finger and the sound. `TouchDownSourceScanTest`
// fails the build if the word reappears in this package.
//
// Why not the other candidates, for the same reason SwiftUI's `Button` and
// `.onTapGesture` lost on iOS:
//   - `Modifier.combinedClickable` / `toggleable` — same pointer-up semantics.
//   - `detectTapGestures(onPress = ...)` — `onPress` DOES run at down, but the
//     lambda is `suspend PressGestureScope.(Offset) -> Unit`, which invites
//     exactly the `awaitRelease()` / `delay()` shape invariant 1 forbids, and
//     it awaits on `Main` (see the pass discussion below). `onDown` here is a
//     plain `() -> Unit` on purpose: if you find yourself wanting to launch a
//     coroutine to play the sound, the design is wrong — `AudioEngine.pop()`
//     and `nudge()` are non-suspend precisely so they can be called from here
//     (A3), and the clip bank is a pre-warmed `SoundPool`, never a
//     `MediaPlayer` that would decode on the tap path.
//
// SCROLLING — Compose's twin of iOS's `delaysContentTouches` problem.
//
// A scrollable ancestor competes for the same down event. Two halves:
//
//   1. We must not be DELAYED by it. `awaitFirstDown` is taken on
//      `PointerEventPass.Initial`, which reaches this node BEFORE any ancestor
//      sees the event, and we consume nothing — so the feedback is already out
//      of the door when the scrollable gets its turn on `Main`, and the
//      scrollable still works. That is the same bargain as
//      `shouldRecognizeSimultaneouslyWith -> true` on iOS: begin instantly,
//      prevent nobody.
//
//   2. Having refused to block the scroll, we must then notice when the scroll
//      WINS, or a flick that happens to lift over a card would count as a tap.
//      That bug is real and was found on the device on iOS (D49): "if you don't
//      start to swipe immediately after touching the screen, it counts as a
//      tap". The web does not behave that way — once the page starts scrolling
//      the browser fires `pointercancel` and no `click` follows.
//
//      iOS restores that by asking the enclosing `UIScrollView` whether it
//      `isDragging || isDecelerating`. Compose has a better answer, because a
//      scrollable announces the takeover in the event stream itself: when
//      `scrollable` claims the drag it CONSUMES the position change. We watch
//      for that on `PointerEventPass.Final` — the pass whose whole purpose is
//      "did anybody upstream consume this" — and a consumed change is the
//      `pointercancel`. No ancestor walk, no view-tree reflection, and no
//      distance threshold (a threshold would also swallow a six-year-old's
//      wobbly press on a NON-scrolling button, which the web delivers as a
//      click).
//
// Note what invariant 1 already guarantees: ARCHITECTURE section 3 forbids a
// scrollable ancestor over an exercise tile grid at all, so on every exercise
// screen nothing is ever consumed and this rule changes no behaviour. It exists
// for the two surfaces that legitimately live inside a scroller — the shop tile
// and the species picker card — which are also the only two surfaces in the app
// that act on touch-UP.
//
// UP / CANCEL / SLIDE-OFF semantics, carried over from iOS unchanged:
//   - a completed press is down then up with the finger still inside the
//     element's bounds. That is the web's `click`.
//   - sliding OUT does not cancel anything while the finger is down; the lift
//     reports `inside = false`. (The web is the same: no `pointerleave`
//     handling anywhere in `Tile.tsx`.)
//   - a cancel — the scroll took it, the window lost focus, the gesture was
//     interrupted — reports `onUp(false)` once and swallows the lift that the
//     system still delivers afterwards. Exactly one `onUp` per `onDown`, ever.
//
// ACCESSIBILITY (invariant 6): this modifier adds no semantics of its own, so
// the `Modifier.semantics { contentDescription = ... }` the caller puts on the
// tile is untouched and TalkBack's activation still reaches the element. The
// caller owns the label; this file owns the timing.

/**
 * The four things that can happen to a tracked pointer, named so the decision
 * table below is a plain function a host test can drive.
 *
 * There is no Compose type in this file's decision layer on purpose: a JUnit
 * test on the JVM cannot dispatch a real pointer event, so the rule has to be
 * reachable without one.
 */
enum class PointerPhase {
    /** The finger landed. */
    DOWN,

    /** The finger moved while still down. */
    MOVE,

    /** The finger lifted. */
    UP,

    /** The system took the touch away. The web's `pointercancel`. */
    CANCEL,
}

/**
 * Containment of a pointer position in an element of [size], in the element's
 * own coordinate space. Pure, and the one piece of geometry a press needs.
 */
fun isInside(position: Offset, size: IntSize): Boolean =
    position.x >= 0f &&
        position.y >= 0f &&
        position.x < size.width.toFloat() &&
        position.y < size.height.toFloat()

/**
 * The press state machine, with no Compose and no Android in it.
 *
 * It exists so the touch-down contract — synchronous [onDown] at DOWN, exactly
 * one [onUp] per press, `inside` reported at the lift — is asserted by
 * `./gradlew :ui:testDebugUnitTest` on the host, where a pointer event cannot
 * be manufactured.
 *
 * You almost certainly want [touchDown] instead of this.
 */
class TouchDownCore(
    var onDown: () -> Unit,
    var onUp: (inside: Boolean) -> Unit,
) {
    /** True between a delivered DOWN and its terminating UP or CANCEL. */
    var isTracking: Boolean = false
        private set

    /**
     * The finger landed. [onDown] is invoked before this function returns —
     * invariant 1 lives on that line, and `TouchDownTest` asserts the ordering.
     */
    fun began() {
        if (isTracking) return
        isTracking = true
        onDown()
    }

    /** The finger lifted; [inside] is the pointer-up-on-the-element test. */
    fun ended(inside: Boolean) {
        if (!isTracking) return
        isTracking = false
        onUp(inside)
    }

    /** [ended] with the containment test done for you. */
    fun ended(position: Offset, size: IntSize) = ended(isInside(position, size))

    /**
     * The system took the touch (a scroll claimed the drag, the window lost the
     * gesture). The web analogue is `pointercancel`: no click follows.
     */
    fun cancelled() {
        if (!isTracking) return
        isTracking = false
        onUp(false)
    }

    /**
     * Every phase decision in one place, reachable without a live gesture.
     *
     * Named `handle` rather than iOS's `apply`, which in Kotlin would sit next
     * to the stdlib's `T.apply { }` and read as a scope function at the call
     * site. Only the name differs.
     *
     * [stolenByAncestor] is "a change in this event was consumed upstream",
     * i.e. a scrollable took the drag. It is checked on UP as well as on MOVE
     * as a belt to the braces: a lift delivered in the same event as the
     * takeover is never a tap, whatever order the passes arrived in.
     */
    fun handle(phase: PointerPhase, inside: Boolean, stolenByAncestor: Boolean) {
        when (phase) {
            PointerPhase.DOWN -> began()
            PointerPhase.MOVE -> if (stolenByAncestor) cancelled()
            PointerPhase.UP -> if (stolenByAncestor) cancelled() else ended(inside)
            PointerPhase.CANCEL -> cancelled()
        }
    }
}

/**
 * The app-wide touch-down primitive.
 *
 * [onDown] runs synchronously at the instant the finger lands — the
 * `pointerdown` of the PWA. Play feedback there and nowhere else: the SFX
 * (`AudioEngine.pop()` / `nudge()`), the press animation
 * ([TileMotion.press] / [TileMotion.shake]), the pick verdict and the star-strip
 * greying (invariant 8) all belong inside it.
 *
 * [onUp] reports whether the finger lifted inside the element's bounds. `true`
 * is the web's `click`; a lift outside, or a cancellation, is `false`. Almost
 * nothing in the game uses it — the shop tile and the species picker card are
 * the only two surfaces that act on touch-up.
 *
 * [enabled] is the web's `disabled` prop: the handler returns early, so a
 * disabled tile neither animates nor picks.
 *
 * The callbacks are read through `rememberUpdatedState`, so a handler that
 * closes over the current round is never stale — the pointer coroutine itself
 * is keyed only on [enabled] and therefore is not torn down and restarted on
 * every recomposition (restarting it mid-press would drop the press).
 */
@Composable
fun Modifier.touchDown(
    enabled: Boolean = true,
    onUp: (inside: Boolean) -> Unit = {},
    onDown: () -> Unit,
): Modifier {
    val currentDown by rememberUpdatedState(onDown)
    val currentUp by rememberUpdatedState(onUp)
    return this.touchDownRaw(
        enabled = enabled,
        onUp = { inside -> currentUp(inside) },
        onDown = { currentDown() },
    )
}

/**
 * [touchDown] without the composition: the callbacks are captured ONCE, for the
 * lifetime of the [enabled] key.
 *
 * This is the real implementation, and it is public for two reasons: a caller
 * whose callbacks are genuinely stable (a `remember`ed lambda, a method
 * reference) can skip the state wrapper, and a host test can build the modifier
 * chain and assert on it — which a `@Composable` factory does not allow.
 *
 * Prefer [touchDown] unless you can point at why the capture is safe.
 */
fun Modifier.touchDownRaw(
    enabled: Boolean = true,
    onUp: (inside: Boolean) -> Unit = {},
    onDown: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val core = TouchDownCore(onDown = onDown, onUp = onUp)
    // `awaitEachGesture` is `awaitPointerEventScope` plus "wait for every
    // pointer to be up before starting the next gesture", which is what lets
    // the loop below bail out the moment a scroll wins without re-arming on
    // the tail of the same touch.
    awaitEachGesture {
        // requireUnconsumed = false: we do not care whether somebody upstream
        // has an interest in this touch, we care that the child gets feedback.
        // Initial pass: we are ahead of every ancestor, so nothing can delay us.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // INVARIANT 1 IS THIS LINE. It runs inside the pointer dispatch.
        core.handle(PointerPhase.DOWN, inside = true, stolenByAncestor = false)

        while (true) {
            // Final pass: by the time an event reaches it, every ancestor that
            // wanted the drag has consumed it. That consumption is our
            // `pointercancel`.
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                // The pointer vanished from the stream: treat it as taken away.
                core.cancelled()
                return@awaitEachGesture
            }
            val stolen = event.changes.any { it.isConsumed }
            val phase = if (change.pressed) PointerPhase.MOVE else PointerPhase.UP
            core.handle(phase, isInside(change.position, size), stolen)
            // Either the press finished (UP) or a scroll took it (MOVE +
            // stolen). Both clear `isTracking`, and both mean this gesture is
            // over — any further `.ended` UIKit-style straggler would be
            // swallowed by the guards in `TouchDownCore` anyway.
            if (!core.isTracking) return@awaitEachGesture
        }
    }
}
