package fr.dappit.attrapelettres

import fr.dappit.attrapelettres.ui.components.ConfettiSystem
import fr.dappit.attrapelettres.ui.components.TileMetrics
import fr.dappit.attrapelettres.ui.design.Palette
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// ===========================================================================
// The launcher icon, as a contract.
//
// The mark's SOURCE is a SwiftUI view — `CoralIcon` in
// apps/game-ios/Sources/ALUI/AppIcon/AppIcon.swift — and `swift run IconForge`
// renders it straight into the iOS asset catalog and the PWA's PNGs, where a
// Swift test re-renders and compares. Nothing renders Android XML, so the three
// drawables next door are a hand transcription and there is nothing to diff
// them against.
//
// What CAN be checked without a rasteriser is every way the transcription can
// go quietly wrong, and each of these has exactly one failure mode, all of them
// invisible until an APK is on a phone:
//
//   * a palette hex moves and the icon keeps the old one — the app and its icon
//     stop being the same colour and nobody notices for a release;
//   * the tile's corner radius is retyped rather than derived, and the icon's
//     tile stops being the shape of the tiles in the game;
//   * something drifts outside the adaptive-icon safe zone and a launcher we do
//     not own crops it;
//   * the monochrome layer gets pointed back at the foreground, which is a
//     cream tile — tinted flat, that is a featureless blob with no letter in it;
//   * a PNG appears in res/ and the Android 12+ splash screen, which blows the
//     icon up to about 160 dp, goes soft.
//
// The geometry below is PARSED out of the files rather than restated here. A
// test that repeats the numbers only proves the numbers were typed twice.
// ===========================================================================
class LauncherIconContractTest {

    /** The design square `CoralIcon` is authored in, and the viewport of the drawables. */
    private val design = 1024.0

    /**
     * An adaptive icon's canvas is 108 dp, a launcher may mask everything
     * outside the centre 72, and only the centre 66 is guaranteed. In this
     * 1024-unit viewport that is a circle of radius 313.
     */
    private val safeRadius = design * (66.0 / 108.0) / 2.0

    // --- Wiring -------------------------------------------------------------

    /**
     * minSdk is 26, so `mipmap-anydpi-v26` is not a modern refinement of a PNG
     * ladder — it IS the icon, and nothing falls back to anything.
     */
    @Test
    fun theAdaptiveIconIsTheOnlyLauncherIcon() {
        val manifest = res("../AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))

        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            assertTrue(res("mipmap-anydpi-v26/$name.xml").isFile, "$name.xml is missing")
        }
    }

    /**
     * Every layer a vector. A bitmap icon is not merely bigger: the Android 12+
     * splash screen renders the app icon at roughly 160 dp, so a 108 dp raster
     * is upscaled by half again on the very first screen of the app.
     */
    @Test
    fun nothingInTheIconIsARaster() {
        val rasters = res(".").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg") }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), rasters, "res/ has rasters in it: $rasters")
    }

    /**
     * All three layers, and the monochrome one is its OWN drawable. Pointing it
     * at the foreground is the natural-looking mistake — it was the state of
     * this repo before the mark existed — and it is wrong for a reason no
     * compiler can see: the system throws the colours away and tints by alpha,
     * so a cream tile with a dark letter on it comes back as a solid blob.
     */
    @Test
    fun allThreeLayersAreDeclaredAndTheThemedOneIsASilhouette() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val xml = res("mipmap-anydpi-v26/$name.xml").readText()
            for (layer in listOf("background", "foreground", "monochrome")) {
                assertTrue(xml.contains("<$layer android:drawable="), "$name declares no <$layer>")
            }
            assertTrue(
                xml.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />"),
                "$name's themed layer must be the silhouette, not the cream tile",
            )
        }
        assertTrue(res("drawable/ic_launcher_monochrome.xml").isFile)
    }

    // --- The colours are the product's ---------------------------------------

    /**
     * `CoralIcon.field`: the pick tile's own coral at the top, the web icon's
     * deeper orange at the bottom, in that order. The first is a token, so a
     * palette change that forgets the icon fails here rather than shipping an
     * app whose icon is a colour the app no longer uses.
     */
    @Test
    fun theFieldIsTheCoralGradientTheSourceDeclares() {
        val xml = body("drawable/ic_launcher_background.xml")
        val stops = Regex("android:offset=\"([\\d.]+)\"\\s+android:color=\"(#[0-9A-Fa-f]{6})\"")
            .findAll(xml)
            .map { it.groupValues[1].toFloat() to it.groupValues[2].uppercase() }
            .toList()

        assertEquals(2, stops.size, "the field is a two-stop gradient")
        assertEquals(0f to Palette.tileColors[0].bg.hex.uppercase(), stops[0])
        assertEquals(1f to "#E8722C", stops[1])

        // 180° in the CSS the mark is authored in — top to bottom, not sideways.
        assertTrue(Regex("android:startY=\"0\"").containsMatchIn(xml))
        assertTrue(Regex("android:endY=\"108\"").containsMatchIn(xml))
    }

    /** The face is the mascot rigs' cream; the ink is the pick tile's own. */
    @Test
    fun theTileWearsTheProductsPaint() {
        val xml = body("drawable/ic_launcher_foreground.xml")
        assertTrue(xml.contains("android:fillColor=\"#FFF6EE\""), "the face is not CoralIcon.cream")
        assertTrue(
            xml.contains("android:strokeColor=\"${Palette.tileColors[0].ink.hex.uppercase()}\""),
            "the letter is not the pick tile's ink (${Palette.tileColors[0].ink.hex})",
        )
    }

    // --- The geometry is the tile's ------------------------------------------

    /**
     * The icon's tile must be the shape of the tiles in the game. `IconTile`
     * takes the radius as a RATIO — `TileMetrics.cornerRadius / defaultSize.min`
     * — so the icon follows the product; retyping 170.4 here would silently
     * decouple them the first time 28 dp moves.
     */
    @Test
    fun theTilesCornerRadiusIsTheProductsOwnRatio() {
        val (side, radius) = tileFromPath()
        val expected = side * (TileMetrics.CORNER_RADIUS.value / TileMetrics.DEFAULT_SIZE.min)
        assertTrue(
            abs(radius - expected) < 0.1,
            "the icon tile's radius is $radius; the product's ratio gives $expected at side $side",
        )
    }

    /** The two flecks `CoralIcon` keeps, in the confetti's own colours. */
    @Test
    fun theConfettiIsTheProductsOwnTwoFlecks() {
        val fills = Regex("android:fillColor=\"(#[0-9A-Fa-f]{6})\"")
            .findAll(body("drawable/ic_launcher_foreground.xml"))
            .map { it.groupValues[1].uppercase() }
            .toList()
        for (index in listOf(1, 2)) {
            assertTrue(
                ConfettiSystem.COLORS[index].uppercase() in fills,
                "IconFleck($index) is missing or is not ConfettiSystem.COLORS[$index]",
            )
        }
    }

    /**
     * Everything drawn fits, and the scale is set BY that fit rather than
     * chosen: the outermost thing in the icon — the yellow fleck — sits on the
     * guarantee, so the drawing is as large as an adaptive icon allows and not
     * one unit larger. Both halves matter. Over, and a launcher we do not own
     * crops the confetti; under, and somebody has quietly shrunk the mark.
     */
    @Test
    fun thePictureFillsTheGuaranteedSafeZoneExactly() {
        val reach = reach("drawable/ic_launcher_foreground.xml")
        assertTrue(reach <= safeRadius, "the icon reaches $reach; a launcher only guarantees $safeRadius")
        assertTrue(reach > safeRadius * 0.99, "the icon reaches only $reach of the $safeRadius available")
    }

    /**
     * The themed layer has no field of its own, so it is scaled to FILL the safe
     * zone rather than to match iOS — and must still fit inside it. Its reach is
     * the furthest stroke end plus half the stroke, which is exactly what a
     * round cap draws.
     */
    @Test
    fun theThemedLetterFillsTheSafeZoneWithoutLeavingIt() {
        val file = "drawable/ic_launcher_monochrome.xml"
        val xml = body(file)
        val scale = outerScale(file)
        val pivotY = Regex("android:pivotY=\"([\\d.]+)\"").find(xml)?.groupValues?.get(1)?.toDouble()
            ?: fail("no pivot in $file")

        val stroke = Regex("android:strokeWidth=\"([\\d.]+)\"").findAll(xml)
            .map { it.groupValues[1].toDouble() }.maxOrNull() ?: fail("no stroke in $file")

        // Every point the two paths name, at its distance from the letter's own
        // centre; the tilt spins them about that centre and cannot change it.
        val reach = Regex("([\\d.]+),([\\d.]+)").findAll(xml.substringAfter("pathData"))
            .map { hypot(it.groupValues[1].toDouble() - 512.0, it.groupValues[2].toDouble() - pivotY) }
            .max() + stroke / 2

        // The centre itself sits below the icon's, as set type does.
        val fromIconCentre = reach * scale + (pivotY - 512.0)
        assertTrue(fromIconCentre <= safeRadius, "the themed A reaches $fromIconCentre of $safeRadius")
        assertTrue(
            fromIconCentre > safeRadius * 0.8,
            "the themed A reaches only $fromIconCentre of $safeRadius — a tinted letter that " +
                "small reads as an empty circle",
        )
    }

    // --- helpers -------------------------------------------------------------

    /**
     * The tile's side and corner radius, read back out of the rounded-rect path
     * both the face and its lip are drawn with: `M x,232 … A r,r …`, in a
     * 232 … 792 box. Parsed, not restated — see the class header. The tile is
     * the LARGEST rounded rect in the file; the two flecks are the others.
     */
    private fun tileFromPath(): Pair<Double, Double> {
        val box = roundedRects("drawable/ic_launcher_foreground.xml")
            .maxByOrNull { it.halfWidth } ?: fail("no rounded-rect path in the foreground")
        return (box.halfWidth * 2) to box.radius
    }

    /** A rounded rect as this file authors them: centre, half-extents, corner radius. */
    private class Box(
        val cx: Double, val cy: Double,
        val halfWidth: Double, val halfHeight: Double,
        val radius: Double,
    ) {
        /** Its furthest point from its own centre, whatever it is rotated by. */
        val outer: Double get() = hypot(halfWidth - radius, halfHeight - radius) + radius
    }

    /**
     * Every `<path>` in a drawable, with the running `translateY` of the groups
     * it sits inside folded into its centre.
     *
     * Rotation is ignored on purpose and it is sound to ignore: every rotation
     * in these files turns a shape about either its own centre (the flecks) or
     * the icon's (the tilt), and neither moves the distance measured below.
     */
    private fun roundedRects(relative: String): List<Box> {
        val xml = body(relative)
        var offset = 0.0
        val boxes = mutableListOf<Box>()
        val depths = ArrayDeque<Double>()

        for (token in Regex("<group[^>]*>|</group>|<path[^>]*/>").findAll(xml).map { it.value }) {
            when {
                token.startsWith("</") -> offset -= depths.removeLastOrNull() ?: 0.0
                token.startsWith("<group") -> {
                    val dy = Regex("android:translateY=\"([-\\d.]+)\"")
                        .find(token)?.groupValues?.get(1)?.toDouble() ?: 0.0
                    depths.addLast(dy)
                    offset += dy
                }
                else -> {
                    val data = Regex("android:pathData=\"([^\"]*)\"").find(token)?.groupValues?.get(1) ?: continue
                    val radius = Regex("A([\\d.]+),").find(data)?.groupValues?.get(1)?.toDouble() ?: continue
                    val xs = mutableListOf<Double>()
                    val ys = mutableListOf<Double>()
                    for (m in Regex("([\\d.]+),([\\d.]+)").findAll(data.replace(Regex("A[\\d.]+,[\\d.]+ [01] [01] "), " "))) {
                        xs += m.groupValues[1].toDouble()
                        ys += m.groupValues[2].toDouble()
                    }
                    boxes += Box(
                        cx = (xs.min() + xs.max()) / 2,
                        cy = (ys.min() + ys.max()) / 2 + offset,
                        halfWidth = (xs.max() - xs.min()) / 2,
                        halfHeight = (ys.max() - ys.min()) / 2,
                        radius = radius,
                    )
                }
            }
        }
        return boxes
    }

    /**
     * How far the drawing reaches from the icon's centre, scaled: the furthest
     * of every filled rounded rect and every stroked path's round cap.
     */
    private fun reach(relative: String): Double {
        val xml = body(relative)
        val fills = roundedRects(relative).map { hypot(it.cx - 512.0, it.cy - 512.0) + it.outer }

        val strokes = Regex("<path[^>]*/>").findAll(xml).mapNotNull { path ->
            val half = (Regex("android:strokeWidth=\"([\\d.]+)\"")
                .find(path.value)?.groupValues?.get(1)?.toDouble() ?: return@mapNotNull null) / 2
            val data = Regex("android:pathData=\"([^\"]*)\"").find(path.value)?.groupValues?.get(1).orEmpty()
            Regex("([\\d.]+),([\\d.]+)").findAll(data)
                .map { hypot(it.groupValues[1].toDouble() - 512.0, it.groupValues[2].toDouble() - 512.0) }
                .max() + half
        }

        return (fills + strokes).max() * outerScale(relative)
    }

    /** The `scaleX` of a drawable's outermost group. */
    private fun outerScale(relative: String): Double =
        Regex("android:scaleX=\"([\\d.]+)\"").find(body(relative))?.groupValues?.get(1)?.toDouble()
            ?: fail("$relative has no scaled group")

    /**
     * The file with its XML comments stripped. All three drawables document
     * their own numbers at length — the safe-zone radius, the hexes they came
     * from, the shadow that had to be dropped — and a scan that read the prose
     * as markup would agree with the documentation instead of the drawing.
     */
    private fun body(relative: String): String =
        res(relative).readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** Same upward walk as `ManifestContractTest`: a contract that skips is not a contract. */
    private fun res(relative: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        for (base in generateSequence(start) { it.parentFile }.take(8)) {
            for (root in listOf(base, File(base, "apps/game-android"))) {
                if (File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory) {
                    return File(root, "app/src/main/res/$relative")
                }
            }
        }
        fail("could not locate apps/game-android walking up from $start")
    }
}
