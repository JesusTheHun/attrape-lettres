package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.Roster
import kotlinx.serialization.Serializable

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
/* the TYPE rather than of a serialiser configuration someone can loosen.       */
/* `toWire` names every field it copies — there is no spread operator and no    */
/* reflective copy to carry one through by accident. `WireTest` still asserts   */
/* on the serialised bytes, which is what guards the generated serialiser       */
/* against a future hand-written one.                                          */
/*                                                                             */
/* Field names are byte-identical to the TS property names: the same household  */
/* document is read and written by the web app and the iOS app.                */
/* -------------------------------------------------------------------------- */

/** A child as it travels: everything except who they are. */
@Serializable
data class WireChild(
    val id: String,
    val touchedAt: Long,
    val profile: PersistedProfile,
)

@Serializable
data class WireRoster(
    val children: List<WireChild>,
    val removed: Map<String, Long>,
)

/**
 * The TS `NO_NAME_REV` — the zero stamp a never-named child carries. Written
 * out here rather than borrowed from persistence so this file's guarantee (a
 * placeholder that always loses) does not depend on a constant elsewhere.
 */
private val NO_NAME_REV = Rev(0L, "")

/** The placeholder a device shows for a child it has never been told about. */
private const val UNKNOWN_CHILD = "Enfant"

/** Strip identity. `activeId` goes too — it is about this tablet, not the family. */
fun toWire(r: Roster): WireRoster =
    WireRoster(
        children = r.children.map { WireChild(id = it.id, touchedAt = it.touchedAt, profile = it.profile) },
        removed = r.removed,
    )

/**
 * Re-attach identity from what THIS device already knows. A child we have never
 * seen gets a placeholder and a zero stamp, so the local name always wins the
 * merge and the parent is prompted to say who it is.
 */
fun fromWire(w: WireRoster, local: Roster): Roster {
    val known = HashMap<String, ChildProfile>()
    for (c in local.children) known[c.id] = c
    return Roster(
        children = w.children.map { c ->
            val mine = known[c.id]
            ChildProfile(
                id = c.id,
                name = mine?.name ?: UNKNOWN_CHILD,
                nameRev = mine?.nameRev ?: NO_NAME_REV,
                touchedAt = c.touchedAt,
                profile = c.profile,
            )
        },
        activeId = null, // never adopted from the wire
        removed = w.removed,
    )
}

/** A roster with no children and no names in it — the "I know nobody" identity. */
private val NOBODY = Roster(children = emptyList(), activeId = null, removed = emptyMap())

/**
 * The wire-to-wire fold: merge two household documents without ever
 * materialising a name.
 *
 * `mergeRoster` works on `Roster`, which has a `name` field, so folding two
 * wire documents means passing through it. Going via [NOBODY] — a roster this
 * device knows nobody in — means every child comes back out as the placeholder,
 * and `toWire` drops it again. Nothing identifying is invented, and nothing
 * that travels is lost.
 *
 * Only a *server* needs this, which is why it exists for [StubSyncTransport].
 * A real device always has its own [Roster] and folds with [mergeRoster].
 */
fun mergeWire(a: WireRoster, b: WireRoster): WireRoster =
    toWire(mergeRoster(fromWire(a, NOBODY), fromWire(b, NOBODY)))
