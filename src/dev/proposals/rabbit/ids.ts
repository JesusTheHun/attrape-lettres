/**
 * Proposal-local slot names + accessory ids for the picked design (A « Lune »),
 * mirroring src/mascot/ids.ts so ship mode is a move, not a rewrite.
 */

/** Colour config slots — written to config.colors[slot]. */
export const P_COLOR_SLOT = {
  rabbit: { body: "bodyColor", belly: "bellyColor", inner: "innerEarColor" },
} as const;

/** Style config slots — written to config.styles[slot]. */
export const P_STYLE_SLOT = {
  rabbit: { ear: "earStyle", tail: "tailStyle", fur: "furPattern" },
} as const;

/** Accessory option ids — matched verbatim against config.accessories. */
export const P_ACCESSORY = {
  rabbit: {
    bow: "rabbit.accessory.bow",
    nightcap: "rabbit.accessory.nightcap",
    stardust: "rabbit.accessory.stardust",
    swimsuit: "rabbit.accessory.swimsuit",
    swimRing: "rabbit.accessory.swim-ring",
  },
} as const;
