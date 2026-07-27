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
  ["hornColor", "#F2C14E", "Cornes d'or", 4],
  ["hornColor", "#4E5560", "Cornes noires", 4],
];
const STYLE_ROWS: Array<[string, string, string, number[]]> = [
  ["hornStyle", "double", "Cornes doubles", [3, 5, 7, 9]],
  ["crestStyle", "lava", "Crête de lave", [4, 6, 9]],
  ["tailStyle", "club", "Queue massue", [0, 1, 5, 9]],
  ["tailStyle", "flame", "Queue de feu", [2, 5, 9]],
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

  // Close-ups of the fiddly bits (small parts unreadable on the 130px boards).
  const zoom = (label: string, config: PConfig, stades: number[]) =>
    `<h2>${label}</h2>${strip(stades.map((s) => ({ svg: petSvg(CandidateA, s, config, 240), caption: `stade ${s}` })))}`;
  out(
    "qa-zoom.html",
    page(
      "Zooms — pièces fines",
      zoom("Cornes doubles", cfg({ styles: { hornStyle: "double" } }), [3, 5, 7]) +
        zoom("Cornes noires / d'or", cfg({ colors: { hornColor: "#4E5560" } }), [2, 4]) +
        zoom("Cornes d'or", cfg({ colors: { hornColor: "#F2C14E" } }), [2, 4]) +
        zoom("Queue massue", cfg({ styles: { tailStyle: "club" } }), [0, 1, 5]) +
        zoom("Queue de feu", cfg({ styles: { tailStyle: "flame" } }), [2, 5, 9]) +
        zoom("Collier de croc", cfg({ accessories: [P_ACCESSORY.dragon.fang] }), [2, 4, 7]) +
        zoom("Croc + cape ensemble", cfg({ accessories: [P_ACCESSORY.dragon.fang, P_ACCESSORY.dragon.cape] }), [4, 7]) +
        zoom("Lunettes d'aviateur", cfg({ accessories: [P_ACCESSORY.dragon.goggles] }), [3, 5, 8]) +
        zoom("Petit trésor", cfg({ accessories: [P_ACCESSORY.dragon.treasure] }), [0, 3, 5]) +
        zoom("Coffre au trésor (stades 7-9)", cfg({ accessories: [P_ACCESSORY.dragon.treasure] }), [7, 8, 9])
    )
  );
});

it.runIf(RUN)("renders tresor QA page", () => {
  // Placement spec: every stade at review size, plus the shop-size row (88 px)
  // for the blind test — a treasure must read as one WITHOUT label.
  const A3 = P_ACCESSORY.dragon;
  const tcfg = cfg({ accessories: [A3.treasure] });
  const big = strip(STAGES.map((s) => ({ svg: petSvg(CandidateA, s, tcfg), caption: `stade ${s}` })));
  const small = strip([1, 4, 7, 9].map((s) => ({ svg: petSvg(CandidateA, s, tcfg, 88), caption: `88px · stade ${s}` })));
  out("qa-tresor.html", page("Trésor — placement", big + `<h2>Taille boutique (88 px)</h2>` + small));
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
    hornColor: "Les bosses de cornes pointent au stade 2 ; la couleur grandit avec elles.",
  };
  const colourEmoji: Record<string, string> = {
    "Écailles rouge braise": "🔥",
    "Écailles charbon": "🪨",
    "Écailles bleu nuit": "🌙",
    "Écailles brun terre": "🤎",
    "Ventre magma": "🌋",
    "Ailes nuit": "🦇",
    "Ailes dorées": "⭐",
    "Cornes d'or": "✨",
    "Cornes noires": "🖤",
  };
  const colourCost: Record<string, number> = {
    "Écailles rouge braise": 20,
    "Écailles charbon": 20,
    "Écailles bleu nuit": 20,
    "Écailles brun terre": 18,
    "Ventre magma": 22,
    "Ailes nuit": 24,
    "Ailes dorées": 24,
    "Cornes d'or": 22,
    "Cornes noires": 22,
  };
  const colourGate: Record<string, number | undefined> = { bellyColor: 1, wingColor: 3, hornColor: 2 };

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
      name: "Cornes doubles",
      emoji: "🐉",
      cost: 45,
      minStage: 3,
      reason: "Deux paires de cornes — la grande + une petite devant, la couronne naturelle des dragons. Dès les vraies cornes (stade 3).",
      cells: [3, 5, 7, 9].map((s) => ({ svg: petSvg(CandidateA, s, cfg({ styles: { hornStyle: "double" } }), 130), caption: `doubles · stade ${s}` })),
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
      name: "Queue massue",
      emoji: "🔨",
      cost: 40,
      minStage: 1,
      reason: "La pointe devient une boule à piques — mini-massue qui dépasse déjà de l'œuf, énorme au stade 9.",
      cells: [0, 2, 5, 9].map((s) => ({ svg: petSvg(CandidateA, s, cfg({ styles: { tailStyle: "club" } }), 130), caption: `massue · stade ${s}` })),
    },
    {
      name: "Queue de feu",
      emoji: "☄️",
      cost: 55,
      minStage: 2,
      reason: "Une flamme brûle au bout de la queue dès qu'il marche (stade 2) ; elle grandit avec lui.",
      cells: [2, 5, 9].map((s) => ({ svg: petSvg(CandidateA, s, cfg({ styles: { tailStyle: "flame" } }), 130), caption: `feu · stade ${s}` })),
    },
  ];

  const accessories: ItemCard[] = [
    {
      name: "Cape de chevalier",
      emoji: "🦸",
      cost: 55,
      minStage: 2,
      reason: "Réservée au dragon qui tient debout (stade 2) — fermoir d'or sur la gorge, drapée derrière lui.",
      cells: worn(A.cape, [[2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Lunettes d'aviateur",
      emoji: "🥽",
      cost: 65,
      minStage: 3,
      reason: "Elles arrivent avec les ailes (stade 3) — posées sur le front, jamais sur les yeux.",
      cells: worn(A.goggles, [[3, "stade 3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Collier de croc",
      emoji: "🦷",
      cost: 45,
      minStage: 2,
      reason: "Son premier croc de lait accroché à un cordon, porté sur la gorge, sous le museau.",
      cells: worn(A.fang, [[2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Petit trésor",
      emoji: "🪙",
      cost: 70,
      reason: "La taille d'une pièce ne change jamais — c'est la QUANTITÉ qui grandit : une simple pièce d'or (stades 0-2), l'or + les bijoux — bague puis couronne (3-6), puis le coffre en bois qui déborde de pièces et de bijoux sertis (7-9).",
      cells: worn(A.treasure, [[0, "stades 0-2 · une pièce d'or"], [3, "stades 3-4 · l'or + la bague"], [5, "stades 5-6 · + la couronne"], [7, "stades 7-8 · le coffre déborde"], [9, "stade 9 · coffre au trésor complet"]]),
    },
    {
      name: "Flamme bleue",
      emoji: "💙",
      cost: 200,
      minStage: 4,
      reason: "Le premium : un souffle légendaire bleu dès le stade 4, double souffle + braises bleues au stade 7, tempête bleue au stade 9.",
      cells: worn(A.blueFlame, [[4, "stades 4-6 · souffle bleu"], [7, "stades 7-8 · double souffle"], [9, "stade 9 · tempête bleue"]]),
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
  <h1>🐉 Garde-robe du dragon « Braise » — v2</h1>
  <p class="meta">Design validé : candidat A « Braise » · garde-robe refaite après retours (exit maillot/bouée/bouclier/casque et « petite queue ») · 18 objets, tous implémentés et rendus sur son rig · 26/07/2026</p>
  <div class="hero"><div>${petSvg(CandidateA, 4, cfg(), 120)}</div><div>Le voici au stade 4. Nouvelle garde-robe : la queue change de STYLE (massue, feu) — jamais de taille ; les cornes changent de forme (doubles) ET de couleur ; cape gardée mais réservée au stade 2+ ; et trois nouveaux trésors de dragon : lunettes d'aviateur, collier de croc, petit tas d'or. Premium inchangé : la Flamme bleue.</div></div>

  <h3>🎨 Couleurs (9) — écrites dans <code>config.colors</code></h3>
  ${colours.map(itemCard).join("")}
  <h3>💇 Styles (4) — écrits dans <code>config.styles</code></h3>
  ${styles.map(itemCard).join("")}
  <h3>🎒 Accessoires (5) — dont 1 premium</h3>
  ${accessories.map(itemCard).join("")}

  <section class="qa">
  <h3>🔍 Annexe QA</h3>
  <p>Boucle visuelle : rendu vitest → capture Chrome headless → relecture de chaque PNG (5 planches accessoire × stades 0→9, planche couleurs/styles).</p>
  <ul>
    <li>✅ Retours intégrés : maillot, bouée, bouclier et casque supprimés ; cape gated stade 2 (plus jamais sur l'œuf ni le bébé couché) ; « petite queue » remplacée par deux STYLES de queue qui grandissent normalement.</li>
    <li>✅ Fermoir de cape et collier de croc sur la gorge à chaque pose (ancres partagées), jamais sur le visage ; si les deux sont portés, le cordon du croc descend d'un cran.</li>
    <li>✅ Lunettes posées sur le front (touchent le dôme, jamais les yeux) ; le trésor est au sol : pièce seule (0-2), tas + bijoux (3-6), coffre en bois qui déborde côté dragon (7-9) — quantité qui grandit, jamais la taille des pièces.</li>
    <li>✅ Chaque couleur/style évident d'un coup d'œil (planches avant/après) — cornes d'or vs noires lisibles dès les bosses du stade 2.</li>
    <li>✅ Gates : ventre (1), cornes couleur (2), croc + cape + queue de feu (2), ailes + cornes doubles + lunettes (3), crête + flamme bleue (4).</li>
  </ul>
  <p class="iter"><b>Itérations v2 :</b> voir historique v1 (œuf élargi, gate <code>ramp()</code>). <b>Coffre (stades 7-9) :</b> contre-review par DA jeunesse — NO-GO v2 (couvercle noyé par le monticule au s9, or tombé caché derrière la pièce-héros, perles sans fil lues comme rivets) puis GO v3 après corrections chiffrées ; mineurs tolérés : brillance blanche des gemmes (TGem partagé avec 0-6), collier lu « texture dorée » à 88 px, s7/s8 proches au premier regard. <b>Note ship :</b> la tradition inter-espèces « maillot + bouée sur chaque espèce » est volontairement rompue pour le dragon (décision utilisateur) — adapter <code>catalog.test.ts</code> en conséquence. <b>Doutes restants :</b> « Cornes d'or » et « Ailes dorées » portées ensemble font beaucoup de jaune ; la flamme de queue au stade 9 arrive près de l'anneau de braises — à re-goûter sur fond réel.</p>
  </section>`;

  out("items.html", html);
});