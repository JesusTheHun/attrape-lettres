import type { CustomizationOption, Species } from "../types";
import { COLOR_SLOT, STYLE_SLOT, ACCESSORY } from "./ids";

/**
 * Shop inventory — the single source of what's buyable. Owned by AGENT A.
 *
 * Every option's slot/value matches exactly what Mascot.tsx reads:
 *  - colours write config.colors[slot] = value (hex),
 *  - styles  write config.styles[slot] = value (variant id),
 *  - accessories are matched verbatim by option id (see ids.ts / ACCESSORY).
 *
 * Cost bands (first exercise clear = 10 pts, repeats decay): colours 15–30,
 * styles 30–60, accessories 40–150, one premium per species = 200.
 */

function color(
  species: Species,
  slot: string,
  token: string,
  value: string,
  name: string,
  emoji: string,
  cost: number,
  minStage?: number
): CustomizationOption {
  const base: CustomizationOption = { id: `${species}.color.${slot}.${token}`, species, category: "color", slot, value, name, emoji, cost };
  return minStage === undefined ? base : { ...base, minStage };
}

function style(
  species: Species,
  slot: string,
  value: string,
  name: string,
  emoji: string,
  cost: number,
  minStage?: number
): CustomizationOption {
  const base: CustomizationOption = { id: `${species}.style.${slot}.${value}`, species, category: "style", slot, value, name, emoji, cost };
  return minStage === undefined ? base : { ...base, minStage };
}

function accessory(
  id: string,
  species: Species,
  name: string,
  emoji: string,
  cost: number,
  minStage?: number
): CustomizationOption {
  const base: CustomizationOption = { id, species, category: "accessory", slot: "accessory", value: id, name, emoji, cost };
  return minStage === undefined ? base : { ...base, minStage };
}

const U = COLOR_SLOT.unicorn;
const CA = COLOR_SLOT.cat;
const FO = COLOR_SLOT.fox;
const US = STYLE_SLOT.unicorn;
const CS = STYLE_SLOT.cat;
const FS = STYLE_SLOT.fox;

export const CATALOG: CustomizationOption[] = [
  /* ---- Unicorn ------------------------------------------------------- */
  color("unicorn", U.body, "rose", "#FFD6E8", "Corps rose", "🌸", 18),
  color("unicorn", U.body, "ciel", "#DCEFFB", "Corps bleu ciel", "💧", 18),
  color("unicorn", U.body, "menthe", "#DDF3D8", "Corps menthe", "🌿", 18),
  // Horn only sprouts at stade 2 (nub) → 3 (real horn); gate horn recolours and
  // the twist so nobody buys a look a hornless baby unicorn can't show.
  color("unicorn", U.horn, "rose", "#FF8FB1", "Corne rose", "🦄", 20, 2),
  color("unicorn", U.horn, "turquoise", "#7FD1D8", "Corne turquoise", "💠", 20, 2),
  color("unicorn", U.mane, "corail", "#FF8A65", "Crinière corail", "🔥", 22),
  color("unicorn", U.mane, "turquoise", "#7FD1D8", "Crinière turquoise", "🌊", 22),
  color("unicorn", U.tail, "menthe", "#AED581", "Queue menthe", "🍃", 20),
  style("unicorn", US.tail, "curly", "Queue bouclée", "🌀", 40),
  style("unicorn", US.horn, "spiral", "Corne torsadée", "🐚", 45, 3),
  accessory(ACCESSORY.unicorn.ribbon, "unicorn", "Nœud", "🎀", 45),
  accessory(ACCESSORY.unicorn.flowerCrown, "unicorn", "Couronne de fleurs", "🌸", 95, 2),
  accessory(ACCESSORY.unicorn.starClip, "unicorn", "Poussière d'étoiles", "✨", 200, 4),

  /* ---- Cat ----------------------------------------------------------- */
  color("cat", CA.body, "gris", "#C9CCD6", "Pelage gris", "🩶", 18),
  color("cat", CA.body, "blanc", "#FFF3E6", "Pelage blanc", "🤍", 18),
  color("cat", CA.body, "noir", "#6A6A72", "Pelage noir", "🖤", 20),
  // Stade 0 is a curled sleeping loaf — belly and tail are tucked out of sight,
  // so gate their looks until the kitten sits up at stade 1.
  color("cat", CA.belly, "rose", "#FFE1EC", "Ventre rose", "🌸", 16, 1),
  color("cat", CA.tail, "roux", "#E08A4E", "Queue rousse", "🦊", 18, 1),
  color("cat", CA.body, "creme", "#F3E4D0", "Pelage crème", "🍦", 18),
  color("cat", CA.body, "lilas", "#E6DDF5", "Pelage lilas", "💜", 20),
  color("cat", CA.tail, "grise", "#C9CCD6", "Queue grise", "🌫️", 18, 1),
  style("cat", CS.hair, "fluffy", "Poil touffu", "☁️", 40),
  style("cat", CS.tail, "short", "Petite queue", "🐈", 32, 1),
  accessory(ACCESSORY.cat.bow, "cat", "Nœud", "🎀", 45),
  accessory(ACCESSORY.cat.bellCollar, "cat", "Collier grelot", "🔔", 55),
  accessory(ACCESSORY.cat.partyHat, "cat", "Chapeau de fête", "🎉", 200, 4),

  /* ---- Fox ----------------------------------------------------------- */
  color("fox", FO.body, "roux", "#E96B4A", "Pelage roux", "🍂", 18),
  color("fox", FO.body, "miel", "#F4A259", "Pelage miel", "🍯", 18),
  color("fox", FO.body, "cendre", "#B7A99A", "Pelage cendré", "🌫️", 20),
  color("fox", FO.belly, "creme", "#FFDCB4", "Ventre crème", "🤍", 16),
  color("fox", FO.tailTip, "brun", "#5A3A1E", "Bout de queue brun", "🟤", 18),
  color("fox", FO.tailTip, "dore", "#FFD54F", "Bout de queue doré", "⭐", 22),
  color("fox", FO.body, "arctique", "#EDE7DE", "Pelage arctique", "❄️", 20),
  style("fox", FS.fur, "spots", "Taches", "🐆", 48),
  style("fox", FS.fur, "stripes", "Rayures", "🐯", 48),
  style("fox", FS.tail, "short", "Petite queue", "🦊", 32),
  accessory(ACCESSORY.fox.scarf, "fox", "Écharpe", "🧣", 45),
  accessory(ACCESSORY.fox.beanie, "fox", "Bonnet", "🧢", 60),
  accessory(ACCESSORY.fox.boots, "fox", "Bottes", "🥾", 200, 4),
];

/**
 * The mascot's factory look per colour/style slot. Each `value` MUST match the
 * corresponding `pick(..., fallback)` default in the species rig, so selecting
 * it (which just clears the slot) reproduces exactly what a fresh mascot shows.
 * Surfaced in the shop as an already-owned, NAMED tile — no "reset/default"
 * wording — so a child can simply pick it to return to the original look.
 * `minStage` mirrors the slot's part visibility so a default for a not-yet-grown
 * part (unicorn horn, curled-kitten belly/tail) stays gated like its variants.
 */
export interface DefaultLook {
  category: "color" | "style";
  slot: string;
  value: string;
  name: string;
  emoji?: string;
  minStage?: number;
}

export const DEFAULT_LOOKS: Record<Species, DefaultLook[]> = {
  unicorn: [
    { category: "color", slot: U.body, value: "#F5ECFF", name: "Corps lilas" },
    { category: "color", slot: U.horn, value: "#FFD54F", name: "Corne dorée", minStage: 2 },
    { category: "color", slot: U.mane, value: "#BA9EE8", name: "Crinière parme" },
    { category: "color", slot: U.tail, value: "#F49AC2", name: "Queue rose" },
    { category: "style", slot: US.tail, value: "straight", name: "Queue lisse", emoji: "〰️" },
    { category: "style", slot: US.horn, value: "smooth", name: "Corne lisse", emoji: "🔺", minStage: 2 },
  ],
  cat: [
    { category: "color", slot: CA.body, value: "#F6A96B", name: "Pelage roux" },
    { category: "color", slot: CA.belly, value: "#FFF3E4", name: "Ventre crème", minStage: 1 },
    { category: "color", slot: CA.tail, value: "#F6A96B", name: "Queue assortie", minStage: 1 },
    { category: "style", slot: CS.hair, value: "short", name: "Poil court", emoji: "🐱" },
    { category: "style", slot: CS.tail, value: "long", name: "Grande queue", emoji: "🐈", minStage: 1 },
  ],
  fox: [
    { category: "color", slot: FO.body, value: "#FF8A65", name: "Pelage roux" },
    { category: "color", slot: FO.belly, value: "#FFFFFF", name: "Ventre blanc" },
    { category: "color", slot: FO.tailTip, value: "#FFFFFF", name: "Bout blanc" },
    { category: "style", slot: FS.fur, value: "plain", name: "Pelage uni", emoji: "🟠" },
    { category: "style", slot: FS.tail, value: "long", name: "Grande queue", emoji: "🦊" },
  ],
};
