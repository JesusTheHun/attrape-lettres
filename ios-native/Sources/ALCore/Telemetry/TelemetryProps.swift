/**
 * The only property names that may be sent, all numeric except `exercise`,
 * which is a closed enum. There is deliberately no `string` escape hatch: a
 * free-text field is how a child's first name ends up on a server.
 *
 * D12 — **closed by construction, not by review.** In TypeScript the allowlist
 * was maintained by discipline plus a `sanitize()` pass that copied a
 * caller-supplied bag into a filtered bag. Here the bag *is* a struct with eight
 * named optional fields, so there is no API that accepts an arbitrary key and no
 * reviewer has to notice one being added. There is no dictionary keyed by a
 * string anywhere in this type, in the queue, or in the encoder.
 *
 * `sanitize` therefore has no Swift counterpart, and neither does the TS test
 * "silently drops any property not on the allowlist" — it passed
 * `childName: "Léa"` through an `as unknown as` cast, and there is no Swift cast
 * that would compile. It is replaced by the reflection test in
 * `TelemetryClosureTests`.
 *
 * `number` → `Int`, not `Double`, verified against every producer: `daysLeft`
 * comes from a ceil, `points`/`cost` from a floor in the rewards module, the
 * rest are counts. That makes the TS "drops non-finite numbers rather than
 * sending null" rule *unrepresentable* rather than merely enforced — there is no
 * `Int` NaN. (money.md R11: if a future call site wants a fraction, switch that
 * field to `Double` **and** restore an `.isFinite` guard in the encoder in the
 * same change.)
 *
 * Declaration order below mirrors the TypeScript interface. The WIRE order is
 * different and lives in `Telemetry.encodeProps` — see the note there.
 */
public struct TelemetryProps: Equatable, Sendable {
    public var exercise: ExerciseId?
    public var level: Int?
    public var rounds: Int?
    public var perfect: Int?
    public var points: Int?
    public var cost: Int?
    public var stage: Int?
    public var daysLeft: Int?

    public init(
        exercise: ExerciseId? = nil,
        level: Int? = nil,
        rounds: Int? = nil,
        perfect: Int? = nil,
        points: Int? = nil,
        cost: Int? = nil,
        stage: Int? = nil,
        daysLeft: Int? = nil
    ) {
        self.exercise = exercise
        self.level = level
        self.rounds = rounds
        self.perfect = perfect
        self.points = points
        self.cost = cost
        self.stage = stage
        self.daysLeft = daysLeft
    }

    /// The allowlist, as the wire spells it. Exists so the closure test has
    /// something to compare `Mirror` labels against; adding a field without
    /// touching this and the encoder fails the test run.
    public static let allowedKeys: [String] = [
        "exercise", "level", "rounds", "perfect", "points", "cost", "stage", "daysLeft",
    ]
}
