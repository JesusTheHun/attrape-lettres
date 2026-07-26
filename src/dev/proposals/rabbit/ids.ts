/**
 * Proposal-local slot names for the rabbit candidates, mirroring
 * src/mascot/ids.ts so ship mode is a move, not a rewrite. Accessory ids
 * arrive with the items run (the wardrobe is designed on the picked silhouette).
 */

/** Colour config slots — written to config.colors[slot]. */
export const P_COLOR_SLOT = {
  rabbit: { body: "bodyColor", belly: "bellyColor", inner: "innerEarColor" },
} as const;

/** Style config slots — written to config.styles[slot]. */
export const P_STYLE_SLOT = {
  rabbit: { ear: "earStyle", tail: "tailSize" },
} as const;
