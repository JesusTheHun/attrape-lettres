package fr.dappit.attrapelettres.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.art.mascot.MascotRigView
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.MotionSurface

// Ollie — the child's companion on screen.
//
// `src/components/Ollie.tsx` is the ORIGINAL, an emoji in a bobbing div. It
// still sits in the web tree and is imported by nothing: `src/types.ts` records
// « Drop-in replacement for <Ollie mood>. Agent A implements the SVG rig. », and
// every live call site (the seven exercises, the hub, the Dashboard, the shop)
// renders `<Mascot config mood />`. So this file is the app-side slot the app
// has always called Ollie, and what it holds is the rig.
//
// It is thin ON PURPOSE, and thinner than the iOS twin. `Ollie.swift` had to
// re-host the rig itself, because a SwiftUI `Canvas` clips to its bounds and a
// stade-9 halo leaves the 100-unit box; it also had to split the rainbow band
// off the draw list to sweep it. On Android :art already owns both:
// `MascotRigView` draws into a canvas `MascotRig.OVERFLOW` times the mascot and
// measures as `size` (so nothing is clipped and layout still matches the web),
// carries the species' French accessibility label, and bakes the sheen band at
// its parked offset inside the silhouette mask. Re-doing any of that here would
// be a second implementation of the same thing, which is how two ports drift.
//
// What this file DOES own is the one thing :art cannot decide: which motion
// surface the mascot is, and therefore whether reduced motion silences it.
// Invariant 6 says it does — the mascot and the confetti are exactly what the
// web gates (`motion-reduce:animate-none` on the mascot, the `reduce` check in
// the confetti), never the press and never the shake. That decision lives in ONE
// table, `MotionSurface`, and this file consults it rather than restating it.

object OllieDefaults {
    /** `size = 88` — the TSX default, and `MascotRigView`'s. */
    val SIZE: Dp = 88.dp

    /** The mascot's surface in the reduce-motion table. */
    val SURFACE: MotionSurface = MotionSurface.MASCOT
}

/**
 * A [ReduceMotionSource] filtered through one [MotionSurface].
 *
 * :art's rig takes a `ReduceMotionSource` and asks it directly, which is right
 * for a module that has no opinion about which surfaces are gated. :ui does have
 * one, and it is a table. This adapter is how the table reaches a module that
 * only knows the interface — and it is a LIVE view, not a snapshot: the system
 * setting can change while the app is running, and a `FixedReduceMotion` copied
 * at composition time would keep bobbing a mascot the child just asked to still.
 */
class GatedReduceMotion(
    private val source: ReduceMotionSource,
    private val surface: MotionSurface,
) : ReduceMotionSource {
    override val isReduced: Boolean
        get() = !Anim.shouldAnimate(surface, source)
}

/**
 * The mascot, animated by its [mood].
 *
 * [preview] is the web's shop thumbnail: it clips (`overflow: hidden`), pins the
 * growth scale to 1 and never bobs. [focus] crops in the 0..100 mascot space and
 * is only ever used with [preview].
 */
@Composable
fun Ollie(
    config: MascotConfig,
    mood: Mood,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
    size: Dp = OllieDefaults.SIZE,
    preview: Boolean = false,
    focus: SvgRect? = null,
) {
    val gated = remember(reduceMotion) { GatedReduceMotion(reduceMotion, OllieDefaults.SURFACE) }
    MascotRigView(
        config = config,
        mood = mood,
        reduceMotion = gated,
        modifier = modifier,
        size = size,
        preview = preview,
        focus = focus,
    )
}
