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
import { P_ACCESSORY } from "./ids";
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
/* Items run — wardrobe QA pages for the PICKED design (A « Braise »).        */
/* ------------------------------------------------------------------------- */

const COLOUR_ROWS: Array<[string, string, string, number]> = [
  ["bodyColor", "#D97B6C", "Écailles rouge braise", 4],
  ["bodyColor", "#8A8D96", "Écailles charbon", 4],
  ["bodyColor", "#7E8FB5", "Écailles bleu nuit", 4],
  ["bodyColor", "#B08968", "Écailles brun terre", 4],
  ["bellyColor", "#FFB27A", "Ventre magma", 5],
  ["wingColor", "#5F6470", "Ailes nuit", 4],
  ["wingColor", "#F2C14E", "Ailes dorées", 4],
];
const STYLE_ROWS: Array<[string, string, string, number[]]> = [
  ["hornStyle", "bull", "Cornes de taureau", [3, 5, 7, 9]],
  ["crestStyle", "lava", "Crête de lave", [4, 6, 9]],
  ["tailSize", "short", "Petite queue", [2, 5, 9]],
];

it.runIf(RUN)("renders items QA pages", () => {
  // One page per accessory, worn at EVERY stade (the placement spec).
  for (const [key, id] of Object.entries(P_ACCESSORY.dragon)) {
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

/* ------------------------------------------------------------------------- */
/* The deliverable — items.html (wardrobe for A « Braise »).                  */
/* ------------------------------------------------------------------------- */

interface ItemCard {
  name: string;
  emoji: string;
  cost: number;
  minStage?: number;
  reason: string;
  cells: { svg: string; caption: string }[];
}

const itemCard = (it2: ItemCard) =>
  `<div class="card"><h4>${it2.emoji} ${it2.name} <span class="cost">${it2.cost} pts</span>${
    it2.minStage ? `<span class="gate">🔒 stade ${it2.minStage}</span>` : ""
  }</h4><p class="why">${it2.reason}</p>${strip(it2.cells)}</div>`;

it.runIf(RUN)("writes items.html", () => {
  const A = P_ACCESSORY.dragon;
  const pair = (config: PConfig, stade: number, label: string) => [
    { svg: petSvg(CandidateA, stade, cfg(), 130), caption: `d'origine · stade ${stade}` },
    { svg: petSvg(CandidateA, stade, config, 130), caption: `${label} · stade ${stade}` },
  ];
  const worn = (id: string, stades: Array<[number, string]>) =>
    stades.map(([s, cap]) => ({ svg: petSvg(CandidateA, s, cfg({ accessories: [id] }), 130), caption: cap }));

  const colourReason: Record<string, string> = {
    bodyColor: "Recolore tout le corps, visible dès l'œuf.",
    bellyColor: "Le ventre est caché dans l'œuf au stade 0.",
    wingColor: "Les ailes poussent au stade 3.",
  };
  const colourEmoji: Record<string, string> = {
    "Écailles rouge braise": "🔥",
    "Écailles charbon": "🪨",
    "Écailles bleu nuit": "🌙",
    "Écailles brun terre": "🤎",
    "Ventre magma": "🌋",
    "Ailes nuit": "🦇",
    "Ailes dorées": "⭐",
  };
  const colourCost: Record<string, number> = {
    "Écailles rouge braise": 20,
    "Écailles charbon": 20,
    "Écailles bleu nuit": 20,
    "Écailles brun terre": 18,
    "Ventre magma": 22,
    "Ailes nuit": 24,
    "Ailes dorées": 24,
  };
  const colourGate: Record<string, number | undefined> = { bellyColor: 1, wingColor: 3 };

  const colours: ItemCard[] = COLOUR_ROWS.map(([slot, value, label, stade]) => ({
    name: label,
    emoji: colourEmoji[label],
    cost: colourCost[label],
    minStage: colourGate[slot],
    reason: colourReason[slot],
    cells: pair(cfg({ colors: { [slot]: value } }), stade, label.split(" ").slice(-1)[0]),
  }));

  const styles: ItemCard[] = [
    {
      name: "Cornes de taureau",
      emoji: "🐂",
      cost: 45,
      minStage: 3,
      reason: "Les vraies cornes poussent au stade 3 ; la courbe s'élargit avec elles.",
      cells: [3, 5, 7, 9].map((s) => ({ svg: petSvg(CandidateA, s, cfg({ styles: { hornStyle: "bull" } }), 130), caption: `taureau · stade ${s}` })),
    },
    {
      name: "Crête de lave",
      emoji: "🌋",
      cost: 50,
      minStage: 4,
      reason: "La crête apparaît au stade 4 ; version braise incandescente.",
      cells: [...pair(cfg({ styles: { crestStyle: "lava" } }), 4, "lave"), ...pair(cfg({ styles: { crestStyle: "lava" } }), 9, "lave").slice(1)],
    },
    {
      name: "Petite queue",
      emoji: "🦎",
      cost: 32,
      minStage: 1,
      reason: "Au stade 0 la queue dépasse à peine de l'œuf — identique dans les deux tailles.",
      cells: [...pair(cfg({ styles: { tailSize: "short" } }), 5, "petite"), ...pair(cfg({ styles: { tailSize: "short" } }), 9, "petite").slice(1)],
    },
  ];

  const accessories: ItemCard[] = [
    {
      name: "Cape de chevalier",
      emoji: "🦸",
      cost: 55,
      reason: "Attachée par un fermoir d'or sur la gorge ; drapée derrière lui — et autour de l'œuf au stade 0.",
      cells: worn(A.cape, [[0, "stade 0 · autour de l'œuf"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Casque de chevalier",
      emoji: "🪖",
      cost: 75,
      reason: "Dôme d'acier à plumet rouge posé sur le crâne ; aux grands stades ses propres cornes le transpercent.",
      cells: worn(A.helmet, [[0, "stade 0 · sur l'œuf"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Bouclier",
      emoji: "🛡️",
      cost: 60,
      reason: "Un écu bleu à flamme d'or, posé contre son flanc — il grandit avec lui.",
      cells: worn(A.shield, [[0, "stade 0 · contre l'œuf"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Flamme bleue",
      emoji: "💙",
      cost: 200,
      minStage: 4,
      reason: "Le premium : un souffle légendaire bleu dès le stade 4, double souffle + braises bleues au stade 7, tempête bleue au stade 9.",
      cells: worn(A.blueFlame, [[4, "stades 4-6 · souffle bleu"], [7, "stades 7-8 · double souffle"], [9, "stade 9 · tempête bleue"]]),
    },
    {
      name: "Maillot de bain",
      emoji: "🩱",
      cost: 60,
      reason: "Tradition des espèces : l'œuf le porte, culotte au stade 1, maillot rayé debout, étoile de champion au stade 6.",
      cells: worn(A.swimsuit, [[0, "stade 0 · l'œuf le porte"], [1, "stade 1 · culotte"], [2, "stades 2-3"], [4, "stades 4-5"], [6, "stades 6-9 · étoile"]]),
    },
    {
      name: "Bouée",
      emoji: "🛟",
      cost: 75,
      reason: "Tradition des espèces : l'œuf flotte dedans, puis il s'assoit dans la bouée ; canard au stade 7.",
      cells: worn(A.swimRing, [[0, "stade 0 · l'œuf flotte"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9 · canard"]]),
    },
  ];

  const html = `<!doctype html><meta charset="utf-8"><title>Garde-robe — Mon dragon « Braise »</title><style>
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
  </style>
  <h1>🐉 Garde-robe du dragon « Braise »</h1>
  <p class="meta">Design validé : candidat A « Braise » (proposition v2) · 16 objets, tous implémentés et rendus sur son rig · 26/07/2026</p>
  <div class="hero"><div>${petSvg(CandidateA, 4, cfg(), 120)}</div><div>Le voici au stade 4 — thème de la garde-robe : petit chevalier de feu. Couleurs profondes, cornes de taureau, cape/casque/bouclier, et un souffle bleu légendaire en premium.</div></div>

  <h3>🎨 Couleurs (7) — écrites dans <code>config.colors</code></h3>
  ${colours.map(itemCard).join("")}
  <h3>💇 Styles (3) — écrits dans <code>config.styles</code></h3>
  ${styles.map(itemCard).join("")}
  <h3>🎒 Accessoires (6) — dont la paire de bain traditionnelle et 1 premium</h3>
  ${accessories.map(itemCard).join("")}

  <section class="qa">
  <h3>🔍 Annexe QA</h3>
  <p>Boucle visuelle : rendu vitest → capture Chrome headless → relecture de chaque PNG (6 planches accessoire × stades 0→9, planche couleurs/styles).</p>
  <ul>
    <li>✅ Fermoir de cape sur la gorge à chaque pose (ancres partagées), jamais sur le visage — y compris couché 0-1.</li>
    <li>✅ Casque posé sur le dôme (touche, ne flotte pas) ; rien ne couvre les yeux ; les cornes des stades 7-9 dépassent du casque volontairement.</li>
    <li>✅ Bouclier au sol contre le flanc, suit la taille du corps ; l'œuf y a droit aussi.</li>
    <li>✅ Chaque couleur/style évident d'un coup d'œil (planches avant/après).</li>
    <li>✅ Gates : ventre (1, caché dans l'œuf), ailes + cornes (3), crête + flamme bleue (4).</li>
  </ul>
  <p class="iter"><b>2 itérations</b> avant le vert : (1) cape et bouée invisibles au stade 0 — l'œuf élargi de la v2 les cachait entièrement → cape élargie, bouée agrandie autour de la coquille ; (2) le souffle bleu fuyait aux stades 2-3 (<code>ramp()</code> borne à son premier arrêt sous le stade 4) → gate explicite <code>stage >= 4</code>. <b>Doutes restants</b> : au stade 1 le casque recouvre la coquille-chapeau (les deux se disputent le dôme) ; le fermoir de cape frôle le menton aux stades couchés ; « Ailes nuit » sur « Écailles charbon » (si les deux sont achetés) sera peu contrasté — à vérifier au ship.</p>
  </section>`;

  out("items.html", html);
});