package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.mascot.CATALOG
import fr.dappit.attrapelettres.core.mascot.DEFAULT_LOOKS
import fr.dappit.attrapelettres.core.mascot.DefaultLook
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.persistence.ProfileView
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.SpeciesProgress
import fr.dappit.attrapelettres.core.persistence.StarCounters
import fr.dappit.attrapelettres.core.persistence.blankConfig
import fr.dappit.attrapelettres.core.persistence.blankSpeciesMap
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.platform.SilentAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/* -------------------------------------------------------------------------- */
/* Fixtures shared by the shop suites.                                         */
/*                                                                             */
/* `Dispatchers.Unconfined` is the scope every model here gets: `say` is a      */
/* fire-and-forget `launch`, and Unconfined runs the body eagerly on the        */
/* calling thread, so a `SilentAudioEngine` transcript is already settled when  */
/* the assertion runs — no test dispatcher, no `runBlocking`, no new            */
/* dependency in `:ui`'s test classpath. The shop is the right place for that   */
/* shortcut: nothing it launches ever suspends on a timer.                     */
/* -------------------------------------------------------------------------- */

/** A flattened profile with no store behind it — for the pure surfaces. */
internal fun shopProfile(
    species: Species = Species.UNICORN,
    balance: Int = 60,
    owned: List<String> = emptyList(),
    config: MascotConfig? = null,
): ProfileView {
    val progress = SpeciesProgress(
        config = config?.copy(species = species) ?: blankConfig(species),
        owned = owned,
        rev = Rev.ZERO,
    )
    val persisted = PersistedProfile(
        chosen = true,
        current = species,
        currentRev = Rev.ZERO,
        species = blankSpeciesMap() + (species to progress),
        stars = StarCounters(earned = emptyMap(), spent = emptyMap()),
        clears = emptyMap(),
    )
    return ProfileView(persisted, balance = balance, ledger = emptyMap())
}

/** Records the particle ceremony without drawing one — the [ShopCelebration] seam. */
internal class CelebrationSpy : ShopCelebration {
    val events = mutableListOf<String>()

    override fun purchased(option: CustomizationOption) {
        events.add("purchased:${option.id}")
    }

    override fun equipped() {
        events.add("equipped")
    }

    override fun grewFlight(price: Int) {
        events.add("grewFlight:$price")
    }

    override fun grewBurst() {
        events.add("grewBurst")
    }
}

/**
 * The web test's `ShopHarness`: boot a child, then award six distinct
 * first-clears of a PAYING exercise (difficulty 1, no perfect-round bonus) =
 * 6 x 10 = 60 pts — "plenty for a 40-pt style".
 *
 * The award goes through the real `ProfileStore`, i.e. through `sessionReward`,
 * because there is no other way to put a point in a profile (invariant 8) and a
 * harness that hand-wrote a balance would be testing a fiction.
 */
internal class ShopHarness(awardLevels: Int = 6) {
    val kv = InMemoryKVStore()
    val audio = SilentAudioEngine()
    val celebration = CelebrationSpy()

    val store = ProfileStore(
        kv = kv,
        difficultyOf = ::exerciseDifficulty,
        time = MutableTimeSource(1_000),
        device = { "test-device" },
    )

    init {
        store.createChild("Test")
        repeat(awardLevels) { level ->
            store.award(ExerciseId.ORDER_SYLLABLES, level, perfectRounds = 0, totalRounds = 1)
        }
    }

    val model = newModel()

    fun newModel(): ShopModel = ShopModel(
        store = store,
        audio = audio,
        kv = kv,
        scope = CoroutineScope(Dispatchers.Unconfined),
        celebration = celebration,
    )

    /** A catalog row by its French shop label, for the active species. */
    fun option(name: String): CustomizationOption =
        CATALOG.first { it.species == store.profile.config.species && it.name == name }

    fun defaultLook(name: String): DefaultLook =
        DEFAULT_LOOKS.getValue(store.profile.config.species).first { it.name == name }

    fun surface(option: CustomizationOption): ShopItemSurface =
        ShopItemSurface(option, store.profile, model.cart?.id)
}
