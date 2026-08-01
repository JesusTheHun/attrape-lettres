package fr.dappit.attrapelettres.ui.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import java.lang.reflect.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Invariant 2's proving test, named in ARCHITECTURE.md section 3 row 2: "no
// animation target is hoisted state".
//
// A host test cannot run a composition, so it cannot watch for a recomposition
// that should not have happened. What it CAN do is assert the two structural
// facts that make the recomposition impossible in the first place:
//
//   1. every animated channel is an `Animatable`, and no motion holder owns a
//      Compose `State` that a composable body could read;
//   2. every apply-the-motion modifier is a `graphicsLayer`, whose lambda is
//      where the `.value` read happens — a deferred read that re-layers without
//      invalidating the composition.
//
// A refactor to `animateFloatAsState` or to `var scale by remember { mutableStateOf }`
// would look identical on a fast phone and would break both.

private fun fieldsOf(type: Class<*>): List<Field> = type.declaredFields.toList()

private fun assertNoHoistedState(type: Class<*>) {
    for (field in fieldsOf(type)) {
        assertTrue(
            !State::class.java.isAssignableFrom(field.type),
            "${type.simpleName}.${field.name} is a Compose State — an animated value read from " +
                "the composition recomposes every frame (invariant 2)",
        )
    }
}

private fun assertAnimatableChannel(type: Class<*>, name: String) {
    val field = fieldsOf(type).single { it.name == name }
    assertTrue(
        Animatable::class.java.isAssignableFrom(field.type),
        "${type.simpleName}.$name must be an Animatable, was ${field.type.simpleName}",
    )
}

class AnimationSurfaceTest {

    private val moving = FixedReduceMotion(false)

    @Test
    fun `no motion holder owns hoisted Compose state`() {
        assertNoHoistedState(TileMotion::class.java)
        assertNoHoistedState(PopMotion::class.java)
        assertNoHoistedState(PulseMotion::class.java)
    }

    @Test
    fun `every animated channel is an Animatable`() {
        assertAnimatableChannel(TileMotion::class.java, "scale")
        assertAnimatableChannel(TileMotion::class.java, "shiftDp")
        assertAnimatableChannel(PopMotion::class.java, "scale")
        assertAnimatableChannel(PopMotion::class.java, "alpha")
        assertAnimatableChannel(PulseMotion::class.java, "alpha")
    }

    @Test
    fun `the channels rest at the un-animated value`() {
        // A tile is not squished and not offset until a finger lands; the pop
        // starts at the element's natural state because WAAPI's default
        // `fill: none` leaves it there until the animation actually begins.
        val tile = TileMotion(inertScope())
        assertEquals(1f, tile.scale.value)
        assertEquals(0f, tile.shiftDp.value)

        val pop = PopMotion(inertScope(), moving)
        assertEquals(1f, pop.scale.value)
        assertEquals(1f, pop.alpha.value)

        val pulse = PulseMotion(inertScope(), moving)
        assertEquals(Anim.PULSE_BASE_ALPHA, pulse.alpha.value)
        assertEquals(Anim.PULSE_BASE_ALPHA, pulse.baseAlpha)
    }

    @Test
    fun `each apply-the-motion modifier is one graphicsLayer and nothing else`() {
        // The mechanism, asserted: a graphics-layer modifier's lambda is where
        // the deferred read lives. A `Modifier.scale(...)` / `Modifier.alpha(...)`
        // built from a composable-read value would be a different element here
        // AND would need a recomposition to change.
        val chains = listOf(
            Modifier.tileMotion(TileMotion(inertScope())),
            Modifier.popFlourish(PopMotion(inertScope(), moving)),
            Modifier.pulseAlpha(PulseMotion(inertScope(), moving)),
        )
        for (chain in chains) {
            var count = 0
            chain.foldIn(Unit) { _, _ -> count += 1 }
            assertEquals(1, count, "expected exactly one modifier element")
            val name = chain.foldIn("") { _, element -> element.javaClass.simpleName }
            assertTrue(
                name.contains("GraphicsLayer", ignoreCase = true),
                "expected a graphicsLayer element, got $name — invariant 2's mechanism is gone",
            )
        }
    }

    @Test
    fun `the shake is authored in dp, not raw pixels`() {
        // `translateX(-8px)` in CSS is 8 CSS pixels, and a CSS pixel is a dp.
        // Writing 8 straight into `translationX` would make the wobble three
        // times wider on a 3x phone than on the web, which is why the channel
        // is named for its unit and the layer converts.
        assertTrue(fieldsOf(TileMotion::class.java).any { it.name == "shiftDp" })
        assertEquals(-8f, Anim.SHAKE.values[1])
        assertEquals(8f, Anim.SHAKE.values[2])
    }
}
