/**
 * Single source of truth for the config keys + option ids the rig reads.
 * Both Mascot.tsx (reader) and catalog.ts (writer) import from here so the
 * slot / value / accessory-id strings can never drift apart.
 *
 * Owned by AGENT A.
 */

/** Colour config slots per species — written to config.colors[slot]. */
export const COLOR_SLOT = {
  unicorn: { body: "bodyColor", horn: "hornColor", mane: "maneColor", tail: "tailColor" },
  cat: { body: "bodyColor", belly: "bellyColor", tail: "tailColor" },
  fox: { body: "bodyColor", belly: "bellyColor", tailTip: "tailTipColor" },
} as const;

/** Style config slots per species — written to config.styles[slot]. */
export const STYLE_SLOT = {
  unicorn: { tail: "tailStyle", horn: "hornStyle" },
  cat: { hair: "hair", tail: "tailSize" },
  fox: { fur: "furPattern", tail: "tailSize" },
} as const;

/** Accessory option ids — matched verbatim against config.accessories. */
export const ACCESSORY = {
  unicorn: {
    ribbon: "unicorn.accessory.ribbon",
    flowerCrown: "unicorn.accessory.flower-crown",
    starClip: "unicorn.accessory.star-clip",
    swimsuit: "unicorn.accessory.swimsuit",
    swimRing: "unicorn.accessory.swim-ring",
  },
  cat: {
    bow: "cat.accessory.bow",
    bellCollar: "cat.accessory.bell-collar",
    partyHat: "cat.accessory.party-hat",
    swimsuit: "cat.accessory.swimsuit",
    swimRing: "cat.accessory.swim-ring",
  },
  fox: {
    scarf: "fox.accessory.scarf",
    beanie: "fox.accessory.beanie",
    boots: "fox.accessory.boots",
    swimsuit: "fox.accessory.swimsuit",
    swimRing: "fox.accessory.swim-ring",
  },
} as const;
