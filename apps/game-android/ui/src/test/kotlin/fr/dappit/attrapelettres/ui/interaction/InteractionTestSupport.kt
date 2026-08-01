package fr.dappit.attrapelettres.ui.interaction

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

// Shared scaffolding for the interaction suites.
//
// A host JUnit run has no `MonotonicFrameClock`, so an `Animatable` cannot be
// driven and nothing here tries to. What the tests need instead is a scope that
// accepts a `launch` and then does NOTHING with it: that turns "did this fire
// synchronously?" into a deterministic assertion, with no sleeping, no polling
// and no race. A completion observed after the call can only have run on the
// caller's stack; a completion that did not run proves the code took the
// animation path.

/** A dispatcher that drops every task instead of running it. */
private object InertDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

/** A scope whose coroutines are started and never run. */
internal fun inertScope(): CoroutineScope = CoroutineScope(Job() + InertDispatcher)
