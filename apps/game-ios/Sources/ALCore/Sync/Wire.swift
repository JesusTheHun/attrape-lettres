/* -------------------------------------------------------------------------- */
/* The wire format — what the server is allowed to know.                        */
/*                                                                             */
/* Names never leave the device (see ChildProfile.name). `toWire` drops both    */
/* the name and its LWW stamp, so a joining phone cannot even be handed one —   */
/* it asks the parent « Qui est-ce ? » instead. What remains on the server is   */
/* genuinely anonymous rather than merely pseudonymous, which is the difference */
/* between a one-paragraph privacy policy and a compliance project.             */
/*                                                                             */
/* INVARIANT 10, STRUCTURALLY: `WireChild` has NO `name` and NO `nameRev`       */
/* property and `WireRoster` has no `activeId`, so stripping is a property of   */
/* the TYPE rather than a runtime `Omit` someone can forget. `toWire` is a      */
/* memberwise projection — there is no spread operator to accidentally carry a  */
/* field through. `WireTests` still asserts on the serialised bytes, guarding   */
/* the Codable conformance against a future custom encoder.                     */
/*                                                                             */
/* Field names are byte-identical to the TS property names: the household       */
/* document is read and written by web/Android Capacitor devices too.           */
/* -------------------------------------------------------------------------- */

/** A child as it travels: everything except who they are. */
public struct WireChild: Codable, Hashable, Sendable {
    public var id: String
    public var touchedAt: Millis
    public var profile: PersistedProfile

    public init(id: String, touchedAt: Millis, profile: PersistedProfile) {
        self.id = id
        self.touchedAt = touchedAt
        self.profile = profile
    }
}

public struct WireRoster: Codable, Hashable, Sendable {
    public var children: [WireChild]
    public var removed: [String: Millis]

    public init(children: [WireChild], removed: [String: Millis]) {
        self.children = children
        self.removed = removed
    }
}

/// TS `NO_NAME_REV` — the zero stamp a never-named child carries.
private let noNameRev = Rev.zero

/** Strip identity. `activeId` goes too — it is about this tablet, not the family. */
public func toWire(_ r: Roster) -> WireRoster {
    WireRoster(
        children: r.children.map { WireChild(id: $0.id, touchedAt: $0.touchedAt, profile: $0.profile) },
        removed: r.removed
    )
}

/**
 * Re-attach identity from what THIS device already knows. A child we have never
 * seen gets a placeholder and a zero stamp, so the local name always wins the
 * merge and the parent is prompted to say who it is.
 */
public func fromWire(_ w: WireRoster, local: Roster) -> Roster {
    var known: [String: ChildProfile] = [:]
    for c in local.children { known[c.id] = c }
    return Roster(
        children: w.children.map { c in
            let mine = known[c.id]
            return ChildProfile(
                id: c.id,
                name: mine?.name ?? "Enfant",
                nameRev: mine?.nameRev ?? noNameRev,
                touchedAt: c.touchedAt,
                profile: c.profile
            )
        },
        activeId: nil, // never adopted from the wire
        removed: w.removed
    )
}
