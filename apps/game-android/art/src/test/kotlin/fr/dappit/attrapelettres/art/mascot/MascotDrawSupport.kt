package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPaintKind
import fr.dappit.attrapelettres.art.svg.SvgPath
import fr.dappit.attrapelettres.art.svg.SvgPathCommand

// Shared inspection helpers for the mascot suites.
//
// Everything the rig and the part library produce is plain data — a list of
// fills and strokes carrying command lists, transforms, clips and paints — so a
// test can look at it directly. These are the four or five shapes of question
// that come up repeatedly; they exist so an assertion reads as the claim it is
// making and not as a tree walk.

/** Record one draw function into a fresh canvas and hand back its nodes. */
internal fun record(body: (SvgCanvas) -> Unit): List<SvgDrawNode> {
    val canvas = SvgCanvas()
    body(canvas)
    return canvas.nodes
}

/**
 * Flatten to the leaves that actually put ink down, in painter's order.
 *
 * A mask contributes its matte before its content, which is the order they were
 * recorded in and the order they are replayed in.
 */
internal fun leaves(nodes: List<SvgDrawNode>): List<SvgDrawNode> = nodes.flatMap { node ->
    when (node) {
        is SvgDrawNode.Fill -> listOf(node)
        is SvgDrawNode.Stroke -> listOf(node)
        is SvgDrawNode.Group -> leaves(node.children)
        is SvgDrawNode.Mask -> leaves(node.matte) + leaves(node.content)
    }
}

internal fun fillsOf(nodes: List<SvgDrawNode>): List<SvgDrawNode.Fill> =
    leaves(nodes).filterIsInstance<SvgDrawNode.Fill>()

internal fun strokesOf(nodes: List<SvgDrawNode>): List<SvgDrawNode.Stroke> =
    leaves(nodes).filterIsInstance<SvgDrawNode.Stroke>()

/** Is there a `<mask>` anywhere in this list? The rainbow overlay is the only one. */
internal fun hasMask(nodes: List<SvgDrawNode>): Boolean = nodes.any { node ->
    when (node) {
        is SvgDrawNode.Mask -> true
        is SvgDrawNode.Group -> hasMask(node.children)
        else -> false
    }
}

internal fun masksOf(nodes: List<SvgDrawNode>): List<SvgDrawNode.Mask> = nodes.flatMap { node ->
    when (node) {
        is SvgDrawNode.Mask -> listOf(node)
        is SvgDrawNode.Group -> masksOf(node.children)
        else -> emptyList()
    }
}

/** The stops of a gradient paint, or an empty list for a solid or none. */
internal fun stopsOf(paint: SvgPaint): List<Pair<Float, Float>> = when (val kind = paint.kind) {
    is SvgPaintKind.Linear -> kind.stops.map { it.offset to it.color.alpha }
    is SvgPaintKind.Radial -> kind.stops.map { it.offset to it.color.alpha }
    else -> emptyList()
}

/** The parsed form of a `d` string, for comparing against a recorded path. */
internal fun commands(d: String): List<SvgPathCommand> = SvgPath.parse(d)

/** Every coordinate in a command list, for finiteness checks. */
internal fun coordinates(commands: List<SvgPathCommand>): List<Float> = commands.flatMap { command ->
    when (command) {
        is SvgPathCommand.MoveTo -> listOf(command.x, command.y)
        is SvgPathCommand.LineTo -> listOf(command.x, command.y)
        is SvgPathCommand.QuadTo -> listOf(command.x1, command.y1, command.x, command.y)
        is SvgPathCommand.CubicTo ->
            listOf(command.x1, command.y1, command.x2, command.y2, command.x, command.y)

        is SvgPathCommand.ArcTo -> listOf(command.rx, command.ry, command.x, command.y)
        SvgPathCommand.Close -> emptyList()
    }
}
