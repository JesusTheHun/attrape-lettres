package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.Copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The adult door, tested as data. Nothing here composes (A11).
//
// The claims worth holding still are legal before they are behavioural: Kids
// Category 1.3 says a purchase may not sit in front of a child, and this gate is
// the whole of the mechanism that keeps it from doing so. So the tests below
// pin the challenge's difficulty, its re-roll, and — as a source scan — the fact
// that the gate holds no licence state at all.

class GateChallengeTest {

    @Test
    fun `the authored range is 3 to 9, i e lowest 3 over a spread of 7`() {
        assertEquals(3, GateChallenge.LOWEST_OPERAND)
        assertEquals(7, GateChallenge.OPERAND_SPREAD)
    }

    @Test
    fun `every seeded roll stays inside 3 to 9 on both operands`() {
        for (seed in 0L until 500L) {
            val c = GateChallenge.roll(SeededGenerator(seed))
            assertTrue(c.a in 3..9, "a = ${c.a} for seed $seed")
            assertTrue(c.b in 3..9, "b = ${c.b} for seed $seed")
        }
    }

    @Test
    fun `the whole of 3 to 9 is reachable — the spread is 7, not something narrower`() {
        val seen = mutableSetOf<Int>()
        for (seed in 0L until 500L) {
            val c = GateChallenge.roll(SeededGenerator(seed))
            seen.add(c.a)
            seen.add(c.b)
        }
        assertEquals((3..9).toSet(), seen)
    }

    @Test
    fun `the answer is the product, and never a trivial times one or two`() {
        for (seed in 0L until 200L) {
            val c = GateChallenge.roll(SeededGenerator(seed))
            assertEquals(c.a * c.b, c.answer)
            // 3 x 3 is the floor. A six-year-old who can do 9 x 9 has earned it.
            assertTrue(c.answer >= 9, "trivial challenge ${c.a} x ${c.b}")
        }
    }

    @Test
    fun `the biggest possible answer still fits the field's three digits`() {
        assertEquals(81, GateChallenge(9, 9).answer)
        assertEquals("81", sanitizeGateInput("81"))
    }

    @Test
    fun `the question reads « Combien font a × b ? » with the operands in order`() {
        val c = GateChallenge(7, 4)
        assertEquals("Combien font 7 × 4 ?", c.question)
        assertEquals(Copy.ParentalGate.question(7, 4), c.question)
        // U+00D7, not the letter x, and not an ASCII asterisk.
        assertTrue(c.question.contains('×'))
        assertFalse(c.question.contains('x'))
    }

    @Test
    fun `the gate re-rolls, so a child who watches once learns nothing`() {
        // Two independent draws from the SAME seed are identical — that is what
        // makes the test deterministic — so the re-roll is proven across seeds,
        // which is what a second mount is.
        val distinct = (0L until 200L)
            .map { GateChallenge.roll(SeededGenerator(it)) }
            .toSet()
        assertTrue(distinct.size > 10, "only ${distinct.size} distinct challenges")

        // And the shipping path, which uses SystemRandomSource.
        val live = (0 until 200).map { GateChallenge.roll() }.toSet()
        assertTrue(live.size > 1, "the live roll produced one challenge 200 times")
    }
}

class GateInputTest {

    @Test
    fun `ASCII digits survive, in order`() {
        assertEquals("42", sanitizeGateInput("42"))
        assertEquals("905", sanitizeGateInput("905"))
    }

    @Test
    fun `everything that is not 0-9 is deleted`() {
        assertEquals("42", sanitizeGateInput("4 2"))
        assertEquals("42", sanitizeGateInput("-4.2"))
        assertEquals("42", sanitizeGateInput("a4b2c"))
        assertEquals("", sanitizeGateInput("abc"))
        assertEquals("", sanitizeGateInput(""))
    }

    @Test
    fun `non-ASCII digits are deleted too — the web's D-class is not Unicode-aware`() {
        // Arabic-Indic, full-width, and a superscript two. `Char.isDigit()` would
        // keep the first two and hand `toIntOrNull` a string it cannot parse.
        assertEquals("", sanitizeGateInput("٤٢"))
        assertEquals("", sanitizeGateInput("４２"))
        assertEquals("", sanitizeGateInput("²"))
    }

    @Test
    fun `a digit carrying a combining mark still yields its digit`() {
        // "3" + U+0301 is one grapheme cluster and two code units; the regex
        // deletes the mark and keeps the 3.
        assertEquals("3", sanitizeGateInput("3́"))
    }

    @Test
    fun `the field is capped at three digits, counted after the filter`() {
        assertEquals("123", sanitizeGateInput("123456"))
        assertEquals("123", sanitizeGateInput("1a2b3c4d"))
    }
}

class ParentalGateModelTest {

    private fun model(a: Int = 7, b: Int = 4) = ParentalGateModel(GateChallenge(a, b))

    @Test
    fun `« Continuer » is disabled exactly while the field is empty`() {
        val m = model()
        assertFalse(m.canSubmit)
        m.type("2")
        assertTrue(m.canSubmit)
        m.type("")
        assertFalse(m.canSubmit)
    }

    @Test
    fun `typing sanitises and clears the error line`() {
        val m = model()
        m.type("1")
        m.submit()
        assertTrue(m.wrong)
        m.type("2a8")
        assertEquals("28", m.value)
        assertFalse(m.wrong)
    }

    @Test
    fun `the right answer passes and changes nothing else`() {
        val m = model()
        m.type("28")
        assertTrue(m.submit())
        assertFalse(m.wrong)
        assertEquals("28", m.value)
    }

    @Test
    fun `leading zeros parse, exactly as JS Number of 028 does`() {
        val m = model()
        m.type("028")
        assertTrue(m.submit())
    }

    @Test
    fun `an empty field is never the answer — the product is at least nine`() {
        val m = model()
        assertFalse(m.submit())
        assertTrue(m.wrong)
    }

    @Test
    fun `a wrong answer clears the field, raises the line, and locks NOTHING`() {
        val m = model()
        m.type("12")
        assertFalse(m.submit())
        assertTrue(m.wrong)
        assertEquals("", m.value)
        // Invariant 3's spirit: no lockout counter, no cooldown, no new challenge.
        assertEquals(28, m.challenge.answer)
        assertFalse(m.canSubmit)
    }

    @Test
    fun `retries are unlimited — twenty wrong answers still leave the right one working`() {
        val m = model()
        repeat(20) {
            m.type("11")
            assertFalse(m.submit())
        }
        m.type("28")
        assertTrue(m.submit())
    }

    @Test
    fun `the challenge never changes under the adult's hands`() {
        val m = model(5, 6)
        val before = m.challenge
        m.type("7")
        m.submit()
        m.type("30")
        assertEquals(before, m.challenge)
        assertTrue(m.submit())
    }
}

class ParentalGateSourceTest {

    @Test
    fun `the scan can find the file it is meant to scan`() {
        assertNotNull(
            adultScreenSource("ParentalGate.kt"),
            "not found from ${System.getProperty("user.dir")}",
        )
    }

    /**
     * INVARIANT 11, structurally. The gate reads no licence, no store and no
     * clock, so it cannot fail to read one — a wrong sum costs one retry and
     * « Annuler » always returns the caller's own screen. It also persists
     * nothing: a "gate passed" flag would make watching once worth something.
     */
    @Test
    fun `the gate touches no storage, no licence and no entitlement`() {
        val file = assertNotNull(adultScreenSource("ParentalGate.kt"))
        val banned = listOf(
            "KVStore",
            "LicenseStore",
            "EntitlementModel",
            "entitlementOf",
            "canPlay",
            "PurchaseStore",
            "TimeSource",
            "ProfileStore",
            "Telemetry",
        )
        for ((number, line) in codeLines(file)) {
            for (word in banned) {
                assertFalse(
                    line.contains(word),
                    "ParentalGate.kt:$number reaches for $word: ${line.trim()}",
                )
            }
        }
    }

    /** A12: `Modifier.clickable` is banned module-wide; so is any other tap path. */
    @Test
    fun `every tap on the gate goes through touchDown`() {
        val file = assertNotNull(adultScreenSource("ParentalGate.kt"))
        for ((number, line) in codeLines(file)) {
            assertFalse(line.contains("clickable"), "ParentalGate.kt:$number uses clickable")
            assertFalse(line.contains("detectTapGestures"), "ParentalGate.kt:$number: $line")
        }
        assertTrue(codeLines(file).any { it.second.contains("touchDown") })
    }

    /** Play never shares an in-app purchase with a family; nothing may say it does. */
    @Test
    fun `the gate promises nothing about a family`() {
        assertNull(
            listOf(Copy.ParentalGate.PURCHASE_REASON, Copy.ParentalGate.TITLE)
                .firstOrNull { it.lowercase().contains("famille") },
        )
    }
}
