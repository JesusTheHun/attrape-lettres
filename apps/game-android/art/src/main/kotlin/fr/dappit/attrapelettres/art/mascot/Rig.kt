package fr.dappit.attrapelettres.art.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.art.svg.drawSvg
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.Accessory
import fr.dappit.attrapelettres.core.mascot.Layout
import fr.dappit.attrapelettres.core.mascot.layoutFor
import fr.dappit.attrapelettres.core.mascot.stageScale
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource

// Port of `src/mascot/Mascot.tsx` — the shell around the species rigs.
//
// Parametric, layered mascot: species x stage x wardrobe x mood turns into a
// DRAW LIST. That resolution is arithmetic and string building, so it is pure
// Kotlin over plain data and every part of it is assertable on a laptop; the
// only thing in this file that touches Compose is `MascotRigView` at the
// bottom, which replays the finished list.
//
// What is here: the ground shadow, the feet-pivot growth scale, the species
// dispatch, the "Arc-en-ciel magique" silhouette-masked rainbow overlay and the
// five French accessibility labels (invariant 6).
//
// What is deliberately NOT here (Motion.kt): the mood animations (bob/pop/
// cheer, transform-origin 50%/82%, the reduced-motion gate) and the sheen's
// sweep. They are transforms over this static draw list — invariant 2 says they
// never reach the render path, so they never reach the code that builds
// geometry.

/** Premium accessories rendered as a WHOLE-IMAGE rainbow sheen, not a worn part. */
val RAINBOW_IDS: List<String> = listOf(Accessory.Unicorn.starClip)

// -----------------------------------------------------------------------------
// THE SPECIES CONTRACT
//
// The five functions below are declared in `Unicorn.kt`, `Cat.kt`, `Fox.kt`,
// `Rabbit.kt` and `Dragon.kt`, one per species, and this file is their only
// caller. The signature is fixed and positional:
//
//     fun drawUnicornRig(
//         c: SvgCanvas,
//         config: MascotConfig,
//         layout: Layout,
//         stage: Int,
//         mood: Mood,
//         preview: Boolean,
//     )
//
// A rig draws into the canvas it is handed, in painter's order, and returns
// nothing. It must leave the save stack exactly as it found it. `layout` is
// passed in rather than recomputed so the shadow, the anchors and the rig all
// agree on one geometry; `stage` is `config.stage` and is passed separately only
// because that is how the TSX props read.
//
// The `when` in `drawRig` has NO `else` branch, so adding a sixth species is a
// compile error here until its rig exists — the same construction invariant 7
// uses for the exercise icons.
// -----------------------------------------------------------------------------

object MascotRig {

    /** The authored coordinate space: `viewBox="0 0 100 100"`. */
    val VIEW_BOX: SvgRect = SvgRect(0.0, 0.0, 100.0, 100.0)

    /**
     * How much bigger than the mascot's nominal size the drawing scope is.
     *
     * Top-stage wings and haloes deliberately leave the 100-unit box — the web
     * sets `overflow: visible` and `SvgCanvas` never clips at the viewBox. On
     * Android the thing that WOULD clip them is not the viewBox but the layers:
     * `drawSvg` opens every `<g opacity>` and the rainbow mask with
     * `saveLayer(0, 0, scopeWidth, scopeHeight)`, so anything outside the
     * drawing scope is lost inside a group even though a plain fill would have
     * survived. Hence a scope larger than the mascot, with the 100-unit box
     * mapped into a centred inner square.
     *
     * The number comes from the measured envelope over all five species and all
     * ten stages: x in [-55.0, 155.0], y in [-80.3, 113.0]. Around the box's
     * centre that is a half-extent of 130.3 units, i.e. 2.61x the box; 2.7 is
     * that plus a little, and no more — a scope four times the mascot would
     * quadruple the cost of every group layer for nothing.
     */
    const val OVERFLOW: Float = 2.7f

    /**
     * `aria-label` per species — byte-for-byte French, hard-coded, not
     * localised (invariant 6).
     *
     * ```ts
     * const LABELS: Record<Species, string> = {
     *   unicorn: "Ma licorne",
     *   cat: "Mon chat",
     *   fox: "Mon renard",
     *   rabbit: "Mon lapin",
     *   dragon: "Mon dragon",
     * };
     * ```
     */
    val labels: Map<Species, String> by lazy { Species.entries.associateWith(::label) }

    /**
     * The accessibility label for one species.
     *
     * A `when` over the closed enum, not a map with a fallback: an empty
     * `contentDescription` is a silent accessibility regression that no
     * rendering test would notice, and the compiler is in a position to prevent
     * it. [labels] is derived from this, so the five strings exist once.
     */
    fun label(species: Species): String = when (species) {
        Species.UNICORN -> "Ma licorne"
        Species.CAT -> "Mon chat"
        Species.FOX -> "Mon renard"
        Species.RABBIT -> "Mon lapin"
        Species.DRAGON -> "Mon dragon"
    }

    /**
     * Everything `Mascot.tsx` does AROUND the species rig: the ground shadow,
     * the growth scale pivoted at the feet, and the rainbow overlay.
     *
     * Split out from [draw] so it can be tested without any species rig at all —
     * the shell owns the shadow, the pivot and the mask, and a bug in any of the
     * three is invisible in a picture full of fur. [rig] is called once when
     * there is no rainbow and TWICE when there is: once for the pet, once as the
     * mask's matte.
     */
    fun drawShell(
        c: SvgCanvas,
        layout: Layout,
        scale: Double,
        rainbow: Boolean,
        rig: (SvgCanvas) -> Unit,
    ) {
        // Ground shadow — OUTSIDE the scaled rig group. `rx` is multiplied by
        // `scale` by hand while `cy` is not, which is exactly what the TSX does:
        // the shadow widens as the pet grows but stays on the same ground line.
        c.fill(
            ellipsePath(50.0, layout.feetY + 3.5, layout.bodyRX * 0.92 * scale, 3.6),
            SvgPaint.hex("#000", opacity = 0.1),
        )

        // `transform="translate(50 pivot) scale(k) translate(-50 -pivot)"` —
        // a scale pivoted at (50, feetY), so the creature grows UPWARD off the
        // ground line instead of drifting away from it.
        fun scaledRig(target: SvgCanvas) {
            target.save()
            target.translate(50.0, layout.feetY)
            target.scale(scale)
            target.translate(-50.0, -layout.feetY)
            rig(target)
            target.restore()
        }

        // "Arc-en-ciel magique": a rainbow prism sweep MASKED to the pet's
        // silhouette, so it hugs the whole creature (wings and overflow beyond
        // the box included) and never spills onto the card or hard-cuts at the
        // viewBox edge. On the web the mask is the rig re-rendered as a flat
        // white silhouette (`brightness(0) invert(1)`); after that filter the
        // luminance mask is identical to an alpha mask, so this port draws the
        // rig itself as the matte and lets its alpha do the work.
        if (rainbow) {
            scaledRig(c)
            c.mask(matte = { m -> scaledRig(m) }, content = { body -> drawRainbowSheen(body) })
        } else {
            scaledRig(c)
        }
    }

    /** Build the mascot's full draw list into [c], in viewBox space (0..100). */
    fun draw(
        c: SvgCanvas,
        config: MascotConfig,
        mood: Mood,
        preview: Boolean = false,
    ) {
        val layout = layoutFor(config.stage.toDouble())
        // Previews pin the scale to 1 so a `focus` crop lines up with raw layout
        // coordinates — a shop tile zooming on a collar must land on the collar.
        val scale = if (preview) 1.0 else stageScale(config.stage)
        val rainbow = config.accessories.any { RAINBOW_IDS.contains(it) }
        drawShell(c, layout, scale, rainbow) { target ->
            drawRig(target, config, layout, mood, preview)
        }
    }

    /** The species dispatch — `Mascot.tsx`'s ternary chain. */
    fun drawRig(
        c: SvgCanvas,
        config: MascotConfig,
        layout: Layout,
        mood: Mood,
        preview: Boolean,
    ) {
        when (config.species) {
            Species.CAT -> drawCatRig(c, config, layout, config.stage, mood, preview)
            Species.FOX -> drawFoxRig(c, config, layout, config.stage, mood, preview)
            Species.RABBIT -> drawRabbitRig(c, config, layout, config.stage, mood, preview)
            Species.DRAGON -> drawDragonRig(c, config, layout, config.stage, mood, preview)
            Species.UNICORN -> drawUnicornRig(c, config, layout, config.stage, mood, preview)
        }
    }

    /**
     * Record the whole mascot into a fresh canvas and return the draw list —
     * what the tests and any host that manages its own canvas consume.
     */
    fun drawList(
        config: MascotConfig,
        mood: Mood,
        preview: Boolean = false,
    ): List<SvgDrawNode> {
        val c = SvgCanvas()
        draw(c, config, mood, preview)
        return c.nodes
    }
}

/**
 * The rendered mascot: the rig's draw list on a `Canvas`, carrying its species'
 * French accessibility label (invariant 6) and wearing the mood animation
 * (invariant 2).
 *
 * [size] is the mascot's nominal box, the `<svg>`'s width and height, and it is
 * what this composable MEASURES as. The canvas underneath is deliberately larger
 * (see [MascotRig.OVERFLOW]) and centred, because a stage-9 halo leaves the
 * 100-unit box and a group layer clipped to the scope would eat it. Nothing
 * clips unless [preview] is set, which is the web's `overflow: hidden` on shop
 * thumbnails.
 *
 * [focus] crops in the 0..100 mascot space (shop tiles zoom onto one accessory).
 * Only ever used together with [preview], which pins the growth scale to 1 so
 * the crop lines up with raw layout coordinates.
 *
 * [reduceMotion] is injected, never read from an ambient: one source of truth
 * for the setting, and one that a test or a preview can force.
 */
@Composable
fun MascotRigView(
    config: MascotConfig,
    mood: Mood,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    preview: Boolean = false,
    focus: SvgRect? = null,
) {
    val label = MascotRig.label(config.species)
    val viewBox = focus ?: MascotRig.VIEW_BOX
    // Resolved out here, not inside the draw lambda, because inside it `size`
    // would resolve to `DrawScope.size` and silently mean something else.
    val sidePx = with(LocalDensity.current) { size.toPx() }
    val scopeSize = if (preview) size else size * MascotRig.OVERFLOW

    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = label
                role = Role.Image
            }
            // The whole mascot moves, ground shadow included, exactly as the web
            // animates the <svg> element and not its contents.
            .mascotMoodMotion(mood = mood, preview = preview, reduceMotion = reduceMotion)
            // INSIDE the motion layer, so the crop travels with the mascot the
            // way `overflow: hidden` on the <svg> does. Outside it, a moving
            // mascot would be cut by a box that stayed still. Preview is the
            // only mode that clips, and preview is also the only mode that
            // never animates, so today the ordering is invisible — which is
            // exactly the kind of thing that is wrong for years.
            .then(if (preview) Modifier.clipToBounds() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // `requiredSize`, not `size`: the parent hands down a fixed 88 dp
        // constraint and `size` would clamp to it, putting the overflow back.
        Canvas(Modifier.requiredSize(scopeSize)) {
            val scope = this.size
            val inner = SvgRect(
                x = ((scope.width - sidePx) / 2).toDouble(),
                y = ((scope.height - sidePx) / 2).toDouble(),
                width = sidePx.toDouble(),
                height = sidePx.toDouble(),
            )
            val canvas = SvgCanvas(viewBox, inner)
            MascotRig.draw(canvas, config, mood, preview)
            drawSvg(canvas.nodes)
        }
    }
}
