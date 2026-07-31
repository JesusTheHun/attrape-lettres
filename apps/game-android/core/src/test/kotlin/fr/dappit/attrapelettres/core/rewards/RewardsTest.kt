package fr.dappit.attrapelettres.core.rewards

import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A line-for-line port of apps/game-web/src/rewards.test.ts. The web app is the
 * source of truth, so these assertions are copied, not re-derived — if one of
 * them ever has to change here alone, the two apps have drifted and that is the
 * finding.
 */
class RewardsTest {

    @Test
    fun `ledgerKey joins exercise and level`() {
        assertEquals("first-letter:3", ledgerKey(ExerciseId.FIRST_LETTER, 3))
    }

    @Test
    fun `rewardFor follows the curve for the first clears`() {
        assertEquals(
            REWARD_CURVE.toList(),
            REWARD_CURVE.indices.map { rewardFor(it) },
        )
    }

    @Test
    fun `rewardFor decays to the floor past the curve`() {
        assertEquals(REWARD_FLOOR, rewardFor(REWARD_CURVE.size))
        assertEquals(REWARD_FLOOR, rewardFor(999))
    }

    @Test
    fun `previewReward returns the first-clear jackpot for an untouched level`() {
        assertEquals(REWARD_CURVE[0], previewReward(emptyMap(), ExerciseId.READ_IMAGE, 1))
    }

    @Test
    fun `previewReward reflects prior clears from the ledger`() {
        val ledger = mapOf(ledgerKey(ExerciseId.READ_IMAGE, 1) to 2)
        assertEquals(REWARD_CURVE[2], previewReward(ledger, ExerciseId.READ_IMAGE, 1))
    }

    @Test
    fun `previewReward promises a training exercise the same curve as any other row`() {
        assertEquals(REWARD_CURVE[0], previewReward(emptyMap(), ExerciseId.FIRST_LETTER, 1))
    }

    @Test
    fun `training exercises pay the curve, and never a bonus`() {
        assertEquals(REWARD_CURVE[0], sessionReward(Difficulty.TRAINING, 0, 10, 10))
        assertEquals(REWARD_FLOOR, sessionReward(Difficulty.TRAINING, 999, 0, 10))
    }

    @Test
    fun `on a training row, a full-perfect run is worth exactly what spam is`() {
        // The whole point of difficulty 0: finishing pays, accuracy does not, so
        // there is nothing on a training row worth grinding for.
        assertEquals(
            sessionReward(Difficulty.TRAINING, 0, 0, 10),
            sessionReward(Difficulty.TRAINING, 0, 10, 10),
        )
    }

    @Test
    fun `a full-perfect run earns exactly difficulty bonus points`() {
        assertEquals(REWARD_CURVE[0] + 1, sessionReward(Difficulty(1), 0, 10, 10))
        assertEquals(REWARD_CURVE[0] + 4, sessionReward(Difficulty(4), 0, 8, 8))
    }

    @Test
    fun `spam-tapping earns the bare curve, no bonus`() {
        assertEquals(REWARD_CURVE[0], sessionReward(Difficulty(4), 0, 0, 10))
        assertEquals(REWARD_FLOOR, sessionReward(Difficulty(4), 999, 0, 10))
    }

    @Test
    fun `partial accuracy scales the bonus down, floored`() {
        // difficulty 2, 5/10 perfect -> floor(5*2/10) = 1 bonus point.
        assertEquals(REWARD_FLOOR + 1, sessionReward(Difficulty(2), 999, 5, 10))
        // difficulty 1, 5/10 perfect -> floor(5/10) = 0: half-careful play on an
        // easy exercise is worth no more than spam.
        assertEquals(REWARD_FLOOR, sessionReward(Difficulty(1), 999, 5, 10))
    }

    @Test
    fun `careful play on a hard exercise beats farming an easy one`() {
        val farmEasy = sessionReward(Difficulty(1), 999, 0, 10) // spam a cheap level forever
        val playHard = sessionReward(Difficulty(4), 999, 10, 10) // first-try a mêlées run
        assertTrue(playHard > farmEasy * 4, "$playHard should beat 4x $farmEasy")
    }

    @Test
    fun `tolerates an empty session without dividing by zero`() {
        assertEquals(REWARD_CURVE[0], sessionReward(Difficulty(3), 0, 0, 0))
    }

    @Test
    fun `exercise wire values are unique and stable`() {
        // ledgerKey embeds these strings in stored, synced data. A duplicate
        // would merge two exercises' histories into one.
        val wires = ExerciseId.entries.map(ExerciseId::wire)
        assertEquals(wires.size, wires.toSet().size)
        assertEquals(ExerciseId.SPELL_TWO_SYLLABLES_MIXED, ExerciseId.fromWire("spell-two-syllables-mixed"))
    }
}
