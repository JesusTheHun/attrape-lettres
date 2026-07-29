/// Events we are willing to record. Anything else is dropped — in Swift, by not
/// being expressible at all.
///
/// D12: the TS `EVENTS.includes(event)` runtime check and its dev `console.warn`
/// **disappear**; an unknown event is not representable. Do not add a
/// `case custom(String)`. Do not expose a public initialiser from an arbitrary
/// string. The raw values are the wire names.
///
/// NB (money.md R10): six of these eleven have no producer today
/// (`exercise_started`, `session_completed`, `shop_opened`, `item_bought`,
/// `mascot_grown`, `trial_expired`). The list is aspirational in the PWA and is
/// ported whole; do not prune it and do not "finish" the wiring.
public enum TelemetryEvent: String, CaseIterable, Sendable {
    case exerciseStarted = "exercise_started"
    case sessionCompleted = "session_completed"
    case shopOpened = "shop_opened"
    case itemBought = "item_bought"
    case mascotGrown = "mascot_grown"
    case trialStarted = "trial_started"
    case trialExpired = "trial_expired"
    case paywallShown = "paywall_shown"
    case purchaseCompleted = "purchase_completed"
    case purchaseFailed = "purchase_failed"
    case purchaseRestored = "purchase_restored"
}
