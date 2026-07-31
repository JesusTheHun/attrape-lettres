package fr.dappit.attrapelettres.core.telemetry

import fr.dappit.attrapelettres.core.domain.ExerciseId

/**
 * The only property names that may be sent, all numeric except [exercise],
 * which is a closed enum. There is deliberately no `String` escape hatch: a
 * free-text field is how a child's first name ends up on a server.
 *
 * D12 — **closed by construction, not by review.** In TypeScript the allowlist
 * was maintained by discipline plus a `sanitize()` pass that copied a
 * caller-supplied bag into a filtered bag. Here the bag *is* a data class with
 * eight named nullable fields, so there is no API that accepts an arbitrary key
 * and no reviewer has to notice one being added. There is no `Map<String, …>`
 * anywhere in this type, in the queue, or in the encoder — and
 * `TelemetryClosureTest` fails the build's test run if one appears.
 *
 * `sanitize` therefore has no Kotlin counterpart, and neither does the TS test
 * "silently drops any property not on the allowlist" — it passed
 * `childName: "Léa"` through an `as unknown as` cast, and there is no Kotlin
 * cast that would compile. It is replaced by the reflection test in
 * `TelemetryClosureTest`.
 *
 * `number` → `Int`, not `Double`, verified against every producer: `daysLeft`
 * comes from a ceil, `points`/`cost` from a floor in the rewards module, the
 * rest are counts. That makes the TS "drops non-finite numbers rather than
 * sending null" rule *unrepresentable* rather than merely enforced — there is
 * no `Int` NaN. (money.md R11: if a future call site wants a fraction, switch
 * that field to `Double` **and** restore an `isFinite()` guard in the encoder
 * in the same change.)
 *
 * Declaration order below mirrors the TypeScript interface. The WIRE order is
 * different and lives in `Telemetry.encodeProps` — see the note there.
 */
data class TelemetryProps(
    val exercise: ExerciseId? = null,
    val level: Int? = null,
    val rounds: Int? = null,
    val perfect: Int? = null,
    val points: Int? = null,
    val cost: Int? = null,
    val stage: Int? = null,
    val daysLeft: Int? = null,
) {
    companion object {
        /**
         * The allowlist, as the wire spells it. Exists so the closure test has
         * something to compare the reflected field names against; adding a
         * field without touching this list and the encoder fails the test run.
         */
        val allowedKeys: List<String> =
            listOf("exercise", "level", "rounds", "perfect", "points", "cost", "stage", "daysLeft")
    }
}
