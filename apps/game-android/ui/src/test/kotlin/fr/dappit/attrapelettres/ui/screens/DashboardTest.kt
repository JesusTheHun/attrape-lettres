package fr.dappit.attrapelettres.ui.screens

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.ProfileStorage
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.persistence.StarCounters
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/* --------------------------------------------------------------------------
 * `src/components/Dashboard.tsx`, asserted against the TypeScript.
 *
 *   const { config, balance } = profile;
 *   const stage = config.stage;
 *   const pct = ((stage + 1) / GROWTH_STAGES) * 100;
 *   const mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)));
 *
 *   useEffect(() => {
 *     el.style.width = `${pct}%`;
 *     if (reduce) return;
 *     el.animate([{ width: "0%" }, { width: `${pct}%` }],
 *       { duration: 900, easing: "cubic-bezier(.2,.9,.3,1)" });
 *   }, [pct]);
 *
 * `GROWTH_STAGES = 10` (`src/types.ts`). Tailwind spacing is n x 4 px:
 * px-5 = 20, pt-6 = 24, pb-10 = 40, gap-5 = 20, p-4 = 16, mb-2 = 8, mt-1 = 4,
 * -mt-3 = -12, px-8 = 32, py-4 = 16, py-3 = 12, px-4 = 16, py-2 = 8, h-5 = 20,
 * gap-2 = 8.
 *
 * Every expected number below is transcribed from the TSX (or produced by
 * running its expression in node), never read back out of the Kotlin.
 * -------------------------------------------------------------------------- */

/** `GROWTH_STAGES` in `src/types.ts` — typed out, not imported for the check. */
private const val TS_GROWTH_STAGES = 10

private fun near(actual: Float, expected: Float, what: String) {
    assertTrue(abs(actual - expected) < 1e-3f, "$what: got $actual, expected $expected")
}

// --- mascotSize ---------------------------------------------------------------

class DashboardMascotSizeTest {

    /**
     * Every expectation here was produced by running the TypeScript expression
     * `Math.round(Math.min(230, Math.max(140, b * 0.46)))` in node.
     */
    @Test
    fun `the clamp and the rounding are the TSX's`() {
        assertEquals(140f, dashboardMascotSize(0f))
        assertEquals(140f, dashboardMascotSize(100f))
        assertEquals(140f, dashboardMascotSize(304f)) // 139.84 -> floor of the clamp
        assertEquals(140f, dashboardMascotSize(305f)) // 140.3
        assertEquals(141f, dashboardMascotSize(306f)) // 140.76
        assertEquals(184f, dashboardMascotSize(400f))
        assertEquals(184f, dashboardMascotSize(401f)) // 184.46
        assertEquals(185f, dashboardMascotSize(402f)) // 184.92
        assertEquals(230f, dashboardMascotSize(499f)) // 229.54 -> 230
        assertEquals(230f, dashboardMascotSize(500f))
        assertEquals(230f, dashboardMascotSize(1000f)) // capped, not scaled
    }

    @Test
    fun `a half rounds UP, as Math round does`() {
        // 375 x 0.46 = 172.5 exactly; JS answers 173.
        assertEquals(173f, dashboardMascotSize(375f))
    }

    @Test
    fun `the size never leaves 140 to 230, for any width a tablet can produce`() {
        var box = 0f
        while (box <= 2000f) {
            val size = dashboardMascotSize(box)
            assertTrue(size in 140f..230f, "box $box -> $size")
            box += 7f
        }
    }

    @Test
    fun `the Dp overload is the same clamp`() {
        assertEquals(140.dp, dashboardMascotSize(0.dp))
        assertEquals(184.dp, dashboardMascotSize(400.dp))
        assertEquals(230.dp, dashboardMascotSize(1000.dp))
    }

    @Test
    fun `the pedestal is clamp(190px, 62 percent, 300px) of the CARD, not of the window`() {
        // `width: "clamp(190px,62%,300px)"` on an `aspect-square` circle. The
        // 62 % is of the card's own width, which is why it resolves through
        // `fluidPercent` and not through `fluid` (D16's whole point).
        assertEquals(190f, dashboardPedestalSide(0f))
        assertEquals(190f, dashboardPedestalSide(300f)) // 186 -> floor
        near(dashboardPedestalSide(400f), 248f, "62% of 400")
        assertEquals(300f, dashboardPedestalSide(500f)) // 310 -> cap
        assertEquals(190f, DashboardMetrics.PEDESTAL_MIN)
        assertEquals(62f, DashboardMetrics.PEDESTAL_PERCENT)
        assertEquals(300f, DashboardMetrics.PEDESTAL_MAX)
    }
}

// --- the growth meter -----------------------------------------------------------

class GrowthMeterTest {

    @Test
    fun `GROWTH_STAGES is 10 and the meter counts from 1`() {
        assertEquals(TS_GROWTH_STAGES, GROWTH_STAGES)
        assertEquals("1/10", GrowthMeter(0).counterText)
        assertEquals("5/10", GrowthMeter(4).counterText)
        assertEquals("10/10", GrowthMeter(9).counterText)
    }

    @Test
    fun `pct = ((stage + 1) div GROWTH_STAGES) times 100`() {
        // A tolerance because the TSX's expression is floating point and so is
        // this one; `1/10 * 100` is not guaranteed to land on 10.0 by identity.
        assertEquals(10.0, GrowthMeter(0).percent, 1e-9)
        assertEquals(20.0, GrowthMeter(1).percent, 1e-9)
        assertEquals(50.0, GrowthMeter(4).percent, 1e-9)
        assertEquals(100.0, GrowthMeter(9).percent, 1e-9)
        // A brand-new companion still shows a sliver: the bar is never empty,
        // because the first stage already counts as one of ten.
        assertTrue(GrowthMeter(0).percent > 0.0)
    }

    @Test
    fun `the ARIA range is 1 to GROWTH_STAGES with valuenow = stage + 1`() {
        val m = GrowthMeter(3)
        assertEquals(1, m.minimum)
        assertEquals(TS_GROWTH_STAGES, m.maximum)
        assertEquals(4, m.value)
    }

    @Test
    fun `the fill resolves the percentage against the track it is given`() {
        near(GrowthMeter(4).fillWidth(400f), 200f, "half a 400 px track")
        near(GrowthMeter(9).fillWidth(400f), 400f, "a full track")
        near(GrowthMeter(0).fillWidth(400f), 40f, "one stage of ten")
        // No track yet (the first layout pass): no fill, and no negative width.
        near(GrowthMeter(4).fillWidth(0f), 0f, "an unmeasured track")
    }

    @Test
    fun `the meter is a function of stage alone — nothing is stored or summed`() {
        // Invariant 9's shape at this scale: two meters built from the same
        // stage are equal, and there is no history, no accumulator, no clock.
        assertEquals(GrowthMeter(6), GrowthMeter(6))
        assertNotEquals(GrowthMeter(6), GrowthMeter(7))
    }
}

// --- the sweep --------------------------------------------------------------------

class GrowthSweepTest {

    @Test
    fun `the bar LANDS at pct whether or not the sweep runs`() {
        // `el.style.width = pct%` happens BEFORE the reduced-motion check. A
        // port that gated the whole effect would show a child with the setting
        // on an empty bar.
        assertEquals(50.0, GrowthSweep(percent = 50.0, reduceMotion = false).settled)
        assertEquals(50.0, GrowthSweep(percent = 50.0, reduceMotion = true).settled)
    }

    @Test
    fun `reduced motion skips the animation and only the animation`() {
        assertTrue(GrowthSweep(percent = 50.0, reduceMotion = false).animates)
        assertFalse(GrowthSweep(percent = 50.0, reduceMotion = true).animates)
    }

    @Test
    fun `the sweep replays from 0 percent, not from wherever the bar was`() {
        // `[{ width: "0%" }, { width: pct% }]` — an entrance, not a delta (that
        // is the shop meter's job, and it is a different animation).
        assertEquals(0.0, GrowthSweep(percent = 90.0, reduceMotion = false).from)
    }

    @Test
    fun `900 ms on cubic-bezier point 2 point 9 point 3 1`() {
        assertEquals(900, DashboardMetrics.SWEEP_DURATION_MS)
        assertEquals(0.2f, DashboardMetrics.SWEEP_CURVE.a)
        assertEquals(0.9f, DashboardMetrics.SWEEP_CURVE.b)
        assertEquals(0.3f, DashboardMetrics.SWEEP_CURVE.c)
        assertEquals(1f, DashboardMetrics.SWEEP_CURVE.d)
    }

    @Test
    fun `the track is a fraction 0 to 1 of the settled width, over one interval`() {
        // Two keyframes, so the whole 900 ms is one eased interval — the WAAPI
        // list has no intermediate offset either.
        assertEquals(listOf(0f, 1f), DashboardMetrics.SWEEP.values)
        assertEquals(listOf(0f, 1f), DashboardMetrics.SWEEP.keyTimes)
        assertEquals(0f, DashboardMetrics.SWEEP.start)
        assertEquals(1f, DashboardMetrics.SWEEP.target)
        assertEquals(1, DashboardMetrics.SWEEP.segments().size)
        assertEquals(900, DashboardMetrics.SWEEP.segments()[0].durationMillis)
    }
}

// --- invariant 9 --------------------------------------------------------------------

class DashboardBalanceTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `the displayed number is the merge fold over per-device counters`() {
        // Two devices each earned; one spent. The dashboard shows the fold, and
        // nowhere in the persisted shape is that number written down.
        val kv = InMemoryKVStore()
        val store = ProfileStore(
            kv = kv,
            difficultyOf = ::exerciseDifficulty,
            time = MutableTimeSource(t0),
            device = { "phone" },
        )
        store.createChild("Léa")
        val id = store.activeId!!

        // Reach the counters through the store's own save path, so the test
        // reads back what the app would read.
        val roster = store.roster
        val seeded = roster.copy(
            children = roster.children.map { child ->
                child.copy(
                    profile = child.profile.copy(
                        stars = StarCounters(
                            earned = mapOf("phone" to 30, "tablet" to 12),
                            spent = mapOf("phone" to 7),
                        ),
                    ),
                )
            },
        )
        ProfileStorage.saveRoster(seeded, kv)

        val reloaded = ProfileStore(
            kv = kv,
            difficultyOf = ::exerciseDifficulty,
            time = MutableTimeSource(t0),
            device = { "phone" },
        )
        reloaded.selectChild(id)

        assertEquals(30 + 12 - 7, reloaded.profile.balance)
        assertEquals("Tu as 35 étoiles", Copy.Dashboard.balance(reloaded.profile.balance))
    }

    @Test
    fun `PersistedProfile has no balance field to persist`() {
        // Java reflection, not `KClass.members`: kotlin-reflect is not on the
        // test classpath and adding it to prove one field name would be a poor
        // trade. `declaredFields` is enough — a data class's constructor
        // properties are backing fields.
        val fields = PersistedProfile::class.java.declaredFields.map { it.name }
        assertFalse("balance" in fields, "PersistedProfile must not carry a total")
        assertTrue("stars" in fields)
    }

    @Test
    fun `the balance label pluralises on more than 1, so zero takes the singular`() {
        // `aria-label={`Tu as ${balance} ${balance > 1 ? "étoiles" : "étoile"}`}`.
        assertEquals("Tu as 0 étoile", Copy.Dashboard.balance(0))
        assertEquals("Tu as 1 étoile", Copy.Dashboard.balance(1))
        assertEquals("Tu as 2 étoiles", Copy.Dashboard.balance(2))
        assertEquals("Tu as 35 étoiles", Copy.Dashboard.balance(35))
    }

    @Test
    fun `the screen's fixed copy is the TSX's`() {
        assertEquals("← Menu", Copy.Dashboard.BACK_TO_MENU_LABEL)
        assertEquals("Retour au menu", Copy.Dashboard.BACK_TO_MENU)
        assertEquals("Mon copain", Copy.Dashboard.HEADING)
        assertEquals("⭐", Copy.Dashboard.BALANCE_GLYPH)
        assertEquals("étoiles à dépenser", Copy.Dashboard.BALANCE_CAPTION)
        assertEquals("🌱 Croissance", Copy.Dashboard.GROWTH)
        assertEquals("Croissance de ton copain", Copy.Dashboard.GROWTH_BAR)
        assertEquals("Boutique 🛍️", Copy.Dashboard.SHOP_DOOR)
        assertEquals("Changer de copain 🔄", Copy.Dashboard.SWITCH_COMPANION)
    }
}

// --- authored metrics ------------------------------------------------------------------

class DashboardMetricsTest {

    @Test
    fun `the stage px-5 pb-10 pt-6, gap-5, rounded-3xl, min-h 620`() {
        assertEquals(20.dp, DashboardMetrics.STAGE_PADDING_X)
        assertEquals(24.dp, DashboardMetrics.STAGE_PADDING_TOP)
        assertEquals(40.dp, DashboardMetrics.STAGE_PADDING_BOTTOM)
        assertEquals(20.dp, DashboardMetrics.STAGE_GAP)
        assertEquals(24.dp, DashboardMetrics.CORNER_RADIUS)
        assertEquals(620.dp, Shell.minimumScreenHeight)
    }

    @Test
    fun `the header px-4 py-2 on the back button and an 84 dp counterweight`() {
        assertEquals(16.dp, DashboardMetrics.BACK_PADDING_X)
        assertEquals(8.dp, DashboardMetrics.BACK_PADDING_Y)
        assertEquals(84.dp, DashboardMetrics.HEADER_SPACER)
    }

    @Test
    fun `the balance pill's three clamps and its two shadows`() {
        assertEquals(FluidSpec(8f, 2.6f, 15f), DashboardMetrics.BALANCE_PADDING_Y)
        assertEquals(FluidSpec(22f, 6.5f, 36f), DashboardMetrics.BALANCE_PADDING_X)
        assertEquals(FluidSpec(34f, 11f, 62f), DashboardMetrics.BALANCE_FONT_SIZE)
        assertEquals(8.dp, DashboardMetrics.BALANCE_GAP)
        // `0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)`.
        assertEquals(8.dp, DashboardMetrics.BALANCE_LIP_DROP)
        assertEquals(
            CssShadow(y = 16.dp, blur = 26.dp, opacity = 0.2f),
            DashboardMetrics.BALANCE_SOFT_SHADOW,
        )
        assertEquals(12.dp, DashboardMetrics.CAPTION_TOP_INSET)
    }

    @Test
    fun `the balance clamps resolve on a phone and on a tablet`() {
        // 390 dp phone: 11vw = 42.9 -> inside the 34..62 band.
        near(DashboardMetrics.BALANCE_FONT_SIZE.resolve(390f), 42.9f, "390 dp")
        // 820 dp tablet: 90.2 -> capped at 62.
        near(DashboardMetrics.BALANCE_FONT_SIZE.resolve(820f), 62f, "820 dp")
        // 280 dp: 30.8 -> floored at 34.
        near(DashboardMetrics.BALANCE_FONT_SIZE.resolve(280f), 34f, "280 dp")
    }

    @Test
    fun `the growth card max-w 420, p-4, mb-2, h-5`() {
        assertEquals(420.dp, DashboardMetrics.CARD_MAX_WIDTH)
        assertEquals(16.dp, DashboardMetrics.CARD_PADDING)
        assertEquals(8.dp, DashboardMetrics.CARD_HEADER_SPACING)
        assertEquals(20.dp, DashboardMetrics.BAR_HEIGHT)
    }

    @Test
    fun `the two doors px-8, py-4 and py-3, and two different lips`() {
        assertEquals(4.dp, DashboardMetrics.SHOP_TOP_INSET)
        assertEquals(32.dp, DashboardMetrics.DOOR_PADDING_X)
        assertEquals(16.dp, DashboardMetrics.SHOP_PADDING_Y)
        assertEquals(12.dp, DashboardMetrics.SWITCH_PADDING_Y)
        // `0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)` on Boutique…
        assertEquals(8.dp, DashboardMetrics.SHOP_LIP_DROP)
        assertEquals(
            CssShadow(y = 14.dp, blur = 24.dp, opacity = 0.2f),
            DashboardMetrics.SHOP_SOFT_SHADOW,
        )
        // …and `0 5px 0 rgba(0,0,0,0.08)` on Changer de copain: a lip, no blur.
        assertEquals(5.dp, DashboardMetrics.SWITCH_LIP.y)
        assertEquals(0.dp, DashboardMetrics.SWITCH_LIP.blur)
        assertEquals(0.08f, DashboardMetrics.SWITCH_LIP.opacity)
    }

    @Test
    fun `the play-surface wash, not the adult one — the cream stops at 38 percent`() {
        // `linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)`.
        // Onboarding / WhoIsPlaying / the shop stop it at 40 %; the two
        // constants are load-bearing and must not be unified.
        assertEquals(180f, Palette.stage.degrees)
        assertEquals(listOf("#FFE7C9", "#FFEFD6", "#DCEFFB"), Palette.stage.stops.map { it.hex })
        assertEquals(0.38f, Palette.stage.stops[1].location)
        assertNotEquals(Palette.stageAdult.stops[1].location, Palette.stage.stops[1].location)
    }

    @Test
    fun `the bar's two colours are the authored ones`() {
        // track `#E9DCC7`, fill `linear-gradient(90deg,#AED581,#66BB6A)`.
        assertEquals("#E9DCC7", Palette.growthTrack.hex)
        assertEquals(90f, Palette.growthFill.degrees)
        assertEquals(listOf("#AED581", "#66BB6A"), Palette.growthFill.stops.map { it.hex })
    }

    @Test
    fun `the pedestal wash is the authored radial gradient`() {
        // `radial-gradient(circle at 50% 42%, #FFFFFF 0%,
        //  rgba(255,255,255,0.4) 55%, rgba(255,255,255,0) 72%)`.
        assertEquals(0.5f, DashboardMetrics.PEDESTAL_CENTER.x)
        assertEquals(0.42f, DashboardMetrics.PEDESTAL_CENTER.y)
        assertEquals(
            listOf(0f to 1f, 0.55f to 0.4f, 0.72f to 0f),
            DashboardMetrics.PEDESTAL_STOPS,
        )
    }
}

// --- source scan ---------------------------------------------------------------------

/** The :ui main tree, found by walking up from the test's working directory. */
private fun dashboardSourceFile(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui/screens/Dashboard.kt"
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

/**
 * Lines with comments stripped, so a scan matches CODE and not the paragraph
 * that explains why the code does not do that.
 */
private fun dashboardCode(): String {
    val file = dashboardSourceFile() ?: return ""
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

class DashboardSourceScanTest {

    @Test
    fun `the scan can find the file it is meant to scan`() {
        assertTrue(dashboardSourceFile()?.isFile == true, "Dashboard.kt was not found")
    }

    /**
     * INVARIANT 9, and invariant 8's other half. The dashboard is the screen
     * that SHOWS a total, so it is the one most likely to be "optimised" into
     * keeping one.
     */
    @Test
    fun `the dashboard stores no total and mints no point`() {
        val code = dashboardCode()
        for (forbidden in listOf(
            "award(", "spend(", ".buy(", "setConfig(", "saveRoster", "sessionReward",
            "balance +", "+= ", "var balance",
        )) {
            assertFalse(
                code.contains(forbidden),
                "Dashboard.kt contains \"$forbidden\"; the balance is a read, never a write",
            )
        }
        // …and it really does read the fold.
        assertTrue(code.contains("profiles.profile"), "the balance must come from the store's fold")
    }

    /** INVARIANT 5's sibling: nothing on this screen may be gated on progress. */
    @Test
    fun `neither door is ever locked`() {
        val code = dashboardCode()
        for (forbidden in listOf("locked", "unlocked", "isEnabled")) {
            assertFalse(
                code.contains(forbidden),
                "Dashboard.kt names \"$forbidden\"; every door is always open",
            )
        }
    }

    /** INVARIANT 1 / A12: `Modifier.clickable` is banned module-wide. */
    @Test
    fun `the dashboard never reaches for Modifier clickable`() {
        assertFalse(dashboardCode().contains("clickable("), "clickable fires on UP, behind a ripple")
    }

    /**
     * INVARIANT 2. The sweep is a draw-phase read of an `Animatable`, not an
     * animated layout: no `animateDpAsState`, no `animateFloatAsState`, and no
     * `.value` read outside a draw lambda.
     */
    @Test
    fun `the sweep never goes through the render path`() {
        val code = dashboardCode()
        for (forbidden in listOf("animateDpAsState", "animateFloatAsState", "asState()")) {
            assertFalse(code.contains(forbidden), "Dashboard.kt uses $forbidden — invariant 2")
        }
        assertTrue(code.contains("drawBehind"), "the fill is painted, not laid out")
    }

    /**
     * INVARIANT 10's neighbourhood. The dashboard shows a child's progress and
     * has no reason to name a transport or a logger.
     */
    @Test
    fun `the dashboard names no telemetry, no transport and no logger`() {
        val code = dashboardCode()
        for (forbidden in listOf("Telemetry", "track(", "SyncClient", "println(", "Log.")) {
            assertFalse(code.contains(forbidden), "Dashboard.kt names \"$forbidden\"")
        }
    }
}
