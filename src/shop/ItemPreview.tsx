import { DEFAULT_LOOKS } from "../mascot/catalog";
import { Mascot } from "../mascot/Mascot";
import { COLOR_SLOT, STYLE_SLOT } from "../mascot/ids";
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

/** Neutral silhouette colour for every part that ISN'T being sold. */
const GHOST = "#CFD1D9";
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

  return (
    <span
      aria-hidden
      className="flex items-center justify-center rounded-2xl"
      style={{ width: size + 14, height: size + 14, background: "#F1F0F5" }}
    >
      <Mascot config={config} mood="idle" size={size} preview />
    </span>
  );
}
