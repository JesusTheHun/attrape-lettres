// Port of `src/mascot/anchors.ts`.

/**
 * WHERE each *worn* accessory sits — resolved from the live per-stage `layout`
 * so a ribbon / collar / scarf tracks the head as it shrinks from the huge-headed
 * newborn to the slim adult, instead of a fixed body-relative point that the
 * baby's oversized head rides straight over (the "bow in the middle of the face"
 * bug). Placement is a pure function of (species, stage) — one source of truth,
 * eyeballed in the Storybook accessory matrix.
 *
 * Owned by AGENT A.
 */
// NB: the three anchor shapes are their own structs rather than `CGPoint` —
// ALCore may not import CoreGraphics (D1, enforced by `PackageSeamTests`), and
// two of the three carry a radius/half-width a point has nowhere to put.
public struct NeckAnchor: Hashable, Sendable {
    public var x: Double
    public var y: Double
    public var w: Double

    public init(x: Double, y: Double, w: Double) {
        self.x = x
        self.y = y
        self.w = w
    }
}

public struct HeadPointAnchor: Hashable, Sendable {
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

public struct HeadDiscAnchor: Hashable, Sendable {
    public var x: Double
    public var y: Double
    public var r: Double

    public init(x: Double, y: Double, r: Double) {
        self.x = x
        self.y = y
        self.r = r
    }
}

public struct AccessoryAnchors: Hashable, Sendable {
    /** Throat / collar line — neck-worn items sit here (ribbon, bell collar, scarf).
     *  `x`,`y` = centre of the throat; `w` = half-width for a collar arc. Always
     *  BELOW the muzzle and glued to the head, so it never climbs onto the face. */
    public var neck: NeckAnchor
    /** Dome apex — hats / beanies seat just above this. */
    public var headTop: HeadPointAnchor
    /** Upper-left of the face — side clips (star clip). */
    public var headSide: HeadPointAnchor
    /** Head centre + radius — ring items fan around this (flower crown). */
    public var head: HeadDiscAnchor
    /** Per-stage legs — foot-worn items follow these (boots). */
    public var feet: [LegSpec]

    public init(
        neck: NeckAnchor,
        headTop: HeadPointAnchor,
        headSide: HeadPointAnchor,
        head: HeadDiscAnchor,
        feet: [LegSpec]
    ) {
        self.neck = neck
        self.headTop = headTop
        self.headSide = headSide
        self.head = head
        self.feet = feet
    }
}

/**
 * How far below the head centre the throat sits, in ×headR. Tuned per muzzle so
 * the collar clears the mouth on EVERY stage: the fox snout sits lowest on the
 * head, so its throat drops a touch less; the cat muzzle sits highest, so it can
 * drop the most. These are the only species-specific numbers here.
 */
/* Rabbit: tiny nose high on the face like the cat — same drop. */
/* Dragon: wide low snout like the fox — same drop (tuned in the items QA). */
private let neckK: [Species: Double] = [
    .unicorn: 0.9, .cat: 0.94, .fox: 0.86, .rabbit: 0.94, .dragon: 0.86,
]

public func accessoryAnchors(_ species: Species, _ layout: Layout) -> AccessoryAnchors {
    let headCX = layout.headCX
    let headCY = layout.headCY
    let headR = layout.headR
    let bodyCX = layout.bodyCX

    // Standing/wobbly/proud: head sits above the chest → throat is straight below
    // the head centre. Lying newborn (stades 0-1): the head rests low and FORWARD
    // over the belly, so the throat is pulled back toward the body centre and up.
    // NB: `NECK_K[species]` cannot miss — `Species` is closed and the table is
    // total. Force-unwrapping would say the same thing louder; the `?? 0` an
    // optional lookup needs would silently draw a collar on the chin instead, so
    // the table is asserted total by `MascotAnchorsTests`.
    let k = neckK[species] ?? 0
    let neck =
        layout.standing
        ? NeckAnchor(x: headCX, y: headCY + headR * k, w: headR * 0.7)
        : NeckAnchor(x: (headCX + bodyCX) / 2, y: headCY + headR * 0.6, w: headR * 0.6)

    return AccessoryAnchors(
        neck: neck,
        headTop: HeadPointAnchor(x: headCX, y: headCY - headR),
        headSide: HeadPointAnchor(x: headCX - headR * 0.72, y: headCY - headR * 0.52),
        head: HeadDiscAnchor(x: headCX, y: headCY, r: headR),
        feet: layout.legs
    )
}
