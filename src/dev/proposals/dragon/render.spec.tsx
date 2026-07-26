/**
 * Proposal render harness (propose mode — designs only, reboot garçon) —
 * gated behind PROPOSAL=dragon so a plain `pnpm test` never runs it:
 *
 *   PROPOSAL=dragon pnpm vitest run src/dev/proposals/dragon/render.spec.tsx
 *
 * Writes qa-*.html (screenshot targets for the visual QA loop) and the final
 * proposal.html next to this file.
 */
import { it } from "vitest";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { layoutFor, stageScale } from "../../../mascot/growth";
import { Fox } from "../../../mascot/Fox";
import { CandidateA } from "./CandidateA";
import { CandidateB } from "./CandidateB";
import { CandidateC } from "./CandidateC";
import type { PConfig, PRigProps } from "./dragonParts";

const SPECIES = "dragon";
const RUN = process.env.PROPOSAL === SPECIES;
const DIR = dirname(fileURLToPath(import.meta.url));
const STAGES = Array.from({ length: 10 }, (_, i) => i);

const cfg = (over: Partial<PConfig> = {}): PConfig => ({ colors: {}, styles: {}, accessories: [], ...over });

type Rig = (p: PRigProps) => ReactElement;

/** Render one candidate at one stade, standalone. Mirrors Mascot.tsx.
 * uid is a global counter: pages mixing candidates would otherwise collide on
 * <defs> gradient ids (first ground-glow wins for everyone). */
let uidN = 0;
function petSvg(RigC: Rig, stage: number, config: PConfig = cfg(), size = 150): string {
  const layout = layoutFor(stage);
  const k = stageScale(stage);
  const uid = `p${++uidN}`;
  return renderToStaticMarkup(
    <svg viewBox="0 0 100 100" width={size} height={size} style={{ overflow: "visible", display: "block" }}>
      <ellipse cx={50} cy={layout.feetY + 3.5} rx={layout.bodyRX * 0.92 * k} ry={3.6} fill="#000" opacity={0.1} />
      <g transform={`translate(50 ${layout.feetY}) scale(${k}) translate(-50 ${-layout.feetY})`}>
        <RigC config={config} layout={layout} stage={stage} uid={uid} />
      </g>
    </svg>
  );
}

/** The existing fox, rendered the same way — the roster-palette reference. */
function foxSvg(stage: number, size = 150): string {
  const layout = layoutFor(stage);
  const k = stageScale(stage);
  return renderToStaticMarkup(
    <svg viewBox="0 0 100 100" width={size} height={size} style={{ overflow: "visible", display: "block" }}>
      <ellipse cx={50} cy={layout.feetY + 3.5} rx={layout.bodyRX * 0.92 * k} ry={3.6} fill="#000" opacity={0.1} />
      <g transform={`translate(50 ${layout.feetY}) scale(${k}) translate(-50 ${-layout.feetY})`}>
        <Fox
          config={{ species: "fox", stage, colors: {}, styles: {}, accessories: [] }}
          layout={layout}
          stage={stage}
          mood="idle"
          uid={`fox${stage}`}
        />
      </g>
    </svg>
  );
}

/** A captioned row of cells. Top padding ≥70px: high stades overflow UP by design. */
function strip(cells: { svg: string; caption: string }[]): string {
  return `<div class="strip">${cells
    .map((c) => `<div class="cell"><div class="art">${c.svg}</div><p>${c.caption}</p></div>`)
    .join("")}</div>`;
}

function page(title: string, body: string): string {
  return `<!doctype html><meta charset="utf-8"><title>${title}</title><style>
    body{font-family:ui-rounded,'SF Pro Rounded',system-ui,sans-serif;background:#FFF7EC;color:#5A3A1E;margin:24px}
    h1{font-size:22px}h2{font-size:18px;margin:20px 0 8px}
    .strip{display:flex;flex-wrap:wrap;gap:14px;align-items:flex-end}
    .cell{background:#fff;border-radius:14px;padding:80px 12px 8px;text-align:center;box-shadow:0 1px 4px rgba(0,0,0,.08)}
    .cell p{font-size:12px;font-weight:800;margin:6px 0 0}
  </style><h1>${title}</h1>${body}`;
}

const out = (name: string, html: string) => {
  mkdirSync(DIR, { recursive: true });
  writeFileSync(join(DIR, name), html);
};

const growthStrip = (RigC: Rig) => strip(STAGES.map((s) => ({ svg: petSvg(RigC, s), caption: `stade ${s}` })));

it.runIf(RUN)("renders QA pages", () => {
  // 1) One growth strip per candidate.
  out("qa-candidate-a.html", page("Candidat A « Braise » — croissance", growthStrip(CandidateA)));
  out("qa-candidate-b.html", page("Candidat B « Roc » — croissance", growthStrip(CandidateB)));
  out("qa-candidate-c.html", page("Candidat C « Orage » — croissance", growthStrip(CandidateC)));

  // 2) Egg close-up: the brief's two special stades, big, all three candidates.
  const eggCells = [CandidateA, CandidateB, CandidateC].flatMap((R, i) => [
    { svg: petSvg(R, 0, cfg(), 230), caption: `candidat ${"ABC"[i]} · stade 0` },
    { svg: petSvg(R, 1, cfg(), 230), caption: `candidat ${"ABC"[i]} · stade 1` },
  ]);
  out("qa-egg.html", page("L'œuf — stades 0 et 1 (brief)", strip(eggCells)));

  // 3) Roster check: candidates next to the existing fox (palette must sit in
  //    the same kawaii family while reading clearly more "garçon").
  const roster = (s: number) =>
    strip([
      { svg: foxSvg(s), caption: `renard (existant) · stade ${s}` },
      { svg: petSvg(CandidateA, s), caption: `A Braise · stade ${s}` },
      { svg: petSvg(CandidateB, s), caption: `B Roc · stade ${s}` },
      { svg: petSvg(CandidateC, s), caption: `C Orage · stade ${s}` },
    ]);
  out("qa-roster.html", page("Face au roster", `<h2>stade 4</h2>${roster(4)}<h2>stade 9</h2>${roster(9)}`));
});

/* ------------------------------------------------------------------------- */
/* The deliverable — proposal.html (designs only; items after the pick).      */
/* ------------------------------------------------------------------------- */

const chip = (hex: string, name: string) =>
  `<span class="chip"><i style="background:${hex}"></i>${name} <code>${hex}</code></span>`;

const arcTable = (rows: string[]) =>
  `<table class="arc"><tr><th>Stade</th><th>Ce que l'enfant voit de nouveau</th></tr>${rows
    .map((r, i) => `<tr><td>${i}</td><td>${r}</td></tr>`)
    .join("")}</table>`;

it.runIf(RUN)("writes proposal.html", () => {
  const direction = (
    letter: string,
    title: string,
    pitch: string,
    palette: string,
    rows: string[],
    ideas: string,
    RigC: Rig,
    recommended: boolean
  ) =>
    `<section class="dir ${recommended ? "reco" : ""}">
      <h2>Candidat ${letter} — ${title}${recommended ? ' <span class="badge">⭐ Recommandé</span>' : ""}</h2>
      <p>${pitch}</p>
      <p class="chips">${palette}</p>
      ${arcTable(rows)}
      ${strip(STAGES.map((s) => ({ svg: petSvg(RigC, s, cfg(), 130), caption: `stade ${s}` })))}
      <p class="ideas"><b>Idées d'objets pour cette silhouette</b> (aperçu texte — la garde-robe sera dessinée sur le candidat retenu) : ${ideas}</p>
    </section>`;

  const html = `<!doctype html><meta charset="utf-8"><title>Proposition v2 — Mon dragon</title><style>
    body{font-family:ui-rounded,'SF Pro Rounded',system-ui,sans-serif;background:#FFF7EC;color:#5A3A1E;margin:28px;max-width:1240px}
    h1{font-size:26px;margin-bottom:4px} h2{font-size:20px;margin:8px 0} h3{font-size:18px;margin:26px 0 10px}
    .meta{color:#9A7A5A;font-weight:700;font-size:13px;margin-bottom:18px}
    .banner{background:#FFE9C9;border:2px solid #F2C14E;border-radius:14px;padding:10px 16px;font-weight:800;margin:14px 0 26px}
    .strip{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-end;margin:10px 0}
    .cell{background:#fff;border-radius:14px;padding:78px 10px 8px;text-align:center;box-shadow:0 1px 4px rgba(0,0,0,.08)}
    .cell p{font-size:11.5px;font-weight:800;margin:6px 0 0;max-width:140px}
    .dir{background:rgba(255,255,255,.55);border-radius:18px;padding:16px 18px;margin:0 0 22px;border:2px solid transparent}
    .dir.reco{border-color:#F2C14E;background:#FFFDF6}
    .badge{background:#F2C14E;color:#5A3A1E;border-radius:999px;padding:2px 10px;font-size:13px;vertical-align:middle}
    .chips .chip{display:inline-flex;align-items:center;gap:6px;background:#fff;border-radius:999px;padding:3px 10px 3px 4px;margin:0 6px 6px 0;font-size:12.5px;font-weight:700}
    .chip i{width:18px;height:18px;border-radius:50%;display:inline-block;border:1px solid rgba(0,0,0,.12)}
    .chip code{color:#9A7A5A;font-size:11px}
    table.arc{border-collapse:collapse;font-size:13px;margin:8px 0}
    table.arc th,table.arc td{border:1px solid #EAD9BF;padding:4px 10px;text-align:left;background:#fff}
    table.arc th{background:#FFF1DC}
    .ideas{font-size:13px;color:#7A6248;background:#fff;border-radius:12px;padding:8px 12px}
    .qa p,.qa li{font-size:13.5px}
  </style>
  <h1>🐉 Mon dragon — proposition v2 (pistes garçon)</h1>
  <p class="meta">Brief v2 : les 3 premières pistes jugées trop « girly » → reboot orienté garçon. Toujours : stade 0 = dans l'œuf, dragon visible · stade 1 = coquilles gardées mignonnement · 26/07/2026. Design seulement — la garde-robe (~15 objets) sera dessinée sur le candidat validé.</p>
  <div class="banner">⭐ Recommandation : <b>Candidat A « Braise »</b> — le feu est l'imaginaire dragon-garçon le plus direct, la silhouette ailée reste la plus lisible « dragon » à 88 px, et vert profond + charbon + braise tranche avec tout le roster. B « Roc » est le plus différent de tout ce qui existe (sans ailes, massue) si vous voulez oser.</div>

  <h3>Ce qui a changé vs v1 : couleurs plus profondes (fini menthe/corail pastel), détails anguleux (crocs, griffes, pointes, massue), zéro gemme/halo, braises-roches-éclairs à la place des étoiles scintillantes.</h3>
  ${direction(
    "A",
    "« Braise », le dragon de feu",
    "Un dragon vert profond à crête-mohawk charbon et ailes couleur braise : crocs, griffes, fissures de lave qui s'allument — le volcan se réveille en lui.",
    [chip("#7DB874", "corps"), chip("#E9DFB2", "ventre sable"), chip("#E2694F", "ailes braise"), chip("#5F6470", "crête charbon"), chip("#FF8A50", "braises")].join(""),
    [
      "Dans son œuf fêlé — frimousse endormie visible, pointe de queue qui dépasse",
      "Sorti ! coquille-chapeau + morceau de coquille sur le flanc",
      "Premiers pas, petites bosses de cornes",
      "Ailes de chauve-souris couleur braise + vraies cornes",
      "Crête-mohawk charbon",
      "Écailles du ventre + grande pointe de queue",
      "Première flamme ! + braises qui montent",
      "Crocs + griffes blanches + cornes à pointes d'or",
      "Le volcan s'éveille : fissures de lave, fumée aux naseaux, aura de braise",
      "Tempête de feu : ailes géantes, grand souffle, sol de lave, anneau de braises",
    ],
    "écailles rouge braise / charbon / bleu nuit · ventre magma · ailes nuit · crête de lave · cornes de taureau · cape de chevalier · casque à cornes · bouclier · premium « Flamme bleue » (souffle légendaire) · maillot + bouée",
    CandidateA,
    true
  )}
  ${direction(
    "B",
    "« Roc », le dragon-dino cuirassé",
    "Une forteresse sur pattes, SANS ailes : plaques d'os sur le crâne, queue-massue à pointes, casque d'os de tricératops — la force tranquille.",
    [chip("#9AA8B8", "corps ardoise"), chip("#D9CDB4", "ventre os"), chip("#C8B89A", "plaques & massue"), chip("#8F8268", "contours os"), chip("#FFD54F", "clous d'or")].join(""),
    [
      "Dans son œuf fêlé — frimousse endormie visible, mini-massue qui dépasse déjà",
      "Sorti ! coquille-chapeau + morceau de coquille sur le flanc",
      "Premiers pas, petites bosses de cornes",
      "Plaques d'os sur le crâne",
      "Queue-massue à pointes !",
      "Armure du ventre",
      "Boum ! nuages de poussière sous les pattes",
      "Casque d'os (collerette de tricératops)",
      "Clous d'or sur le casque + aura de pierre",
      "Titan : casque géant, massue énorme, sol qui tremble, anneau de rochers",
    ],
    "carapace kaki / gris orage / brun terre · plaques dorées · massue double-pointes · plaques pointues · casque de gladiateur · bandana · ceinture de champion · premium « Armure de titan » · maillot + bouée",
    CandidateB,
    false
  )}
  ${direction(
    "C",
    "« Orage », le dragon des tempêtes",
    "Un dragon bleu nuit tout en angles : ailes déchiquetées, queue-éclair, charges électriques dorées — pas un nuage mignon en vue.",
    [chip("#7E8FB5", "corps nuit"), chip("#DDE3ED", "ventre acier"), chip("#5E6E96", "ailes sombres"), chip("#FFD54F", "éclairs d'or"), chip("#3A4766", "contours")].join(""),
    [
      "Dans son œuf fêlé — frimousse endormie visible, pointe de queue qui dépasse",
      "Sorti ! coquille-chapeau + morceau de coquille sur le flanc",
      "Premiers pas, petites bosses de cornes",
      "Ailes anguleuses déchiquetées",
      "Queue-éclair dorée",
      "Crête d'orage bleu nuit",
      "Charge ! zigzags dorés sur le corps + premières étincelles",
      "Cornes-éclairs dorées",
      "Aura d'orage + ailes soulignées d'or",
      "Titan du tonnerre : ailes géantes, méga souffle-éclair, sol d'orage, anneau d'éclairs",
    ],
    "écailles nuit noire / bleu électrique / gris acier · ventre orage · ailes violettes sombres · crête néon · cornes doubles · cape d'orage · lunettes de vitesse · premium « Foudre suprême » (méga éclair) · maillot + bouée",
    CandidateC,
    false
  )}

  <section class="qa">
  <h3>🔍 Annexe QA</h3>
  <p>Boucle visuelle : rendu vitest → capture Chrome headless → relecture de chaque PNG (bandes de croissance ×3, gros plans œuf, planche « face au roster » avec le renard en référence).</p>
  <ul>
    <li>✅ Chaque stade 3→9 montre un beat nouveau vs son voisin de gauche.</li>
    <li>✅ Les 3 silhouettes distinctes entre elles ET du roster (ailes-feu / sans ailes-massue / ailes anguleuses-éclairs).</li>
    <li>✅ Œuf du brief conservé de la v1 validée visuellement (frimousse + queue visibles au stade 0, coquilles portées au stade 1).</li>
    <li>✅ Palettes encore kawaii-pastel à côté du renard, mais plus profondes ; zéro gemme, zéro halo, étoiles remplacées par braises / rochers / éclairs.</li>
  </ul>
  <p class="iter"><b>2 itérations</b> avant le vert : (1) collision d'ids de dégradés quand plusieurs candidats partagent une page (le halo de sol de A teintait B en orange) → uid compteur global ; fumée des naseaux de A qui frôlait les yeux → dérive latérale. <b>Doutes restants</b> : la fumée de A reste discrète à 88 px ; les cornes de B très rondes tirent un peu vers l'hippopotame avant l'arrivée des plaques (stade 2) ; l'anneau d'éclairs de C au stade 9 est dense — à re-goûter sur fond réel du jeu.</p>
  </section>`;

  out("proposal.html", html);
});