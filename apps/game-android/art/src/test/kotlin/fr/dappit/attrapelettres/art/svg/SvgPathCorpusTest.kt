package fr.dappit.attrapelettres.art.svg

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every `d` string that exists in the web app, parsed. Port of the iOS
// `SVGPathCorpusTests.swift`, reading the SAME file it does — see
// `SvgCorpusFixture.kt` for why there is one corpus and not two.
//
// Regenerate after touching any mascot:
//   node apps/game-ios/scripts/extract-svg-corpus.mjs \
//        apps/game-ios/Tests/ALArtTests/Resources/svg-corpus.json

class SvgPathCorpusTest {

    @Test
    fun `the corpus is present and has not shrunk unnoticed`() {
        val entries = SvgCorpus.entries
        assertEquals(SvgCorpus.declaredCount, entries.size)
        // A regeneration that silently captures fewer paths is the failure mode
        // this guards: the suite would still pass on a corpus of three strings.
        assertTrue(entries.size >= 180, "corpus has ${entries.size} paths — did extraction break?")
        assertTrue(entries.any { it.kind == "template" })
        assertTrue(entries.any { it.kind == "static" })
    }

    @Test
    fun `every path string in the app parses`() {
        val failures = mutableListOf<String>()
        for (entry in SvgCorpus.entries) {
            try {
                SvgPath.parseFlattened(entry.d)
            } catch (e: SvgPathException) {
                failures.add("[${entry.file}] ${e.message} — ${entry.d.take(120)}")
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} path(s) failed:\n" + failures.joinToString("\n"),
        )
    }

    @Test
    fun `no path parses to nothing`() {
        val empty = SvgCorpus.entries
            .filter { SvgPath.parseFlattened(it.d).isEmpty() }
            .map { "[${it.file}] ${it.d.take(120)}" }
        assertTrue(empty.isEmpty(), "empty result for:\n" + empty.joinToString("\n"))
    }

    @Test
    fun `no path produces a degenerate or non-finite bounding box`() {
        val bad = mutableListOf<String>()
        for (entry in SvgCorpus.entries) {
            val box = SvgPath.bounds(SvgPath.parseFlattened(entry.d))
            if (box == null) {
                bad.add("[${entry.file}] no bounds at all — ${entry.d.take(120)}")
                continue
            }
            val finite = box.x.isFinite() && box.y.isFinite() &&
                box.width.isFinite() && box.height.isFinite()
            // A path may legitimately be a straight line (zero on one axis), but
            // never zero on both, and never NaN — NaN is what a botched arc
            // conversion produces, and it renders as nothing at all.
            if (!finite || (box.width == 0.0 && box.height == 0.0)) {
                bad.add("[${entry.file}] $box — ${entry.d.take(120)}")
            }
        }
        assertTrue(bad.isEmpty(), "suspect bounding boxes:\n" + bad.joinToString("\n"))
    }

    @Test
    fun `no path produces a non-finite coordinate`() {
        // The bounding box above would already catch a NaN, but only once it has
        // been folded into a min or a max. This looks at every number that will
        // reach a Compose Path, which is the level the renderer fails at.
        val bad = mutableListOf<String>()
        for (entry in SvgCorpus.entries) {
            for (command in SvgPath.parseFlattened(entry.d)) {
                val coordinates = when (command) {
                    is SvgPathCommand.MoveTo -> listOf(command.x, command.y)
                    is SvgPathCommand.LineTo -> listOf(command.x, command.y)
                    is SvgPathCommand.QuadTo ->
                        listOf(command.x1, command.y1, command.x, command.y)

                    is SvgPathCommand.CubicTo ->
                        listOf(command.x1, command.y1, command.x2, command.y2, command.x, command.y)

                    is SvgPathCommand.ArcTo -> listOf(command.x, command.y)
                    SvgPathCommand.Close -> emptyList()
                }
                if (coordinates.any { !it.isFinite() }) {
                    bad.add("[${entry.file}] $command — ${entry.d.take(120)}")
                }
            }
        }
        assertTrue(bad.isEmpty(), "non-finite coordinates:\n" + bad.joinToString("\n"))
    }

    @Test
    fun `flattening leaves no arc behind`() {
        // Everything downstream — the Compose adapter, the bounding boxes, the
        // transform helper — assumes `flatten` is total. It is the one assumption
        // that would fail silently, as a chord drawn where a curve belongs.
        val arcs = SvgCorpus.entries
            .filter { entry -> SvgPath.parseFlattened(entry.d).any { it is SvgPathCommand.ArcTo } }
        assertTrue(arcs.isEmpty(), "arcs survived flattening in ${arcs.map { it.file }}")
    }

    @Test
    fun `the corpus exercises every command the app actually uses`() {
        val all = SvgCorpus.entries.joinToString("") { it.d }
        // Present in the source today. If a mascot introduces S/T/h/v the parser
        // already handles them and SvgPathGrammarTest covers them; this only
        // asserts the corpus did not lose coverage of what is in use.
        for (command in "MLQZCAlqHaz") {
            assertTrue(all.contains(command), "corpus no longer contains a '$command' command")
        }
    }

    @Test
    fun `parsing is deterministic`() {
        for (entry in SvgCorpus.entries.take(40)) {
            assertEquals(
                SvgPath.parseFlattened(entry.d),
                SvgPath.parseFlattened(entry.d),
                "[${entry.file}] parsed differently twice",
            )
        }
    }

    @Test
    fun `translating the corpus moves every bounding box and changes nothing else`() {
        // `SvgPath.transform` is what resolves a clip into root space and what
        // `SvgShape` maps a viewBox with, so it is exercised on real geometry
        // rather than on a two-command toy. A translation must move the box by
        // exactly the offset and leave its size alone.
        for (entry in SvgCorpus.entries) {
            val commands = SvgPath.parseFlattened(entry.d)
            val box = SvgPath.bounds(commands) ?: continue
            val moved = SvgPath.bounds(
                SvgPath.transform(commands, SvgTransform.translate(10.0, -20.0)),
            ) ?: continue
            val tolerance = 1e-3 * maxOf(1.0, abs(box.minX) + abs(box.minY))
            assertTrue(abs(moved.minX - (box.minX + 10)) < tolerance, "[${entry.file}] $moved")
            assertTrue(abs(moved.minY - (box.minY - 20)) < tolerance, "[${entry.file}] $moved")
            assertTrue(abs(moved.width - box.width) < tolerance, "[${entry.file}] $moved")
            assertTrue(abs(moved.height - box.height) < tolerance, "[${entry.file}] $moved")
            assertEquals(commands.size, SvgPath.transform(commands, SvgTransform.Identity).size)
        }
    }

    @Test
    fun `a uniform scale scales every corpus bounding box by the same factor`() {
        for (entry in SvgCorpus.entries) {
            val commands = SvgPath.parseFlattened(entry.d)
            val box = SvgPath.bounds(commands) ?: continue
            val scaled = SvgPath.bounds(
                SvgPath.transform(commands, SvgTransform.scale(3.0, 3.0)),
            ) ?: continue
            // A float carries about seven digits, and the corpus reaches into the
            // hundreds, so the tolerance is relative to the magnitude involved.
            val tolerance = 1e-3 * maxOf(1.0, box.width + box.height)
            assertTrue(
                abs(scaled.width - box.width * 3) < tolerance &&
                    abs(scaled.height - box.height * 3) < tolerance,
                "[${entry.file}] $box scaled to $scaled",
            )
        }
    }
}
