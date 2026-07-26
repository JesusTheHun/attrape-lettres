/**
 * Proposal render harness (propose mode — designs only) — gated behind
 * PROPOSAL=rabbit so a plain `pnpm test` never runs it:
 *
 *   PROPOSAL=rabbit pnpm vitest run src/dev/proposals/rabbit/render.spec.tsx
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
import { P_ACCESSORY } from "./ids";
import type { PConfig, PRigProps } from "./rabbitParts";

const SPECIES = "rabbit";
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
  out("qa-candidate-a.html", page("Candidat A « Lune » — croissance", growthStrip(CandidateA)));
  out("qa-candidate-b.html", page("Candidat B « Trèfle » — croissance", growthStrip(CandidateB)));
  out("qa-candidate-c.html", page("Candidat C « Nuage » — croissance", growthStrip(CandidateC)));

  // 2) Babies close-up: lying stades 0-1, big, all three candidates.
  const babyCells = [CandidateA, CandidateB, CandidateC].flatMap((R, i) => [
    { svg: petSvg(R, 0, cfg(), 230), caption: `candidat ${"ABC"[i]} · stade 0` },
    { svg: petSvg(R, 1, cfg(), 230), caption: `candidat ${"ABC"[i]} · stade 1` },
  ]);
  out("qa-babies.html", page("Bébés couchés — stades 0 et 1", strip(babyCells)));

  // 3) Roster check: candidates next to the existing fox (palette must sit in
  //    the same kawaii family and the silhouettes must read apart).
  const roster = (s: number) =>
    strip([
      { svg: foxSvg(s), caption: `renard (existant) · stade ${s}` },
      { svg: petSvg(CandidateA, s), caption: `A Lune · stade ${s}` },
      { svg: petSvg(CandidateB, s), caption: `B Trèfle · stade ${s}` },
      { svg: petSvg(CandidateC, s), caption: `C Nuage · stade ${s}` },
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

  const html = `<!doctype html><meta charset="utf-8"><title>Proposition — Mon lapin</title><style>
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
  <h1>🐰 Mon lapin — proposition (ultra kawaii, non genré)</h1>
  <p class="meta">Brief : « rabbit — ultra kawaii, gender neutral » · 26/07/2026 · Design seulement — la garde-robe (~15 objets) sera dessinée sur le candidat validé.</p>
  <div class="banner">⭐ Recommandation : <b>Candidat A « Lune »</b> — la légende du lapin de lune donne l'arc mythique le plus fort (comme renard→kitsune), les grandes oreilles droites sont la silhouette lapin la plus lisible à 88 px, et ivoire + lavande + or est la palette la plus douce ET la plus neutre. B « Trèfle » (oreilles tombantes) est le plus câlin, C « Nuage » (oreille pliée) le plus espiègle.</div>
  ${direction(
    "A",
    "« Lune », le lapin de lune",
    "Un lapin ivoire aux grandes oreilles droites qui devient, stade après stade, le légendaire lapin de la lune : bouts d'oreilles trempés d'or, croissant sur le front, et une pleine lune de lumière au stade 9.",
    [chip("#F6EFE3", "corps ivoire"), chip("#FFFFFF", "ventre"), chip("#D9CCEE", "oreilles lavande"), chip("#FFD54F", "or lunaire"), chip("#EFE7FF", "lueur de lune")].join(""),
    [
      "Bébé endormi tout rond, oreilles couchées sur le dos",
      "Tête levée, les oreilles commencent à pointer",
      "Premiers bonds, oreilles droites",
      "Poitrail duveteux + l'intérieur des oreilles fleurit lavande",
      "Bouts d'oreilles trempés d'or lunaire",
      "Grand pompon de lune + son étoile d'or",
      "Croissant d'or sur le front + premières étincelles",
      "Des étoiles au bout des oreilles",
      "Un croissant de lune flotte près de sa tête (douce lueur)",
      "Pleine lune : aura lavande, lumière au sol, grande ronde d'étoiles",
    ],
    "pelage neige / gris souris / caramel · oreilles rose poudré / menthe · ventre crème · styles : oreilles pliées, petit pompon · accessoires : nœud céleste, écharpe étoilée, grelot de lune, premium « Poussière d'étoiles » (traînée d'étoiles filantes) · maillot + bouée",
    CandidateA,
    true
  )}
  ${direction(
    "B",
    "« Trèfle », le lapin porte-bonheur",
    "Un lapin sauge aux longues oreilles TOMBANTES (lop) : un trèfle pousse sur sa tête, les fleurs et les papillons le suivent, et au stade 9 la prairie fleurit sous ses pattes.",
    [chip("#E7F1DB", "corps sauge"), chip("#FFF9EE", "ventre crème"), chip("#F6CDBB", "oreilles pêche"), chip("#7BB661", "trèfle"), chip("#FFB4A2", "fleurs"), chip("#FFE082", "miel")].join(""),
    [
      "Bébé endormi, longues oreilles posées au sol",
      "Tête levée, oreilles tombantes de chaque côté",
      "Premiers bonds, oreilles qui ballottent",
      "Une pousse de trèfle porte-bonheur sur la tête",
      "Une fleur de pêcher s'ouvre au bout d'une oreille",
      "Collerette de pétales",
      "Deux papillons compagnons",
      "La pousse devient couronne fleurie (le trèfle au centre)",
      "Pluie de pétales + douce aura",
      "La prairie fleurit sous ses pattes : fleurs, herbe, ronde de pétales",
    ],
    "pelage crème / gris nuage / blanc · oreilles rose / bleuet · styles : une oreille levée, petit pompon · accessoires : chapeau de paille, carotte câline, sac de graines, premium « Jardin enchanté » (papillons dorés partout) · maillot + bouée",
    CandidateB,
    false
  )}
  ${direction(
    "C",
    "« Nuage », le lapin des nuages",
    "Un lapin blanc-bleu avec UNE oreille pliée (sa signature espiègle) et une queue-nuage en coton : il apprend à sauter si haut qu'au stade 9 il marche sur son propre tapis de nuages dorés.",
    [chip("#ECF3FB", "corps ciel pâle"), chip("#FFFFFF", "ventre & nuages"), chip("#C3DDF1", "oreilles ciel"), chip("#9CCFF0", "brise"), chip("#FFE082", "soleil d'or")].join(""),
    [
      "Bébé endormi, la petite oreille pliée déjà là",
      "Tête levée, une oreille droite, une pliée",
      "Premiers bonds",
      "Col de nuages autour du cou",
      "Queue-nuage XXL en coton",
      "Pouf-pouf ! petits nuages de saut aux pattes",
      "Tourbillons de brise + première étincelle",
      "Il marche sur son propre tapis de nuages",
      "Bouts d'oreilles trempés de ciel + deux nuages compagnons",
      "Fête du ciel : aura dorée, étincelles d'or, grand tapis de nuages",
    ],
    "pelage blanc pur / gris pluie / pêche d'aube · oreilles lilas / menthe · styles : deux oreilles droites, petite queue · accessoires : bonnet de nuit, cerf-volant, écharpe de vent, premium « Soleil d'or » (soleil levant + nuages dorés) · maillot + bouée",
    CandidateC,
    false
  )}

  <section class="qa">
  <h3>🔍 Annexe QA</h3>
  <p>Boucle visuelle : rendu vitest → capture Chrome headless → relecture de chaque PNG (bandes de croissance ×3, gros plans bébés stades 0-1, planche « face au roster » avec le renard en référence).</p>
  <ul>
    <li>Chaque stade 3→9 montre un beat nouveau vs son voisin de gauche.</li>
    <li>Les 3 silhouettes distinctes entre elles (droites / tombantes / une pliée) ET du roster licorne-chat-renard.</li>
    <li>Stades couchés 0-1 : bébé lové lisible, oreilles au repos, visage bien posé sur la tête penchée en avant.</li>
    <li>Palettes pastel-kawaii à côté du renard, toutes non genrées (ivoire-lavande-or / sauge-pêche / bleu ciel-or).</li>
  </ul>
  <p class="iter"><b>2 itérations</b> avant le vert : (1) le pompon flottait à mi-corps comme une excroissance → repositionné au bas de la croupe (les trois candidats) ; les oreilles tombantes de B, dessinées derrière la tête et de la même couleur, disparaissaient (la tête lisait « œuf ») → oreilles élargies, allongées et passées DEVANT la tête, intérieur pêche face au lecteur ; oreilles couchées des bébés A allongées ; intérieur d'oreille de A quasi-uni avant le stade 3 pour que le beat « fleurit lavande » soit réel ; bouts de ciel de C fonçés (#7FB8E8), trop pâles sur oreille bleu pâle. <b>Doutes restants</b> : l'étoile d'or du pompon de A (beat 5) est discrète à 88 px (le saut de taille du pompon porte le beat) ; la ronde de pétales de B au stade 9 frôle les oreilles ; le beat 4 de C (queue-nuage) est le plus doux de son arc.</p>
  </section>`;

  out("proposal.html", html);
});

/* ------------------------------------------------------------------------- */
/* Items run — wardrobe QA pages for the PICKED design (A « Lune »).          */
/* ------------------------------------------------------------------------- */

const COLOUR_ROWS: Array<[string, string, string, number]> = [
  ["bodyColor", "#D6D3DE", "Pelage gris souris", 4],
  ["bodyColor", "#EFC9A0", "Pelage caramel", 4],
  ["bodyColor", "#F8D3BC", "Pelage pêche", 4],
  ["bodyColor", "#E4DCF2", "Pelage lilas", 4],
  ["innerEarColor", "#F5A8C0", "Oreilles rose poudré", 4],
  ["innerEarColor", "#A8DDB8", "Oreilles menthe", 4],
  ["bellyColor", "#FFE8BC", "Ventre crème", 4],
];
const STYLE_ROWS: Array<[string, string, string, number[]]> = [
  ["earStyle", "pliees", "Oreilles pliées", [2, 4, 9]],
  ["tailStyle", "etoile", "Queue étoile", [1, 5, 9]],
  ["furPattern", "flocons", "Flocons d'étoiles", [1, 4, 9]],
];

it.runIf(RUN)("renders items QA pages", () => {
  // One page per accessory, worn at EVERY stade (the placement spec).
  for (const [key, id] of Object.entries(P_ACCESSORY.rabbit)) {
    out(
      `qa-acc-${key}.html`,
      page(`Accessoire — ${key}`, strip(STAGES.map((s) => ({ svg: petSvg(CandidateA, s, cfg({ accessories: [id] })), caption: `stade ${s}` }))))
    );
  }
  // Colour + style variants vs the factory look.
  const colourBoard = COLOUR_ROWS.map(([slot, value, label, stade]) => {
    const pairHtml = strip([
      { svg: petSvg(CandidateA, stade), caption: "d'origine" },
      { svg: petSvg(CandidateA, stade, cfg({ colors: { [slot]: value } })), caption: label },
    ]);
    return `<h2>${label} (stade ${stade})</h2>${pairHtml}`;
  }).join("");
  const styleBoard = STYLE_ROWS.map(([slot, value, label, stades]) => {
    const cells = stades.flatMap((s) => [
      { svg: petSvg(CandidateA, s), caption: `d'origine · stade ${s}` },
      { svg: petSvg(CandidateA, s, cfg({ styles: { [slot]: value } })), caption: `${label} · stade ${s}` },
    ]);
    return `<h2>${label}</h2>${strip(cells)}`;
  }).join("");
  out("qa-items-variants.html", page("Couleurs & styles — variantes vs origine", colourBoard + styleBoard));
});

/* Blind legibility pairs — NEUTRAL indices, no titles/captions (the guesser
 * must see hint-free pixels). Index ↔ item map lives only in this table. */
const BLIND_PAIRS: Array<{ n: string; config: PConfig; stade: number }> = [
  { n: "01", config: cfg({ accessories: [P_ACCESSORY.rabbit.bow] }), stade: 1 },
  { n: "02", config: cfg({ accessories: [P_ACCESSORY.rabbit.bow] }), stade: 4 },
  { n: "03", config: cfg({ accessories: [P_ACCESSORY.rabbit.nightcap] }), stade: 1 },
  { n: "04", config: cfg({ accessories: [P_ACCESSORY.rabbit.nightcap] }), stade: 4 },
  { n: "05", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimsuit] }), stade: 1 },
  { n: "06", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimsuit] }), stade: 4 },
  { n: "07", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimRing] }), stade: 1 },
  { n: "08", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimRing] }), stade: 4 },
  { n: "09", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 4 },
  { n: "10", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 7 },
  { n: "11", config: cfg({ styles: { earStyle: "pliees" } }), stade: 4 },
  { n: "12", config: cfg({ styles: { tailSize: "petit" } }), stade: 6 },
  { n: "13", config: cfg({ styles: { furPattern: "flocons" } }), stade: 4 },
  // Round 2 — redesigned items get FRESH indices (and fresh guessers).
  { n: "14", config: cfg({ accessories: [P_ACCESSORY.rabbit.nightcap] }), stade: 1 },
  { n: "15", config: cfg({ accessories: [P_ACCESSORY.rabbit.nightcap] }), stade: 4 },
  { n: "16", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimsuit] }), stade: 1 },
  { n: "17", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimsuit] }), stade: 4 },
  { n: "18", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 4 },
  { n: "19", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 7 },
  { n: "20", config: cfg({ styles: { earStyle: "pliees" } }), stade: 4 },
  { n: "21", config: cfg({ styles: { tailSize: "petit" } }), stade: 5 },
  { n: "22", config: cfg({ styles: { furPattern: "flocons" } }), stade: 4 },
  // Round 3 — swimsuit straps, stardust scatter, longer ear fold, star tail
  // (replaces the illegible "Petit pompon"), deeper flecks.
  { n: "23", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimsuit] }), stade: 1 },
  { n: "24", config: cfg({ accessories: [P_ACCESSORY.rabbit.swimsuit] }), stade: 4 },
  { n: "25", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 4 },
  { n: "26", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 7 },
  { n: "27", config: cfg({ styles: { earStyle: "pliees" } }), stade: 4 },
  { n: "28", config: cfg({ styles: { tailStyle: "etoile" } }), stade: 5 },
  { n: "29", config: cfg({ styles: { furPattern: "flocons" } }), stade: 4 },
  // Round 4 — stardust re-paired at stade 5: stade 7's own free star-tipped
  // ears confounded the guess (skill warning about spectacle stades).
  { n: "30", config: cfg({ accessories: [P_ACCESSORY.rabbit.stardust] }), stade: 5 },
  // Fold re-paired at stade 3 (plain lavender ears — gold dips at 4 turned the
  // shape change into a colour riddle); star tail redrawn over the hip.
  { n: "31", config: cfg({ styles: { earStyle: "pliees" } }), stade: 3 },
  { n: "32", config: cfg({ styles: { tailStyle: "etoile" } }), stade: 5 },
];

it.runIf(RUN)("renders blind pair pages", () => {
  for (const { n, config, stade } of BLIND_PAIRS) {
    const pair = strip([
      { svg: petSvg(CandidateA, stade, cfg(), 240), caption: "" },
      { svg: petSvg(CandidateA, stade, config, 240), caption: "" },
    ]);
    out(`qa-blind-${n}.html`, `<!doctype html><meta charset="utf-8"><style>
      body{background:#FFF7EC;margin:24px}
      .strip{display:flex;gap:20px;align-items:flex-end}
      .cell{background:#fff;border-radius:14px;padding:85px 14px 12px}
    </style>${pair}`);
  }
});

/* ------------------------------------------------------------------------- */
/* The deliverable — items.html (wardrobe for A « Lune »).                    */
/* ------------------------------------------------------------------------- */

interface ItemCard {
  name: string;
  emoji: string;
  cost: number;
  minStage?: number;
  reason: string;
  cells: { svg: string; caption: string }[];
}

const itemCard = (card: ItemCard) =>
  `<div class="card"><h4>${card.emoji} ${card.name} <span class="cost">${card.cost} pts</span>${
    card.minStage ? `<span class="gate">🔒 stade ${card.minStage}</span>` : ""
  }</h4><p class="why">${card.reason}</p>${strip(card.cells)}</div>`;

it.runIf(RUN)("writes items.html", () => {
  const A = P_ACCESSORY.rabbit;
  const pair = (config: PConfig, stade: number, label: string) => [
    { svg: petSvg(CandidateA, stade, cfg(), 130), caption: `d'origine · stade ${stade}` },
    { svg: petSvg(CandidateA, stade, config, 130), caption: `${label} · stade ${stade}` },
  ];
  const worn = (id: string, stades: Array<[number, string]>) =>
    stades.map(([s, cap]) => ({ svg: petSvg(CandidateA, s, cfg({ accessories: [id] }), 130), caption: cap }));
  const styled = (slot: string, value: string, stades: Array<[number, string]>) =>
    stades.map(([s, cap]) => ({ svg: petSvg(CandidateA, s, cfg({ styles: { [slot]: value } }), 130), caption: cap }));

  const colourMeta: Record<string, { emoji: string; cost: number; minStage?: number; reason: string }> = {
    "Pelage gris souris": { emoji: "🐭", cost: 18, reason: "Recolore tout le corps, visible dès le bébé." },
    "Pelage caramel": { emoji: "🍮", cost: 18, reason: "Recolore tout le corps, visible dès le bébé." },
    "Pelage pêche": { emoji: "🍑", cost: 18, reason: "Recolore tout le corps, visible dès le bébé." },
    "Pelage lilas": { emoji: "💜", cost: 20, reason: "Recolore tout le corps, visible dès le bébé." },
    "Oreilles rose poudré": { emoji: "🌸", cost: 20, minStage: 3, reason: "L'intérieur des oreilles ne « fleurit » qu'au stade 3 — avant, la couleur serait presque invisible." },
    "Oreilles menthe": { emoji: "🌿", cost: 20, minStage: 3, reason: "Même raison : la couleur d'oreilles s'ouvre au stade 3." },
    "Ventre crème": { emoji: "🍦", cost: 16, reason: "Réchauffe le ventre blanc, visible à tous les stades." },
  };
  const colours: ItemCard[] = COLOUR_ROWS.map(([slot, value, label, stade]) => ({
    name: label,
    ...colourMeta[label],
    cells: pair(cfg({ colors: { [slot]: value } }), colourMeta[label].minStage ? 4 : stade, label.split(" ").slice(-1)[0]),
  }));

  const styles: ItemCard[] = [
    {
      name: "Oreilles pliées",
      emoji: "🐰",
      cost: 45,
      minStage: 2,
      reason: "Les oreilles sont couchées sur le dos avant le stade 2 — le pli ne se verrait pas.",
      cells: styled("earStyle", "pliees", [[2, "stades 2-3"], [4, "stades 4-6 · bouts d'or pliés"], [9, "stades 7-9"]]),
    },
    {
      name: "Queue étoile",
      emoji: "🌟",
      cost: 50,
      reason: "Le pompon devient une étoile d'or — assortie au lapin de lune, visible même couché.",
      cells: styled("tailStyle", "etoile", [[0, "stades 0-1 · couché"], [2, "stades 2-3"], [5, "stades 4-6"], [9, "stades 7-9"]]),
    },
    {
      name: "Flocons d'étoiles",
      emoji: "❄️",
      cost: 48,
      reason: "Des petites étoiles lavande saupoudrées sur le corps, dès le bébé.",
      cells: styled("furPattern", "flocons", [[1, "stades 0-1 · couché"], [4, "stades 2-6"], [9, "stades 7-9"]]),
    },
  ];

  const accessories: ItemCard[] = [
    {
      name: "Nœud étoilé",
      emoji: "🎀",
      cost: 45,
      reason: "Un nœud bleu nuit à étoile d'or, posé sur la gorge par les ancres partagées — jamais sur le visage.",
      cells: worn(A.bow, [[0, "stade 0"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Bonnet de nuit",
      emoji: "🌙",
      cost: 60,
      reason: "Un bonnet de dodo qui retombe sur le côté, pompon au bout ; les grandes oreilles dépassent.",
      cells: worn(A.nightcap, [[0, "stade 0"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Poussière d'étoiles",
      emoji: "🌠",
      cost: 200,
      minStage: 4,
      reason: "Le premium : de la poussière d'étoiles d'or tourne autour de lui dès le stade 4, s'épaissit au stade 7, et une étoile filante le rejoint au stade 9.",
      cells: worn(A.stardust, [[4, "stades 4-6 · poussière d'or"], [7, "stades 7-8 · plus dense"], [9, "stade 9 · étoile filante"]]),
    },
    {
      name: "Maillot de bain",
      emoji: "🩱",
      cost: 60,
      minStage: 2,
      reason: "Tradition des espèces — maillot rayé à bretelles, porté debout (stade 2), étoile de champion au stade 6.",
      cells: worn(A.swimsuit, [[2, "stades 2-3"], [4, "stades 4-5"], [6, "stades 6-9 · étoile"]]),
    },
    {
      name: "Bouée",
      emoji: "🛟",
      cost: 75,
      reason: "Tradition des espèces : le bébé couché dort DESSUS, puis il s'assoit dedans ; canard au stade 7.",
      cells: worn(A.swimRing, [[0, "stade 0 · il dort dessus"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9 · canard"]]),
    },
  ];

  const html = `<!doctype html><meta charset="utf-8"><title>Garde-robe — Mon lapin « Lune »</title><style>
    body{font-family:ui-rounded,'SF Pro Rounded',system-ui,sans-serif;background:#FFF7EC;color:#5A3A1E;margin:28px;max-width:1240px}
    h1{font-size:26px;margin-bottom:4px} h3{font-size:18px;margin:26px 0 10px} h4{font-size:15px;margin:0 0 4px}
    .meta{color:#9A7A5A;font-weight:700;font-size:13px;margin-bottom:18px}
    .hero{display:flex;align-items:center;gap:16px;background:#FFFDF6;border:2px solid #F2C14E;border-radius:16px;padding:10px 16px;margin:0 0 22px;font-weight:800}
    .strip{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-end;margin:10px 0}
    .cell{background:#fff;border-radius:14px;padding:78px 10px 8px;text-align:center;box-shadow:0 1px 4px rgba(0,0,0,.08)}
    .cell p{font-size:11.5px;font-weight:800;margin:6px 0 0;max-width:150px}
    .card{background:rgba(255,255,255,.55);border-radius:16px;padding:12px 14px;margin:0 0 16px}
    .cost{background:#DDF3D8;border-radius:999px;padding:2px 8px;font-size:12px;margin-left:8px}
    .gate{background:#FFE1EC;border-radius:999px;padding:2px 8px;font-size:12px;margin-left:6px}
    .why{font-size:13px;color:#7A6248;margin:2px 0 0}
    .qa p,.qa li{font-size:13.5px}
    table.blind{border-collapse:collapse;font-size:12.5px;margin:8px 0}
    table.blind th,table.blind td{border:1px solid #EAD9BF;padding:4px 8px;text-align:left;background:#fff}
    table.blind th{background:#FFF1DC}
  </style>
  <h1>🐰 Garde-robe du lapin « Lune »</h1>
  <p class="meta">Design validé : candidat A « Lune » · 15 objets, tous implémentés et rendus sur son rig · 26/07/2026</p>
  <div class="hero"><div>${petSvg(CandidateA, 4, cfg(), 120)}</div><div>Le voici au stade 4 — thème de la garde-robe : petit gardien de la lune. Douceurs lavande et or, bonnet de dodo, queue étoile, et de la poussière d'étoiles en premium.</div></div>

  <h3>🎨 Couleurs (7) — écrites dans <code>config.colors</code></h3>
  ${colours.map(itemCard).join("")}
  <h3>💇 Styles (3) — écrits dans <code>config.styles</code></h3>
  ${styles.map(itemCard).join("")}
  <h3>🎒 Accessoires (5) — dont la paire de bain traditionnelle et 1 premium</h3>
  ${accessories.map(itemCard).join("")}

  <section class="qa">
  <h3>🔍 Annexe QA</h3>
  <p><b>Boucle placement</b> : rendu vitest → capture Chrome headless → relecture de chaque PNG (5 planches accessoire × stades 0→9, planche couleurs/styles). Nœud sur la gorge à chaque pose (ancres partagées, profil chat 0.94) ; bonnet posé sur le dôme, oreilles dépassent ; rien ne couvre les yeux ; tout suit la tête qui rétrécit. <b>2 itérations</b> de placement (pompon repositionné bas de croupe ; profondeur des couleurs d'oreilles/ventre).</p>
  <p><b>Test de lisibilité en aveugle</b> (obligatoire) : chaque accessoire/style montré à un agent NEUF (haiku) en paire sans/avec, fichiers neutres <code>qa-blind-NN.png</code>, une seule tentative. Verdicts verbatim :</p>
  <table class="blind"><tr><th>Objet</th><th>Paire</th><th>Réponse verbatim (agent neuf)</th><th>Verdict</th><th>Refontes</th></tr>
  <tr><td rowspan="2">🎀 Nœud étoilé</td><td>couché (1)</td><td>« a blue bow tie around the creature's neck »</td><td>✅</td><td rowspan="2">0</td></tr>
  <tr><td>debout (4)</td><td>« a blue bow tie around the neck »</td><td>✅</td></tr>
  <tr><td rowspan="2">🌙 Bonnet de nuit</td><td>couché (1)</td><td>v1 « a blue nightcap with a star » · v2 « a blue nightcap with a white stripe »</td><td>✅</td><td rowspan="2">1 — v1 debout lu « blue crown » (cône symétrique entre les 2 oreilles droites) → pointe retombante SOUS le bord + pompon pendant</td></tr>
  <tr><td>debout (4)</td><td>v1 « a blue crown » ❌ · v2 « a blue nightcap placed on the companion's head »</td><td>✅</td></tr>
  <tr><td rowspan="2">🩱 Maillot de bain</td><td>couché (1)</td><td>v1 « a blue backpack » · v2 « striped shirt » · v3 « striped shell or backpack »</td><td>❌ assumé</td><td rowspan="2">3 — culotte réduite, maillot descendu aux hanches, bretelles ajoutées ; sans eau il se lit « vêtement rayé / marin ». Tradition inter-espèces conservée, gate stade 2 (plus de vue couchée), limite documentée</td></tr>
  <tr><td>debout (4)</td><td>v1 « striped shirt » · v2 « striped scarf » · v3 « striped sailor outfit/vest »</td><td>❌ assumé</td></tr>
  <tr><td rowspan="2">🛟 Bouée</td><td>couché (1)</td><td>« a yellow and white striped inflatable swim ring or floatie tube »</td><td>✅</td><td rowspan="2">0</td></tr>
  <tr><td>debout (4)</td><td>« a yellow and white striped flotation ring around the waist »</td><td>✅</td></tr>
  <tr><td rowspan="2">🌠 Poussière d'étoiles</td><td>stade 4</td><td>v1 « magical sparkle effect (small stars) » ✅ · v2 « golden crown » ❌ · v3 « magical sparkle/star aura glowing around »</td><td>✅</td><td rowspan="2">2 — la traînée de comète lue « baguette magique » puis « couronne » → poussière d'or éparpillée AUTOUR du corps, zone au-dessus de la tête interdite ; paire 2 déplacée du stade 7 (ses étoiles d'oreilles gratuites parasitaient) au stade 5 : « golden stars/diamonds floating around it »</td></tr>
  <tr><td>stade 5</td><td>« sparkle accessory — golden stars/diamonds floating around it »</td><td>✅</td></tr>
  <tr><td>🐰 Oreilles pliées</td><td>debout (3)</td><td>v1 « ears are shorter » · v2 « ears changed colour » · v3 (stade 3, sans bouts d'or) « ears changed from upright and long to shorter and floppy »</td><td>✅</td><td>2 — pli 58°→100°, pointe pliée allongée ; paire déplacée au stade 3 (les bouts d'or du stade 4 en faisaient une devinette de couleurs)</td></tr>
  <tr><td>🌟 Queue étoile</td><td>debout (5)</td><td>« holding a star instead of a pouch »</td><td>✅ avec réserve</td><td>2 — remplace « Petit pompon » (2 échecs : « queue déplacée », « objet perdu ») ; étoile redessinée PAR-DESSUS la hanche ; l'étoile est nommée, mais lue « tenue » plutôt que « queue » (pose de face)</td></tr>
  <tr><td>❄️ Flocons d'étoiles</td><td>debout (4)</td><td>v1 « pupils smaller » · v2 « ears tilted » · v3 « small sparkle/star shapes scattered across the companion's chest »</td><td>✅</td><td>2 — étoiles agrandies ×2, lavande foncé #B8A6E0, étalées corps + ventre</td></tr>
  </table>
  <p class="iter"><b>Doutes restants</b> : le maillot sans eau se lit « vêtement rayé / marin » même après 3 versions (bretelles gardées : c'est la plus lisible) — c'est la tradition inter-espèces, l'enfant l'achète sous son nom « Maillot de bain » 🩱 ; les oreilles pliées perdent les étoiles de bouts d'oreilles aux stades 7-9 (elles pointent vers le bas) — beat remplacé par les bouts d'or pliés ; la poussière d'or du premium cohabite avec les étincelles gratuites des stades 6+ (l'or est plus dense et plus foncé).</p>
  </section>`;

  out("items.html", html);
});
