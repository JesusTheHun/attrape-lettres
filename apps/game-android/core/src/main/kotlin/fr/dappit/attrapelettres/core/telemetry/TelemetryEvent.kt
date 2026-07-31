package fr.dappit.attrapelettres.core.telemetry

/**
 * Events we are willing to record. Anything else is dropped — in Kotlin, by not
 * being expressible at all.
 *
 * D12: the TS `EVENTS.includes(event)` runtime check and its dev
 * `console.warn` **disappear**; an unknown event is not representable. Do not
 * add a `CUSTOM(String)` case, do not add a `fromWire(String)` companion. This
 * enum deliberately differs from `ExerciseId` (A2) on that last point:
 * `ExerciseId.fromWire` exists because stored ledger keys are read BACK, while
 * an event name only ever travels outward, so a parser would be a hole and
 * nothing else.
 *
 * The `wire` values are the names the server sees, byte-identical to the
 * TypeScript `EVENTS` tuple and the Swift `rawValue`s — one events table is fed
 * by three clients.
 *
 * NB (money.md R10): six of these eleven have no producer today
 * (`exercise_started`, `session_completed`, `shop_opened`, `item_bought`,
 * `mascot_grown`, `trial_expired`). The list is aspirational in the PWA and is
 * ported whole; do not prune it and do not "finish" the wiring.
 */
enum class TelemetryEvent(val wire: String) {
    EXERCISE_STARTED("exercise_started"),
    SESSION_COMPLETED("session_completed"),
    SHOP_OPENED("shop_opened"),
    ITEM_BOUGHT("item_bought"),
    MASCOT_GROWN("mascot_grown"),
    TRIAL_STARTED("trial_started"),
    TRIAL_EXPIRED("trial_expired"),
    PAYWALL_SHOWN("paywall_shown"),
    PURCHASE_COMPLETED("purchase_completed"),
    PURCHASE_FAILED("purchase_failed"),
    PURCHASE_RESTORED("purchase_restored"),
}
