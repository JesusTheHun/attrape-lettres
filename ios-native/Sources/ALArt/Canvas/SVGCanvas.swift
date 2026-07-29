import CoreGraphics
import SwiftUI

// The drawing substrate every mascot, icon and word image goes through (D15).
//
// One model, two halves:
//
//   1. `SVGCanvas` — an imperative builder with an explicit CTM stack that
//      mirrors SVG's own transform semantics. A rig draw function transcribes
//      its TSX statement by statement (painter's order, D15) into `fill` /
//      `stroke` / `clip` / `group` / `mask` calls and gets back a draw list.
//   2. `render(into:)` — replays that list into a SwiftUI `Canvas`'s
//      `GraphicsContext`.
//
// The split is what makes the geometry host-testable: a test builds the list
// and asserts on transforms, clip regions and node structure without ever
// creating a `GraphicsContext` (which only exists inside a live `Canvas`).
//
// Transform semantics — the part D15 exists for:
//
//   `canvas.translate(50, y); canvas.scale(k); canvas.translate(-50, -y)`
//   is exactly `transform="translate(50 y) scale(k) translate(-50 -y)"`:
//   the FIRST call is the OUTERMOST transform, later calls apply to the
//   drawn geometry first — the same left-to-right reading as an SVG
//   transform list, and the same arithmetic a browser does. Because the
//   whole CTM (including the viewBox mapping and the growth `scale(k)`)
//   reaches `GraphicsContext` as one matrix, stroke widths scale with the
//   geometry exactly as SVG scales them.
//
// What this file deliberately does NOT do:
//
//   - Clip at the viewBox. Wings and haloes at the top growth stages
//     deliberately overflow the 100-unit box (`overflow: visible` on the
//     web). Only the container decides to clip (preview mode does; nothing
//     else does). `SVGCanvas.viewBoxTransform` maps, it never clips.
//     NB: SwiftUI's `Canvas` view clips to its *own bounds* — the mascot
//     view must size the Canvas larger than the mascot and offset inside
//     it; that is the container's job, not this file's.
//   - Even-odd fills. The whole app has zero `fill-rule="evenodd"` (D21);
//     every fill here is nonzero, which is `FillStyle()`'s default. There
//     is no API to request even-odd on purpose.
//   - `<use>`. SVG's def/use indirection exists because SVG defs share a
//     document namespace; here a reused subtree is just the draw closure
//     called twice (the rainbow overlay does exactly that: once for the
//     rig, once inside `mask`).

/// One node of the recorded draw list.
///
/// Leaves (`fill` / `stroke`) are self-contained: they carry the full CTM at
/// the time they were recorded and the clip stack resolved into root space,
/// so replay order is the only ordering that matters — painter's order, the
/// order the builder was called in.
public enum SVGDrawNode {
    /// Fill `path` (nonzero — D21) with `paint`. `transform` maps the path's
    /// user space to the canvas root space; `clips` are root-space paths whose
    /// intersection bounds the drawing.
    case fill(path: Path, transform: CGAffineTransform, clips: [Path], paint: SVGPaint)
    /// Stroke `path` with `paint`. The stroke is centred on the path and its
    /// width lives in the path's user space — the CTM scales it, as SVG does.
    case stroke(path: Path, transform: CGAffineTransform, clips: [Path], paint: SVGPaint, style: StrokeStyle)
    /// `<g opacity=…>`: children composite into one layer first, then the
    /// layer blends at `opacity` — overlapping children must NOT double-darken.
    case group(opacity: Double, children: [SVGDrawNode])
    /// `<mask>`: `matte`'s rendered ALPHA modulates `content`, pixel by pixel.
    ///
    /// NB: the web's one mask (the "Arc-en-ciel magique" silhouette) is a
    /// luminance mask made equivalent to an alpha mask by CSS
    /// `filter: brightness(0) invert(1)` on the `<use>` — after that filter
    /// luminance ≡ 1, so mask value = source alpha (spec/mascot.md §4).
    /// This port implements the alpha mask directly and drops the filter;
    /// implementing luminance masking (GraphicsContext even ships a
    /// `.luminanceToAlpha` filter) would re-introduce a web workaround for
    /// nothing. Semi-transparent parts keep their exact web behaviour:
    /// a sparkle at opacity 0.8 passes 0.8 of the sheen.
    case mask(matte: [SVGDrawNode], content: [SVGDrawNode])
}

/// The builder: an SVG-semantics drawing surface with an explicit CTM stack.
public struct SVGCanvas {

    private struct State {
        var ctm: CGAffineTransform
        var clips: [Path]
    }

    private var state: State
    private var stack: [State] = []

    /// The recorded draw list, in painter's order.
    public private(set) var nodes: [SVGDrawNode] = []

    /// The CTM as currently accumulated: user space → canvas root space.
    public var currentTransform: CGAffineTransform { state.ctm }

    /// The active clip stack, resolved into canvas root space.
    public var currentClips: [Path] { state.clips }

    // MARK: - Creation

    public init() {
        state = State(ctm: .identity, clips: [])
    }

    /// A canvas whose root CTM maps `viewBox` into `rect` the way
    /// `preserveAspectRatio="xMidYMid meet"` does: uniform scale to fit,
    /// centred on both axes. Content outside the viewBox is NOT clipped.
    public init(viewBox: CGRect, fitting rect: CGRect) {
        state = State(ctm: Self.viewBoxTransform(viewBox, fitting: rect), clips: [])
    }

    /// The `xMidYMid meet` mapping on its own — for containers that manage
    /// their own canvas (the mascot view sizes its Canvas larger than the
    /// mascot so overflow survives, then applies this to an inner rect).
    public static func viewBoxTransform(_ viewBox: CGRect, fitting rect: CGRect) -> CGAffineTransform {
        guard viewBox.width > 0, viewBox.height > 0 else { return .identity }
        let s = min(rect.width / viewBox.width, rect.height / viewBox.height)
        let dx = rect.midX - viewBox.midX * s
        let dy = rect.midY - viewBox.midY * s
        return CGAffineTransform(scaleX: s, y: s)
            .concatenating(CGAffineTransform(translationX: dx, y: dy))
    }

    // MARK: - CTM stack

    /// Push the current transform and clip; `restore()` pops back to it.
    public mutating func save() {
        stack.append(state)
    }

    /// Pop to the most recent `save()`. Unbalanced restores are authored-code
    /// bugs the tests catch; at runtime this is a no-op rather than a trap —
    /// invariant 3 applied to the drawing layer.
    public mutating func restore() {
        guard let previous = stack.popLast() else {
            assertionFailure("SVGCanvas.restore() without a matching save()")
            return
        }
        state = previous
    }

    /// Concatenate `t` the way appending it to an SVG transform list does:
    /// `t` applies to subsequently drawn geometry BEFORE everything already
    /// on the stack.
    public mutating func concatenate(_ t: CGAffineTransform) {
        state.ctm = t.concatenating(state.ctm)
    }

    /// `translate(tx ty)`.
    public mutating func translate(_ tx: Double, _ ty: Double) {
        concatenate(CGAffineTransform(translationX: tx, y: ty))
    }

    /// `scale(s)` — uniform.
    public mutating func scale(_ s: Double) {
        scale(s, s)
    }

    /// `scale(sx sy)`. Negative values mirror, exactly as in SVG — the
    /// unicorn's left wing is `scale(-s s)` and must stay that way.
    public mutating func scale(_ sx: Double, _ sy: Double) {
        concatenate(CGAffineTransform(scaleX: sx, y: sy))
    }

    /// `rotate(a)` or `rotate(a cx cy)`. The optional anchor is the silent
    /// bug D15 exists to kill, so it is implemented exactly as SVG defines
    /// it: `translate(cx cy) rotate(a) translate(-cx -cy)`. Positive angles
    /// turn clockwise on screen (y-down space), same as SVG.
    public mutating func rotate(degrees: Double, about center: CGPoint? = nil) {
        if let c = center {
            concatenate(CGAffineTransform(translationX: c.x, y: c.y))
            concatenate(CGAffineTransform(rotationAngle: degrees * .pi / 180))
            concatenate(CGAffineTransform(translationX: -c.x, y: -c.y))
        } else {
            concatenate(CGAffineTransform(rotationAngle: degrees * .pi / 180))
        }
    }

    // MARK: - Clip

    /// `<clipPath>`: intersect the active clip with `path` (interpreted in
    /// the current user space, nonzero rule). Scoped by `save()`/`restore()`
    /// like everything else on the stack.
    public mutating func clip(to path: Path) {
        state.clips.append(path.applying(state.ctm))
    }

    // MARK: - Drawing

    /// Fill `path` (nonzero) with `paint` under the current CTM and clip.
    public mutating func fill(_ path: Path, with paint: SVGPaint) {
        nodes.append(.fill(path: path, transform: state.ctm, clips: state.clips, paint: paint))
    }

    /// Stroke `path` with `paint` under the current CTM and clip. A zero or
    /// negative line width records nothing: SVG treats `stroke-width="0"` as
    /// "no stroke" while SwiftUI may still draw a hairline — the dragon's
    /// `SpadeTail` passes `strokeWidth={edge ? 1 : 0}` and relies on this.
    public mutating func stroke(_ path: Path, with paint: SVGPaint, style: StrokeStyle) {
        guard style.lineWidth > 0 else { return }
        nodes.append(.stroke(path: path, transform: state.ctm, clips: state.clips, paint: paint, style: style))
    }

    // MARK: - Structure

    /// `<g opacity=…>` — `body` draws into a child canvas that inherits the
    /// current CTM and clip; the result composites as ONE layer at `opacity`.
    public mutating func group(opacity: Double = 1, _ body: (inout SVGCanvas) -> Void) {
        var child = SVGCanvas(inheriting: state)
        body(&child)
        nodes.append(.group(opacity: opacity, children: child.nodes))
    }

    /// `<mask>` — `matte`'s rendered alpha modulates what `content` draws.
    /// Both closures inherit the current CTM and clip. See `SVGDrawNode.mask`
    /// for why this is an alpha mask and not a luminance one.
    public mutating func mask(_ matte: (inout SVGCanvas) -> Void, content: (inout SVGCanvas) -> Void) {
        var matteCanvas = SVGCanvas(inheriting: state)
        matte(&matteCanvas)
        var contentCanvas = SVGCanvas(inheriting: state)
        content(&contentCanvas)
        nodes.append(.mask(matte: matteCanvas.nodes, content: contentCanvas.nodes))
    }

    private init(inheriting state: State) {
        self.state = state
    }

    // MARK: - Replay

    /// Replay the recorded draw list into a `GraphicsContext`, in order.
    public func render(into context: inout GraphicsContext) {
        Self.render(nodes, into: &context)
    }

    static func render(_ nodes: [SVGDrawNode], into context: inout GraphicsContext) {
        for node in nodes {
            switch node {
            case let .fill(path, transform, clips, paint):
                var c = context
                for clip in clips { c.clip(to: clip) }
                paint.fill(path, transform: transform, into: &c)

            case let .stroke(path, transform, clips, paint, style):
                var c = context
                for clip in clips { c.clip(to: clip) }
                paint.stroke(path, style: style, transform: transform, into: &c)

            case let .group(opacity, children):
                // Composite the children as one layer, then blend the layer
                // at the group's opacity — `drawLayer` applies the context's
                // current opacity to the finished layer, so overlapping
                // children darken once, not twice.
                var c = context
                c.opacity *= opacity
                c.drawLayer { inner in
                    inner.opacity = 1
                    Self.render(children, into: &inner)
                }

            case let .mask(matte, content):
                var c = context
                c.clipToLayer { inner in
                    Self.render(matte, into: &inner)
                }
                Self.render(content, into: &c)
            }
        }
    }
}
