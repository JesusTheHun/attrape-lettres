package fr.dappit.attrapelettres.core.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Port of the mascot vocabulary from `src/types.ts` (with `src/mascot/catalog.ts`
 * for the catalog row's shape).
 *
 * These types have exactly ONE owner and it is here. Content, levels,
 * persistence, the mascot art and the shop all need them; written twice they
 * drift. The `core/mascot` package (growth math, anchors, ids, catalog data) and the
 * `:art` rigs import this file — they do not redeclare it.
 *
 * `Mood` is not here: it is shared with the exercises and lives in ExerciseId.kt.
 */

/** Mascot + rewards --------------------------------------------------------*/
/* Shared contract for the mascot / points / customization feature. Agents A   */
/* (design), B (earn+dashboard) and C (spend+customize) all build against this. */
/* Do not fork these shapes; add agent-local types in agent-owned files.       */

/**
 * `wire` values are FROZEN for the same reason `ExerciseId`'s are (A2): they key
 * `PersistedProfile.species` on disk and over the sync wire, where the web app
 * and the iOS app write the same strings. The `@SerialName` mirrors `wire` so
 * kotlinx writes the exact string a hand-built key uses — `MascotTest` welds the
 * two together so they cannot drift.
 */
@Serializable
enum class Species(val wire: String) {
    @SerialName("unicorn") UNICORN("unicorn"),
    @SerialName("cat") CAT("cat"),
    @SerialName("fox") FOX("fox"),
    @SerialName("rabbit") RABBIT("rabbit"),
    @SerialName("dragon") DRAGON("dragon"),
    ;

    companion object {
        private val byWire = entries.associateBy(Species::wire)

        /** Null for an unknown string — a stored key from a newer build is data to skip, not a crash. */
        fun fromWire(wire: String): Species? = byWire[wire]
    }
}

/** 0 = baby … 9 = majestic. 10 growth stages. */
const val GROWTH_STAGES = 10

@Serializable
enum class CustomizationCategory(val wire: String) {
    @SerialName("accessory") ACCESSORY("accessory"),
    @SerialName("color") COLOR("color"),
    @SerialName("style") STYLE("style"),
}

/** One buyable item in the shop. `slot` is the config key it writes. */
@Serializable
data class CustomizationOption(
    /** Globally unique, e.g. "unicorn.horn.rainbow". */
    val id: String,
    val species: Species,
    val category: CustomizationCategory,
    /** Config key this writes: colours/styles set `colors[slot]`/`styles[slot]`. */
    val slot: String,
    /** Colour hex or style-variant id. Ignored for pure accessories. */
    val value: String,
    /** French shop label. */
    val name: String,
    /** Optional shop thumbnail. */
    val emoji: String? = null,
    /** Cost in points. */
    val cost: Int,
    /** Optional growth gate (default 0). */
    // NB: stays nullable. The catalog test distinguishes "absent" from "0" —
    // `catalog.ts` only spreads `minStage` in when it was passed.
    val minStage: Int? = null,
)

/** Everything that makes one child's mascot look the way it does. */
@Serializable
data class MascotConfig(
    val species: Species,
    /** 0..GROWTH_STAGES-1 */
    val stage: Int,
    /** slot -> hex colour, e.g. { hornColor: "#F0A", tailColor: "#8CF" } */
    // NB: index signatures stay `Map<String, String>`. The rigs `pick()` with
    // fallbacks, so an unknown slot key written by an older build must round-trip
    // untouched — modelling slots as enums would silently drop it.
    val colors: Map<String, String>,
    /** slot -> variant id, e.g. { tailSize: "long", hair: "curly" } */
    val styles: Map<String, String>,
    /** Equipped accessory option ids. */
    val accessories: List<String>,
)
