package fr.dappit.attrapelettres.art.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import fr.dappit.attrapelettres.art.svg.IconText
import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgColor
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgTextMetrics
import fr.dappit.attrapelettres.art.svg.drawSvg
import fr.dappit.attrapelettres.art.svg.toComposeColor
import fr.dappit.attrapelettres.art.svg.toComposePath
import fr.dappit.attrapelettres.art.svg.toMatrix
import fr.dappit.attrapelettres.core.domain.ExerciseId

// ```tsx
// export function ExerciseIcon({ id, size = 30 }: { id: ExerciseId; size?: number }) {
//   const { tint, glyph } = GLYPHS[id];
//   return (
//     <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden style={{ display: "block" }}>
//       <rect x={2} y={2} width={28} height={28} rx={8.5} fill={tint} />
//       {glyph}
//     </svg>
//   );
// }
// ```
//
// One canvas for the whole icon, so the badge, the pictogram and the `<text>`
// glyphs share one CTM and cannot scale apart: the 32-unit viewBox is mapped
// once and the font size is expressed in those same units.
//
// `aria-hidden` becomes `clearAndSetSemantics {}`. The hub names every game in
// text beside the icon; announcing "A" twice would be noise.

/** `viewBox="0 0 32 32"`. */
private val VIEW_BOX = SvgRect(0.0, 0.0, 32.0, 32.0)

/** `<rect x={2} y={2} width={28} height={28} rx={8.5} />`, as neutral commands. */
private val BADGE = SvgShapes.roundedRect(2.0, 2.0, 28.0, 28.0, cornerRadius = 8.5)

@Composable
fun ExerciseIcon(id: ExerciseId, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val spec = remember(id) { exerciseIconSpec(id) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        // `this.size` explicitly: the DrawScope's pixel size, not the Dp
        // parameter this lambda also captures under the same name.
        val viewBox = SvgCanvas.viewBoxTransform(
            VIEW_BOX,
            SvgRect(0.0, 0.0, this.size.width.toDouble(), this.size.height.toDouble()),
        )
        withTransform({ transform(viewBox.toMatrix()) }) {
            drawPath(BADGE.toComposePath(), SvgColor.hex(spec.tint).toComposeColor())
            for (node in spec.nodes) {
                when (node) {
                    is IconNode.Shapes -> drawSvg(node.nodes)
                    is IconNode.Text -> drawIconText(measurer, node.text)
                }
            }
        }
    }
}

// --- The <text> adapter ------------------------------------------------------
//
// The thin Compose half of `svg/SvgText.kt` — the only code that touches fonts,
// and deliberately unreachable from a host test (TextMeasurer needs the Android
// text stack). Everything it computes flows through [SvgTextMetrics], which IS
// host-tested, so what remains here is only plumbing:
//
//   - The layout is measured at `Density(1, 1)`, which makes one layout pixel
//     equal one icon unit: the glyph then rides the SAME CTM as the paths and
//     cannot scale apart from them, exactly as one `<svg>` element behaves. It
//     also keeps the user's font-scale setting out of a decorative pictogram —
//     the web icon does not grow with the browser's text zoom either.
//   - Metrics come off the first line as floats (`firstBaseline`, line bottom),
//     the same ascent/descent convention SvgTextMetrics documents.
//   - The font: the TSX asks for `ui-rounded,'SF Pro Rounded',system-ui,...`,
//     which Android resolves to Roboto via `system-ui` — so the faithful port
//     IS the platform sans, at `fontWeight={900}`.

private val UNIT_DENSITY = Density(density = 1f, fontScale = 1f)

private fun DrawScope.drawIconText(measurer: TextMeasurer, text: IconText) {
    val layout = measurer.measure(
        text = AnnotatedString(text.string),
        style = TextStyle(
            color = SvgColor.hex(text.fill).toComposeColor(),
            fontSize = text.size.sp,
            fontWeight = FontWeight(text.weight),
            fontFamily = FontFamily.SansSerif,
        ),
        density = UNIT_DENSITY,
    )
    // Line metrics as floats — `layout.size` is an IntSize and its ceil-rounding
    // would off-centre a 17-unit glyph by a visible fraction of a unit.
    val width = layout.getLineRight(0) - layout.getLineLeft(0)
    val ascent = layout.firstBaseline
    val descent = layout.getLineBottom(0) - layout.firstBaseline
    val metrics = SvgTextMetrics(ascent = ascent.toDouble(), descent = descent.toDouble())
    val topLeft = metrics.topLeft(x = text.x, y = text.y, width = width.toDouble())
    drawText(layout, topLeft = Offset(topLeft.x, topLeft.y))
}
