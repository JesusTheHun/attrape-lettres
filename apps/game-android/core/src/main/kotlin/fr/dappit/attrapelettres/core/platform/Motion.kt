package fr.dappit.attrapelettres.core.platform

/* -------------------------------------------------------------------------- */
/* ONE source of truth for reduced motion.                                      */
/*                                                                              */
/* Invariant 6 requires `prefers-reduced-motion` to be honoured by the mascot   */
/* and the confetti. Independent per-screen reads are that many chances to      */
/* forget one — and tests could not force the setting on. So: one interface     */
/* here, injected at the root, read everywhere. Android has no                  */
/* `prefers-reduced-motion`; the adapter reads                                  */
/* `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, and it deliberately gates   */
/* only what the web gates — mascot and confetti, never press/shake.            */
/* -------------------------------------------------------------------------- */

interface ReduceMotionSource {
    val isReduced: Boolean
}

/** Tests and previews. */
class FixedReduceMotion(override val isReduced: Boolean) : ReduceMotionSource
