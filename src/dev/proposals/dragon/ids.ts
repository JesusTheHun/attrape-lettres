/**
 * Proposal-local slot names + accessory ids, mirroring src/mascot/ids.ts so
 * ship mode is a move, not a rewrite. Read ONLY by the candidate rigs and the
 * proposal render harness.
 */

/** Colour config slots — written to config.colors[slot]. */
export const P_COLOR_SLOT = {
  dragon: { body: "bodyColor", belly: "bellyColor", wing: "wingColor" },
} as const;

/** Style config slots — written to config.styles[slot]. */
export const P_STYLE_SLOT = {
  dragon: { horn: "hornStyle", crest: "crestStyle", tail: "tailSize" },
} as const;

/** Accessory option ids — matched verbatim against config.accessories. */
export const P_ACCESSORY = {
  dragon: {
    bowtie: "dragon.accessory.bowtie",
    goggles: "dragon.accessory.goggles",
    armor: "dragon.accessory.armor",
    swimsuit: "dragon.accessory.swimsuit",
    swimRing: "dragon.accessory.swim-ring",
  },
} as const;
