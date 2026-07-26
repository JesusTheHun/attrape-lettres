/**
 * Proposal-local slot names + accessory ids for the picked design (A « Braise »),
 * mirroring src/mascot/ids.ts so ship mode is a move, not a rewrite.
 *
 * Wardrobe v2 (retours utilisateur) : plus de paire de bain / bouclier / casque ;
 * cape gardée mais stade 2+ ; la queue change de STYLE (massue, feu), pas de
 * taille ; cornes = nouvelle forme + couleurs ; nouveaux accessoires (lunettes
 * d'aviateur, collier de croc, petit trésor).
 */

/** Colour config slots — written to config.colors[slot]. */
export const P_COLOR_SLOT = {
  dragon: { body: "bodyColor", belly: "bellyColor", wing: "wingColor", horn: "hornColor" },
} as const;

/** Style config slots — written to config.styles[slot]. */
export const P_STYLE_SLOT = {
  dragon: { horn: "hornStyle", crest: "crestStyle", tail: "tailStyle" },
} as const;

/** Accessory option ids — matched verbatim against config.accessories. */
export const P_ACCESSORY = {
  dragon: {
    cape: "dragon.accessory.cape",
    goggles: "dragon.accessory.goggles",
    fang: "dragon.accessory.fang-necklace",
    treasure: "dragon.accessory.treasure",
    blueFlame: "dragon.accessory.blue-flame",
  },
} as const;
