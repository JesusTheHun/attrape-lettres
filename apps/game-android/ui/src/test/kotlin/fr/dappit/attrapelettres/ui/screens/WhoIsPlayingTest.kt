package fr.dappit.attrapelettres.ui.screens

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.ProfileStorage
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.sync.SyncClient
import fr.dappit.attrapelettres.core.sync.SyncTransport
import fr.dappit.attrapelettres.core.sync.WireChild
import fr.dappit.attrapelettres.core.sync.WireRoster
import fr.dappit.attrapelettres.core.telemetry.TelemetryProps
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.interaction.Anim
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/* --------------------------------------------------------------------------
 * `src/components/WhoIsPlaying.tsx` + the roster half of
 * `src/hooks/useProfile.tsx`, asserted against the TypeScript.
 *
 * The two branches that are easy to get wrong and impossible to see are both
 * here:
 *
 *   onRename={() => {
 *     const next = window.prompt(`Nouveau prénom pour ${c.name} ?`, c.name);
 *     if (next !== null) renameChild(c.id, next);   // "" DOES call it
 *   }}
 *   onDelete={() => {
 *     if (window.confirm(`Supprimer le profil de ${c.name} ? Tout sera perdu.`))
 *       deleteChild(c.id);
 *   }}
 *
 * and, in the hook:
 *
 *   const renameChild = (id, name) => {
 *     const trimmed = name.trim();
 *     if (!trimmed) return;                  // the empty rename is a no-op
 *     … { ...c, name: trimmed.slice(0, 14), nameRev, touchedAt: now }
 *   };
 * -------------------------------------------------------------------------- */

private const val DEVICE = "this-phone"
private const val T0 = 1_700_000_000_000L

private fun makeStore(
    kv: InMemoryKVStore = InMemoryKVStore(),
    sync: SyncClient? = null,
): ProfileStore = ProfileStore(
    kv = kv,
    difficultyOf = ::exerciseDifficulty,
    time = MutableTimeSource(T0),
    device = { DEVICE },
    sync = sync,
)

/** A store with one named child, and their id in hand. */
private fun storeWithChild(name: String): Pair<ProfileStore, String> {
    val store = makeStore()
    store.createChild(name)
    return store to store.activeId!!
}

// --- RosterPrompt ----------------------------------------------------------------

class RosterPromptTest {

    @Test
    fun `the rename prompt is window prompt's message, character for character`() {
        val p = RosterPrompt(RosterPrompt.Kind.RENAME, "c1", "Léa")
        assertEquals("Nouveau prénom pour Léa ?", p.message)
    }

    @Test
    fun `the delete prompt is window confirm's message, character for character`() {
        val p = RosterPrompt(RosterPrompt.Kind.DELETE, "c1", "Léa")
        assertEquals("Supprimer le profil de Léa ? Tout sera perdu.", p.message)
    }

    @Test
    fun `the two dialogs for one child are distinct values`() {
        // The screen holds ONE `prompt` at a time and switches on `kind`, so the
        // two must never compare equal — a merged identity would show the
        // rename field on a delete confirmation.
        val rename = RosterPrompt(RosterPrompt.Kind.RENAME, "c1", "Léa")
        val delete = RosterPrompt(RosterPrompt.Kind.DELETE, "c1", "Léa")
        assertTrue(rename != delete)
        assertEquals(rename, RosterPrompt(RosterPrompt.Kind.RENAME, "c1", "Léa"))
    }
}

// --- rosterAction, every combination -----------------------------------------------

class RosterActionTest {

    private val rename = RosterPrompt(RosterPrompt.Kind.RENAME, "c1", "Léa")
    private val delete = RosterPrompt(RosterPrompt.Kind.DELETE, "c1", "Léa")

    @Test
    fun `no dialog open — nothing happens, whatever is passed`() {
        assertEquals(RosterAction.None, rosterAction(null, confirmed = true, text = "Noé"))
        assertEquals(RosterAction.None, rosterAction(null, confirmed = false, text = "Noé"))
        assertEquals(RosterAction.None, rosterAction(null, confirmed = true, text = ""))
    }

    @Test
    fun `rename cancelled — window prompt returned null, so renameChild is NEVER called`() {
        // `if (next !== null) renameChild(...)`. This is the branch that is easy
        // to get wrong: a port that treated cancel as "rename to the seeded
        // value" would look correct in every manual test.
        assertEquals(RosterAction.None, rosterAction(rename, confirmed = false, text = "Noé"))
        assertEquals(RosterAction.None, rosterAction(rename, confirmed = false, text = ""))
    }

    @Test
    fun `rename confirmed — the typed text goes to the store, untouched`() {
        assertEquals(
            RosterAction.Rename("c1", "Noé"),
            rosterAction(rename, confirmed = true, text = "Noé"),
        )
        // NOT trimmed here — `renameChild` trims, and the trimming rule is the
        // hook's, so a second implementation cannot drift from it.
        assertEquals(
            RosterAction.Rename("c1", "  Noé  "),
            rosterAction(rename, confirmed = true, text = "  Noé  "),
        )
    }

    @Test
    fun `rename confirmed with an EMPTY field still calls the store`() {
        // `window.prompt` returning `""` is not `null`: the TSX calls
        // `renameChild(c.id, "")` and the hook's `if (!trimmed) return` swallows
        // it. Deciding emptiness here instead would move the rule out of the one
        // place that owns it.
        assertEquals(
            RosterAction.Rename("c1", ""),
            rosterAction(rename, confirmed = true, text = ""),
        )
        assertEquals(
            RosterAction.Rename("c1", "   "),
            rosterAction(rename, confirmed = true, text = "   "),
        )
    }

    @Test
    fun `delete declined — deleteChild is never called`() {
        assertEquals(RosterAction.None, rosterAction(delete, confirmed = false, text = ""))
        assertEquals(RosterAction.None, rosterAction(delete, confirmed = false, text = "Noé"))
    }

    @Test
    fun `delete confirmed — the id goes, and the text field is irrelevant`() {
        assertEquals(RosterAction.Delete("c1"), rosterAction(delete, confirmed = true, text = ""))
        assertEquals(RosterAction.Delete("c1"), rosterAction(delete, confirmed = true, text = "Noé"))
    }
}

// --- applyRosterAction --------------------------------------------------------------

class ApplyRosterActionTest {

    @Test
    fun `None touches nothing at all`() {
        val (store, id) = storeWithChild("Léa")
        val before = store.roster
        applyRosterAction(RosterAction.None, store)
        assertEquals(before, store.roster)
        assertEquals(1, store.children.size)
        assertEquals("Léa", store.children[0].name)
        assertEquals(id, store.activeId)
    }

    @Test
    fun `rename changes the name, trimmed and capped at 14 by the store`() {
        val (store, id) = storeWithChild("Léa")
        applyRosterAction(RosterAction.Rename(id, "  Noé  "), store)
        assertEquals("Noé", store.children[0].name)

        // `trimmed.slice(0, 14)` — `maxLength={14}` guards the CREATE field, and
        // the hook guards the rename.
        applyRosterAction(RosterAction.Rename(id, "Bartholomé-Alexandre"), store)
        assertEquals(Copy.WhoIsPlaying.NAME_MAX_LENGTH, store.children[0].name.length)
        assertEquals("Bartholomé-Ale", store.children[0].name)
    }

    @Test
    fun `the empty rename the hook ignores really is ignored`() {
        // `if (!trimmed) return` — the name stands and NOTHING is stamped.
        val (store, id) = storeWithChild("Léa")
        val before = store.roster
        applyRosterAction(RosterAction.Rename(id, ""), store)
        assertEquals("Léa", store.children[0].name)
        assertEquals(before, store.roster)

        applyRosterAction(RosterAction.Rename(id, "    "), store)
        assertEquals("Léa", store.children[0].name)
        assertEquals(before, store.roster)
    }

    @Test
    fun `renaming an unknown id changes nobody's name`() {
        val (store, _) = storeWithChild("Léa")
        applyRosterAction(RosterAction.Rename("nobody", "Noé"), store)
        assertEquals(listOf("Léa"), store.children.map { it.name })
    }

    @Test
    fun `delete removes the child, clears the wheel, and leaves a tombstone`() {
        val (store, id) = storeWithChild("Léa")
        applyRosterAction(RosterAction.Delete(id), store)
        assertTrue(store.children.isEmpty())
        // `activeId: r.activeId === id ? null : r.activeId`.
        assertNull(store.activeId)
        // A tombstone, not just a removal: without it the family's other device
        // still has the child and would hand them straight back on next merge.
        assertEquals(T0, store.roster.removed[id])
    }

    @Test
    fun `deleting a sibling leaves the player at the wheel`() {
        val store = makeStore()
        store.createChild("Léa")
        val lea = store.activeId!!
        store.createChild("Noé")
        val noe = store.activeId!!
        applyRosterAction(RosterAction.Delete(lea), store)
        assertEquals(listOf("Noé"), store.children.map { it.name })
        assertEquals(noe, store.activeId)
    }

    /**
     * INVARIANT 9. Renaming and deleting are roster operations; neither may
     * touch the star counters, and there is no total anywhere to touch.
     */
    @Test
    fun `no roster action writes, sums or invents a total`() {
        val (store, id) = storeWithChild("Léa")
        val starsBefore = store.roster.children[0].profile.stars
        val clearsBefore = store.roster.children[0].profile.clears

        applyRosterAction(RosterAction.Rename(id, "Noé"), store)
        assertEquals(starsBefore, store.roster.children[0].profile.stars)
        assertEquals(clearsBefore, store.roster.children[0].profile.clears)
        assertEquals(0, store.profile.balance)

        // …and the persisted shape has nowhere to put one even if a future edit
        // wanted to: `balance` is a FOLD (`balanceOf(stars)`), never a field.
        val fields = PersistedProfile::class.java.declaredFields.map { it.name }
        assertFalse("balance" in fields)
        assertTrue("stars" in fields)
    }
}

// --- the screen's other three decisions ------------------------------------------------

class RosterScreenStateTest {

    @Test
    fun `creating starts from the roster and then STICKS`() {
        // `useState(children.length === 0)`.
        assertTrue(rosterCreating(pinned = null, rosterIsEmpty = true))
        assertFalse(rosterCreating(pinned = null, rosterIsEmpty = false))
    }

    @Test
    fun `deleting the last child in edit mode does NOT jump into the name form`() {
        // The TSX's `creating` was pinned to `false` on mount and stays there,
        // so the grid keeps rendering with only the « Nouveau » card. A plain
        // `children.isEmpty()` would open the keyboard under the parent's
        // finger mid-tidy.
        assertFalse(rosterCreating(pinned = false, rosterIsEmpty = true))
        // …and the « Nouveau » card can still send them to the form.
        assertTrue(rosterCreating(pinned = true, rosterIsEmpty = false))
    }

    @Test
    fun `the card announces the job it will do, and it names the child`() {
        // `aria-label={editing ? `Renommer ${name}` : `Jouer avec ${name}`}`.
        // The name IS in the label and that is correct: a screen reader runs on
        // the device. Invariant 10 is about what LEAVES it.
        assertEquals("Jouer avec Léa", childCardLabel("Léa", editing = false))
        assertEquals("Renommer Léa", childCardLabel("Léa", editing = true))
    }

    @Test
    fun `the create button is gated on the TRIMMED name, and submits the untrimmed one`() {
        // `const ok = name.trim().length > 0`.
        assertFalse(canCreateProfile(""))
        assertFalse(canCreateProfile("   "))
        assertTrue(canCreateProfile("Léa"))
        assertTrue(canCreateProfile("  Léa  "))
    }

    @Test
    fun `the field clamps at 14 UTF-16 code units, exactly like maxLength`() {
        assertEquals(14, Copy.WhoIsPlaying.NAME_MAX_LENGTH)
        assertEquals("Léa", clampProfileName("Léa"))
        assertEquals("Bartholomé-Ale", clampProfileName("Bartholomé-Alexandre"))
        assertEquals(14, clampProfileName("aaaaaaaaaaaaaaaaaaaa").length)
    }

    @Test
    fun `the roster's fixed copy is the TSX's`() {
        assertEquals("Qui joue ?", Copy.WhoIsPlaying.HEADING)
        assertEquals("Modifier", Copy.WhoIsPlaying.EDIT)
        assertEquals("Terminé", Copy.WhoIsPlaying.EDIT_DONE)
        assertEquals("Nouveau profil", Copy.WhoIsPlaying.NEW_PROFILE)
        assertEquals("Nouveau", Copy.WhoIsPlaying.NEW_PROFILE_LABEL)
        assertEquals("Comment tu t'appelles ?", Copy.WhoIsPlaying.ASK_NAME)
        assertEquals("Ton prénom", Copy.WhoIsPlaying.NAME_PLACEHOLDER)
        assertEquals("C'est parti ! 🎉", Copy.WhoIsPlaying.GO)
        assertEquals("Retour", Copy.WhoIsPlaying.BACK)
        assertEquals("🦉", Copy.WhoIsPlaying.OWL_AVATAR)
        assertEquals("👋", Copy.WhoIsPlaying.WAVE)
        assertEquals("Supprimer Léa", Copy.WhoIsPlaying.delete("Léa"))
        // U+FF0B FULLWIDTH PLUS SIGN, not an ASCII "+".
        assertEquals("＋", Copy.WhoIsPlaying.NEW_PROFILE_GLYPH)
        assertTrue(Copy.WhoIsPlaying.NEW_PROFILE_GLYPH != "+")
    }
}

// --- the card's press --------------------------------------------------------------------

class RosterPressTest {

    @Test
    fun `the shop's PRESS keyframes, not the exercise tile's`() {
        // src/shop/anim.ts:
        //   const PRESS = [scale(1), scale(0.94), scale(1)]
        //   el.animate(PRESS, { duration: 130, easing: "ease-out" })
        // Tile.tsx bottoms out at 0.9; these are two different animations and
        // must not be unified.
        assertEquals(listOf(1f, 0.94f, 1f), RosterPress.SPEC.values)
        assertEquals(listOf(0f, 0.5f, 1f), RosterPress.SPEC.keyTimes)
        assertEquals(130, RosterPress.SPEC.durationMillis)
        assertEquals(Anim.EASE_OUT, RosterPress.SPEC.easing)
        assertTrue(
            RosterPress.SPEC.values != Anim.PRESS.values,
            "the shop press and the tile press are different animations",
        )
    }

    @Test
    fun `the shop press IS reduced-motion gated, where the tile press is not`() {
        // `press()` in shop/anim.ts starts `if (!el || reducedMotion()) return`;
        // `Tile.tsx` has no `matchMedia` call at all. D29's table lists "shop
        // press/pop" under gated for exactly this reason.
        assertTrue(RosterPress.shouldAnimate(FixedReduceMotion(false)))
        assertFalse(RosterPress.shouldAnimate(FixedReduceMotion(true)))
    }

    @Test
    fun `the authored metrics are the TSX's`() {
        assertEquals(24.dp, WhoIsPlayingMetrics.STAGE_PADDING_X)
        assertEquals(40.dp, WhoIsPlayingMetrics.STAGE_PADDING_TOP)
        assertEquals(40.dp, WhoIsPlayingMetrics.STAGE_PADDING_BOTTOM)
        assertEquals(24.dp, WhoIsPlayingMetrics.STAGE_GAP)
        assertEquals(20.dp, WhoIsPlayingMetrics.FORM_GAP)
        assertEquals(448.dp, WhoIsPlayingMetrics.GRID_MAX_WIDTH)
        assertEquals(384.dp, WhoIsPlayingMetrics.FORM_MAX_WIDTH)
        assertEquals(2, WhoIsPlayingMetrics.GRID_COLUMNS)
        assertEquals(16.dp, WhoIsPlayingMetrics.GRID_GAP)
        assertEquals(96.dp, WhoIsPlayingMetrics.AVATAR_ROW_HEIGHT)
        assertEquals(84.dp, WhoIsPlayingMetrics.AVATAR_SIZE)
        assertEquals(0.72f, WhoIsPlayingMetrics.OWL_RATIO)
        assertEquals(36.dp, WhoIsPlayingMetrics.CORNER_BUTTON_SIDE)
        assertEquals(8.dp, WhoIsPlayingMetrics.CORNER_BUTTON_OFFSET)
        assertEquals(3.dp, WhoIsPlayingMetrics.NEW_CARD_BORDER)
        assertEquals(150.dp, WhoIsPlayingMetrics.NEW_CARD_MIN_HEIGHT)
        assertEquals(46.dp, WhoIsPlayingMetrics.NEW_CARD_GLYPH_SIZE)
        assertEquals(0.4f, WhoIsPlayingMetrics.DISABLED_OPACITY)
    }
}

// --- INVARIANT 10 ---------------------------------------------------------------------

/**
 * Records every pushed roster so a test can read exactly what would have gone
 * up the wire. `exchange` answers with an EMPTY household, which is the
 * shortest path to "what would leave this device".
 */
private class RecordingSyncTransport : SyncTransport {

    val pushed = mutableListOf<WireRoster>()

    override suspend fun exchange(payload: WireRoster): WireRoster {
        pushed.add(payload)
        return WireRoster(children = emptyList(), removed = emptyMap())
    }
}

class RosterPrivacyTest {

    /** Deliberately distinctive, so a substring search cannot pass by accident. */
    private val name = "Zéphyrine"
    private val renamed = "Ombeline"

    @Test
    fun `the whole roster flow puts no name in a single pushed byte`() = runBlocking {
        val kv = InMemoryKVStore()
        val transport = RecordingSyncTransport()
        val store = makeStore(kv = kv, sync = SyncClient(transport))

        // Drive the screen's whole roster surface.
        store.createChild(name)
        val id = store.activeId!!
        applyRosterAction(
            rosterAction(
                RosterPrompt(RosterPrompt.Kind.RENAME, id, name),
                confirmed = true,
                text = renamed,
            ),
            store,
        )
        assertEquals(renamed, store.children[0].name)

        store.syncNow()

        assertTrue(transport.pushed.isNotEmpty(), "nothing was pushed — the assertion below would be vacuous")
        for (roster in transport.pushed) {
            // A data class's `toString` prints every field, recursively, so this
            // is the closest a test with no serializer on its classpath gets to
            // reading the bytes — and it would catch a name smuggled into any
            // nested field, not just the one we thought of.
            val rendered = roster.toString()
            assertFalse(rendered.contains(name), "a name reached the wire: $rendered")
            assertFalse(rendered.contains(renamed), "a name reached the wire: $rendered")
        }

        // The control that makes the assertion above mean something: the name
        // really was on the device, in the saved roster, the whole time.
        val saved = kv.string(ProfileStorage.ROSTER_KEY)
        assertNotNull(saved)
        assertTrue(saved.contains(renamed), "the name must live ON the device")
    }

    @Test
    fun `a deleted child's name is not in the tombstone either`() = runBlocking {
        val kv = InMemoryKVStore()
        val transport = RecordingSyncTransport()
        val store = makeStore(kv = kv, sync = SyncClient(transport))

        store.createChild(name)
        val id = store.activeId!!
        applyRosterAction(RosterAction.Delete(id), store)

        store.syncNow()

        assertTrue(transport.pushed.isNotEmpty())
        for (roster in transport.pushed) {
            assertFalse(roster.toString().contains(name))
            // The tombstone is keyed by the opaque child id, never the name.
            assertTrue(roster.removed.containsKey(id))
        }
    }

    /**
     * The structural reason the assertions above cannot become vacuous:
     * `WireChild` has no `name` field at all, so stripping is a property of the
     * TYPE and not of a serialiser configuration.
     */
    @Test
    fun `WireChild has no name field to carry one`() {
        val fields = WireChild::class.java.declaredFields.map { it.name }
        assertFalse("name" in fields, "WireChild must not carry a child's name")
        assertFalse("nameRev" in fields)
        assertTrue("id" in fields)
    }

    /**
     * And the reason a telemetry property cannot carry one: `TelemetryProps` is
     * Ints and one closed enum, with no `String` escape hatch. If someone adds a
     * String property to say "which child", this fails.
     */
    @Test
    fun `TelemetryProps has no field a name could fit in`() {
        for (field in TelemetryProps::class.java.declaredFields) {
            if (field.isSynthetic) continue
            val type = field.type.name
            assertFalse(
                type == "java.lang.String",
                "TelemetryProps.${field.name} is a String — invariant 10's escape hatch",
            )
        }
        assertFalse("name" in TelemetryProps.allowedKeys)
    }
}

// --- source scans ------------------------------------------------------------------------

private fun whoIsPlayingSourceFile(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui/screens/WhoIsPlaying.kt"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        for (candidate in listOf(
            File(dir, suffix),
            File(dir, "ui/$suffix"),
            File(dir, "apps/game-android/ui/$suffix"),
        )) {
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    return null
}

/** Lines with comments stripped, so a scan matches CODE and not prose. */
private fun whoIsPlayingCode(): String {
    val file = whoIsPlayingSourceFile() ?: return ""
    val out = mutableListOf<String>()
    var inBlock = false
    for (raw in file.readLines()) {
        val line = raw.trim()
        if (inBlock) {
            if (line.endsWith("*/")) inBlock = false
            continue
        }
        if (line.startsWith("/*")) {
            if (!line.endsWith("*/")) inBlock = true
            continue
        }
        if (line.startsWith("//") || line.startsWith("*")) continue
        out.add(raw.substringBefore("//"))
    }
    return out.joinToString("\n")
}

class WhoIsPlayingSourceScanTest {

    @Test
    fun `the scan can find the file it is meant to scan`() {
        assertTrue(whoIsPlayingSourceFile()?.isFile == true, "WhoIsPlaying.kt was not found")
    }

    /**
     * INVARIANT 10. The roster is the one screen that holds children's names, so
     * it is the one screen where a telemetry call would be catastrophic.
     */
    @Test
    fun `the roster screen names no telemetry, no transport and no logger`() {
        val code = whoIsPlayingCode()
        for (forbidden in listOf(
            "Telemetry", "track(", "reportError", "SyncClient", "syncNow",
            "println(", "Log.", "System.out",
        )) {
            assertFalse(
                code.contains(forbidden),
                "WhoIsPlaying.kt names \"$forbidden\" — a name must never leave the device",
            )
        }
    }

    /** INVARIANT 8 / 9. The roster spends nothing and earns nothing. */
    @Test
    fun `the roster screen never awards, spends or buys`() {
        val code = whoIsPlayingCode()
        for (forbidden in listOf("award(", "spend(", ".buy(", "balance", "sessionReward")) {
            assertFalse(
                code.contains(forbidden),
                "WhoIsPlaying.kt names \"$forbidden\"; points are not this screen's business",
            )
        }
    }

    /** INVARIANT 5's sibling. No child on the device is ever hidden or gated. */
    @Test
    fun `the roster lists every child, unconditionally`() {
        val code = whoIsPlayingCode()
        // The grid iterates the roster itself — not a filtered, sorted or
        // truncated view of it.
        assertTrue(code.contains("roster.children"), "the grid must read the store's own list")
        for (forbidden in listOf(
            "children.filter", "children.sorted", "children.take(", "children.drop(",
            "locked", "unlocked",
        )) {
            assertFalse(
                code.contains(forbidden),
                "WhoIsPlaying.kt names \"$forbidden\"; every child is always listed",
            )
        }
    }

    /** INVARIANT 1 / A12: `Modifier.clickable` is banned module-wide. */
    @Test
    fun `the roster never reaches for Modifier clickable`() {
        val code = whoIsPlayingCode()
        assertFalse(code.contains("clickable("), "clickable fires on UP, behind a ripple")
        // …and Material's own dialog would have brought one in with it.
        assertFalse(code.contains("AlertDialog"), "Material3's AlertDialog uses clickable buttons")
    }

    /**
     * Every mutation goes through the ONE choke point. A screen that reached for
     * `ProfileStorage` or `KVStore` directly would bypass the stamping,
     * tombstoning and clamping that make the roster mergeable (invariant 9).
     */
    @Test
    fun `the roster mutates only through ProfileStore`() {
        val code = whoIsPlayingCode()
        for (forbidden in listOf("ProfileStorage", "KVStore", "saveRoster", "kv.set")) {
            assertFalse(
                code.contains(forbidden),
                "WhoIsPlaying.kt names \"$forbidden\"; ProfileStore is the only door",
            )
        }
    }
}
