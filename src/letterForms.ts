import type { LetterFace, LetterScript } from "./types";

/**
 * How a letter is drawn, shared by every exercise that renders a glyph by form.
 * `print` is the app's rounded sans; `cursive` leans on the OS handwriting font
 * (Snell/Segoe Script/…) — zero-dependency, so the joined "attaché" shape a
 * French 6yo learns is close enough without shipping a webfont. (A proper
 * école-cursive font file is a nice follow-up; the runtime needs none.)
 */
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";
export const SCRIPT_FONT: Record<LetterScript, string> = {
  print: ROUNDED,
  cursive: "'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive",
};

/** Screen-reader label: names the letter, its case, and (cursive only) its form. */
export function faceLabel(face: LetterFace): string {
  const caseWord = face.glyph === face.glyph.toUpperCase() ? "majuscule" : "minuscule";
  const scriptWord = face.script === "cursive" ? " attachée" : "";
  return `Lettre ${face.base} ${caseWord}${scriptWord}`;
}
