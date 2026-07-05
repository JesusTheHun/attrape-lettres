import { accessoryAnchors } from "../mascot/anchors";
import { DEFAULT_LOOKS } from "../mascot/catalog";
import { layoutFor } from "../mascot/growth";
import { Mascot } from "../mascot/Mascot";
import { ACCESSORY, COLOR_SLOT, STYLE_SLOT } from "../mascot/ids";
import type { CustomizationCategory, MascotConfig, Species } from "../types";

/* -------------------------------------------------------------------------- */
/* The shop tile thumbnail: a GHOST mini-mascot that previews the real reward.  */
/* The whole creature is drawn in neutral grey and ONLY the thing this tile     */
/* sells is shown for real — the recoloured part in its hex, the restyled part  */
/* in its new shape, or the accessory drawn where it lands. This kills the two   */
/* failure modes of an emoji thumbnail: an abstract stand-in (🌸 for a pink      */
/* body) and a whole-animal glyph (🐈 for a short tail) that hides the change.   */
/*                                                                              */
/* A fixed "showcase" stage + <Mascot preview> (which suppresses the per-stage  */
/* magic: wings, mane, halo, crown, extra tails, sparkles…) keep the frame calm */
/* so the grey silhouette reads and the single real part pops.                  */
/* -------------------------------------------------------------------------- */

/** Neutral silhouette colour for every part that ISN'T being sold. Kept light and
 * cool so real mid-grey coats (e.g. "Pelage gris") still read a shade darker. */
const GHOST = "#DADCE4";
/** Standing youngster: legs + a real horn/tail are visible, proportions cute. */
const SHOWCASE_STAGE = 4;

function ghostColors(species: Species): Record<string, string> {
  const out: Record<string, string> = {};
  for (const slot of Object.values(COLOR_SLOT[species])) out[slot] = GHOST;
  return out;
}

/**
 * A shape-style lives on ONE part; tinting that part with its factory colour (the
 * rest stays ghost) makes the shape read — a pink coiled tail beats a grey one on
 * a grey rump. Body-wide styles (cat fluffy, fox pattern) are omitted: their
 * change is drawn in its own contrasting colour, so the body stays ghost.
 */
const STYLE_COLOR_SLOT: Partial<Record<Species, Record<string, string>>> = {
  unicorn: { [STYLE_SLOT.unicorn.tail]: COLOR_SLOT.unicorn.tail, [STYLE_SLOT.unicorn.horn]: COLOR_SLOT.unicorn.horn },
  cat: { [STYLE_SLOT.cat.tail]: COLOR_SLOT.cat.tail },
};

function defaultColor(species: Species, colorSlot: string): string | undefined {
  return DEFAULT_LOOKS[species].find((d) => d.category === "color" && d.slot === colorSlot)?.value;
}

type Box = { x: number; y: number; w: number; h: number };

/**
 * Where to ZOOM. A small part or worn accessory is unreadable inside a whole-body
 * thumbnail, so we crop the drawing to a square around it (it then fills the tile).
 * Whole-coat colours, body patterns, fluffy fur and the whole-image shimmer return
 * `undefined` → no crop, full body. Coordinates come from the same layout/anchor
 * maths the rig draws with, at the fixed showcase stage, so the box lands true.
 */
function focusFor(species: Species, category: CustomizationCategory, slot: string, value: string): Box | undefined {
  const L = layoutFor(SHOWCASE_STAGE);
  const A = accessoryAnchors(species, L);
  const { headCX, headCY, headR, bodyCX, bodyCY, bodyRX, bodyRY, feetY } = L;
  const box = (cx: number, cy: number, r: number): Box => ({ x: cx - r, y: cy - r, w: 2 * r, h: 2 * r });

  const horn = () => box(headCX, headCY - headR * 0.5, headR * 1.25);
  const mane = () => box(headCX, headCY, headR * 1.5);
  const uniTail = () => box(bodyCX - bodyRX * 0.5, bodyCY + bodyRY * 0.5, bodyRY * 1.3);
  const catTail = () => box(bodyCX + bodyRX * 0.9, bodyCY - bodyRY * 0.05, bodyRY * 1.25);
  const foxTail = () => box(bodyCX + bodyRX * 0.85, bodyCY + bodyRY * 0.45, bodyRY * 1.3);
  const belly = () => box(bodyCX, bodyCY + bodyRY * 0.28, bodyRX * 1.15);

  if (category === "accessory") {
    if (value === ACCESSORY.unicorn.starClip) return undefined; // whole-image shimmer
    if (value === ACCESSORY.unicorn.ribbon || value === ACCESSORY.cat.bellCollar || value === ACCESSORY.fox.scarf)
      return box(A.neck.x, A.neck.y, headR * 1.1);
    if (value === ACCESSORY.cat.bow || value === ACCESSORY.cat.partyHat || value === ACCESSORY.fox.beanie)
      return box(A.headTop.x, headCY - headR * 0.55, headR * 1.4);
    if (value === ACCESSORY.unicorn.flowerCrown) return box(headCX, headCY - headR * 0.2, headR * 1.4);
    if (value === ACCESSORY.fox.boots) return box(bodyCX, feetY - 4, bodyRX * 1.25);
    return undefined;
  }

  if (category === "color") {
    const C = COLOR_SLOT[species];
    if (slot === C.body) return undefined; // whole coat → full body
    if (species === "unicorn") {
      if (slot === COLOR_SLOT.unicorn.horn) return horn();
      if (slot === COLOR_SLOT.unicorn.mane) return mane();
      if (slot === COLOR_SLOT.unicorn.tail) return uniTail();
    } else if (species === "cat") {
      if (slot === COLOR_SLOT.cat.belly) return belly();
      if (slot === COLOR_SLOT.cat.tail) return catTail();
    } else {
      if (slot === COLOR_SLOT.fox.belly) return belly();
      if (slot === COLOR_SLOT.fox.tailTip) return foxTail();
    }
    return undefined;
  }

  // style: zoom part-local shapes; body-wide (fluffy, fur pattern) stay full.
  if (species === "unicorn") {
    if (slot === STYLE_SLOT.unicorn.horn) return horn();
    if (slot === STYLE_SLOT.unicorn.tail) return uniTail();
  } else if (species === "cat") {
    if (slot === STYLE_SLOT.cat.tail) return catTail();
  } else {
    if (slot === STYLE_SLOT.fox.tail) return foxTail();
    // Spots/stripes sit on the torso and are tiny full-body — crop to the trunk.
    if (slot === STYLE_SLOT.fox.fur) return box(bodyCX, bodyCY, bodyRX * 1.15);
  }
  return undefined;
}

interface ItemPreviewProps {
  species: Species;
  category: CustomizationCategory;
  /** Config key the item writes (colours/styles). Unused for accessories. */
  slot: string;
  /** Hex (colour), style-variant id (style), or accessory id (accessory). */
  value: string;
  size?: number;
}

export function ItemPreview({ species, category, slot, value, size = 60 }: ItemPreviewProps) {
  const colors = ghostColors(species);
  if (category === "color") {
    colors[slot] = value;
  } else if (category === "style") {
    const colorSlot = STYLE_COLOR_SLOT[species]?.[slot];
    const tint = colorSlot && defaultColor(species, colorSlot);
    if (colorSlot && tint) colors[colorSlot] = tint;
  }

  const config: MascotConfig = {
    species,
    stage: SHOWCASE_STAGE,
    colors,
    // A style preview sets only its own slot; the rig fills every other slot with
    // its plain factory default, so the shape change is the sole difference.
    styles: category === "style" ? { [slot]: value } : {},
    accessories: category === "accessory" ? [value] : [],
  };

  const focus = focusFor(species, category, slot, value);

  return (
    <span
      aria-hidden
      className="flex items-center justify-center rounded-2xl"
      style={{ width: size + 14, height: size + 14, background: "#F1F0F5" }}
    >
      <Mascot config={config} mood="idle" size={size} preview focus={focus} />
    </span>
  );
}
