package fr.dappit.attrapelettres.ui.design

import fr.dappit.attrapelettres.ui.components.codeLines
import fr.dappit.attrapelettres.ui.components.uiMainOrNull
import fr.dappit.attrapelettres.ui.components.uiSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// `Modifier.alpha` installs a CLIPPING layer for any alpha below 1, and CSS
// `opacity` clips nothing. Every fade in this app is a ported CSS `opacity`, so
// the stock modifier is wrong at every single call site — not just at the one
// where it was caught.
//
// It was caught on a Tile: `disabled` is true during the celebration window, so
// the tile that had just been answered correctly was faded and highlighted at
// the same instant, and the clip ate its ring down to four green corner
// slivers. Nothing on the host could see it; it took an emulator and a pixel
// scan. Hence a source scan — the same tool, and the same reason, as
// `TouchDownSourceScanTest`: the defect is in which modifier was typed, and
// that much IS visible without rasterising.

class OpacitySourceScanTest {

    @Test
    fun `ui never calls Modifier alpha`() {
        assertTrue(uiMainOrNull != null, "could not find :ui main sources")
        val offenders = uiSources()
            .filter { file -> codeLines(file).any { it.contains(".alpha(") } }
            .map { it.name }
            .sorted()
        assertEquals(emptyList(), offenders, "use Modifier.opacity, not Modifier.alpha, in $offenders")
    }

    @Test
    fun `ui never imports the stock alpha modifier`() {
        val offenders = uiSources()
            .filter { it.readText().contains("androidx.compose.ui.draw.alpha") }
            .map { it.name }
            .sorted()
        assertEquals(emptyList(), offenders, "stray import of the clipping alpha in $offenders")
    }

    @Test
    fun `opacity is a no-op at full opacity and a non-clipping layer below it`() {
        // The `>= 1f` short-circuit is the one behaviour of the stock modifier
        // worth keeping: a fully opaque node should cost no layer at all.
        val modifier = androidx.compose.ui.Modifier
        assertEquals(modifier, modifier.opacity(1f))
        assertEquals(modifier, modifier.opacity(1.5f))
        assertTrue(modifier.opacity(0.4f) !== modifier)

        // And the layer it does install must not clip — `clip` is left at the
        // GraphicsLayerScope default rather than set, which is what separates
        // this from `Modifier.alpha`.
        val source = uiMainOrNull?.resolve("design/Opacity.kt")?.readText().orEmpty()
        assertTrue(source.isNotEmpty(), "Opacity.kt not found")
        assertTrue("graphicsLayer" in source, "opacity must go through graphicsLayer")
        assertTrue(
            codeLines(uiMainOrNull!!.resolve("design/Opacity.kt")).none { "clip" in it },
            "opacity must never set clip",
        )
    }
}
