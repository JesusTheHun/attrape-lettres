/**
 * Proposal render harness — gated behind PROPOSAL=dragon so a plain
 * `pnpm test` never runs it:
 *
 *   PROPOSAL=dragon pnpm vitest run src/dev/proposals/dragon/render.spec.tsx
 *
 * Writes qa-*.html (screenshot targets for the visual QA loop) next to this
 * file. The final proposal.html is authored separately once QA is green.
 */
import { it } from "vitest";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { layoutFor, stageScale } from "../../../mascot/growth";
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

/** Render one candidate at one stade, standalone. Mirrors Mascot.tsx. */
function petSvg(RigC: Rig, stage: number, config: PConfig, size = 150): string {
  const layout = layoutFor(stage);
  const k = stageScale(stage);
  const uid = `p${stage}x${JSON.stringify(config).length}`;
  return renderToStaticMarkup(
    <svg viewBox="0 0 100 100" width={size} height={size} style={{ overflow: "visible", display: "block" }}>
      <ellipse cx={50} cy={layout.feetY + 3.5} rx={layout.bodyRX * 0.92 * k} ry={3.6} fill="#000" opacity={0.1} />
      <g transform={`translate(50 ${layout.feetY}) scale(${k}) translate(-50 ${-layout.feetY})`}>
        <RigC config={config} layout={layout} stage={stage} uid={uid} />
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

const growthStrip = (RigC: Rig) => strip(STAGES.map((s) => ({ svg: petSvg(RigC, s, cfg()), caption: `stade ${s}` })));

it.runIf(RUN)("renders QA pages", () => {
  // 1) One growth strip per candidate.
  out("qa-candidate-a.html", page("Candidat A « Flamme » — croissance", growthStrip(CandidateA)));
  out("qa-candidate-b.html", page("Candidat B « Nimbus » — croissance", growthStrip(CandidateB)));
  out("qa-candidate-c.html", page("Candidat C « Pépite » — croissance", growthStrip(CandidateC)));

  // 2) One page per accessory, worn at EVERY stade (the placement spec) — on A.
  for (const [key, id] of Object.entries(P_ACCESSORY.dragon)) {
    out(
      `qa-acc-${key}.html`,
      page(`Accessoire — ${key}`, strip(STAGES.map((s) => ({ svg: petSvg(CandidateA, s, cfg({ accessories: [id] })), caption: `stade ${s}` }))))
    );
  }

  // 3) Colour + style variant board vs factory look (on A).
  const colourRows: Array<[string, string, string, number]> = [
    // [slot, value, label, stade]
    ["bodyColor", "#7FD1C0", "Écailles turquoise", 4],
    ["bodyColor", "#9BC4EA", "Écailles bleues", 4],
    ["bodyColor", "#F2938A", "Écailles rouges", 4],
    ["bodyColor", "#C9ADE8", "Écailles violettes", 4],
    ["bellyColor", "#FFD98E", "Ventre doré", 5],
    ["wingColor", "#C9A3E8", "Ailes violettes", 4],
    ["wingColor", "#8EC9F0", "Ailes bleu ciel", 4],
  ];
  const colourBoard = colourRows
    .map(([slot, value, label, stade]) => {
      const pair = strip([
        { svg: petSvg(CandidateA, stade, cfg()), caption: "d'origine" },
        { svg: petSvg(CandidateA, stade, cfg({ colors: { [slot]: value } })), caption: label },
      ]);
      return `<h2>${label} (stade ${stade})</h2>${pair}`;
    })
    .join("");
  const styleRows: Array<[string, string, string, number[]]> = [
    ["hornStyle", "curly", "Cornes de bélier", [3, 5, 7, 9]],
    ["crestStyle", "round", "Crête ronde", [4, 6, 9]],
    ["tailSize", "short", "Petite queue", [2, 5, 9]],
  ];
  const styleBoard = styleRows
    .map(([slot, value, label, stades]) => {
      const cells = stades.flatMap((s) => [
        { svg: petSvg(CandidateA, s, cfg()), caption: `d'origine · stade ${s}` },
        { svg: petSvg(CandidateA, s, cfg({ styles: { [slot]: value } })), caption: `${label} · stade ${s}` },
      ]);
      return `<h2>${label}</h2>${strip(cells)}`;
    })
    .join("");
  out("qa-variants.html", page("Couleurs & styles — variantes vs origine", colourBoard + styleBoard));

  // 4) Egg close-up: the brief's two special stades, big, all three candidates.
  const eggCells = [CandidateA, CandidateB, CandidateC].flatMap((R, i) => [
    { svg: petSvg(R, 0, cfg(), 230), caption: `candidat ${"ABC"[i]} · stade 0` },
    { svg: petSvg(R, 1, cfg(), 230), caption: `candidat ${"ABC"[i]} · stade 1` },
  ]);
  out("qa-egg.html", page("L'œuf — stades 0 et 1 (brief)", strip(eggCells)));
});

/* ------------------------------------------------------------------------- */
/* The deliverable — proposal.html, embedding the QA-approved SVG markup.     */
/* ------------------------------------------------------------------------- */

const chip = (hex: string, name: string) =>
  `<span class="chip"><i style="background:${hex}"></i>${name} <code>${hex}</code></span>`;

const arcTable = (rows: string[]) =>
  `<table class="arc"><tr><th>Stade</th><th>Ce que l'enfant voit de nouveau</th></tr>${rows
    .map((r, i) => `<tr><td>${i}</td><td>${r}</td></tr>`)
    .join("")}</table>`;

interface ItemCard {
  name: string;
  emoji: string;
  cost: number;
  minStage?: number;
  reason: string;
  cells: { svg: string; caption: string }[];
}

const card = (it2: ItemCard) =>
  `<div class="card"><h4>${it2.emoji} ${it2.name} <span class="cost">${it2.cost} pts</span>${
    it2.minStage ? `<span class="gate">🔒 stade ${it2.minStage}</span>` : ""
  }</h4><p class="why">${it2.reason}</p>${strip(it2.cells)}</div>`;

it.runIf(RUN)("writes proposal.html", () => {
  const A = P_ACCESSORY.dragon;
  const pair = (config: PConfig, stade: number, label: string) => [
    { svg: petSvg(CandidateA, stade, cfg(), 130), caption: `d'origine · stade ${stade}` },
    { svg: petSvg(CandidateA, stade, config, 130), caption: `${label} · stade ${stade}` },
  ];
  const worn = (id: string, stades: Array<[number, string]>) =>
    stades.map(([s, cap]) => ({ svg: petSvg(CandidateA, s, cfg({ accessories: [id] }), 130), caption: cap }));

  const colours: ItemCard[] = [
    { name: "Écailles turquoise", emoji: "🐬", cost: 18, reason: "Recolore tout le corps, visible dès l'œuf.", cells: pair(cfg({ colors: { bodyColor: "#7FD1C0" } }), 4, "turquoise") },
    { name: "Écailles bleues", emoji: "💧", cost: 18, reason: "Recolore tout le corps, visible dès l'œuf.", cells: pair(cfg({ colors: { bodyColor: "#9BC4EA" } }), 4, "bleues") },
    { name: "Écailles rouges", emoji: "🔥", cost: 20, reason: "Le dragon rouge classique, assorti à l'arc du feu.", cells: pair(cfg({ colors: { bodyColor: "#F2938A" } }), 4, "rouges") },
    { name: "Écailles violettes", emoji: "💜", cost: 20, reason: "Recolore tout le corps, visible dès l'œuf.", cells: pair(cfg({ colors: { bodyColor: "#C9ADE8" } }), 4, "violettes") },
    { name: "Ventre doré", emoji: "⭐", cost: 22, minStage: 1, reason: "Le ventre est caché dans l'œuf au stade 0.", cells: pair(cfg({ colors: { bellyColor: "#FFD98E" } }), 5, "doré") },
    { name: "Ailes violettes", emoji: "🦋", cost: 24, minStage: 3, reason: "Les ailes poussent au stade 3.", cells: pair(cfg({ colors: { wingColor: "#C9A3E8" } }), 4, "violettes") },
    { name: "Ailes bleu ciel", emoji: "☁️", cost: 24, minStage: 3, reason: "Les ailes poussent au stade 3.", cells: pair(cfg({ colors: { wingColor: "#8EC9F0" } }), 4, "bleu ciel") },
  ];

  const styles: ItemCard[] = [
    {
      name: "Cornes de bélier",
      emoji: "🐏",
      cost: 45,
      minStage: 3,
      reason: "Les vraies cornes poussent au stade 3 ; la boucle grandit avec elles.",
      cells: [...pair(cfg({ styles: { hornStyle: "curly" } }), 3, "bélier").slice(1), ...pair(cfg({ styles: { hornStyle: "curly" } }), 7, "bélier").slice(1), ...pair(cfg({ styles: { hornStyle: "curly" } }), 9, "bélier").slice(1)],
    },
    {
      name: "Crête ronde",
      emoji: "🫧",
      cost: 40,
      minStage: 4,
      reason: "La crête apparaît au stade 4 ; version bulles toutes douces.",
      cells: [...pair(cfg({ styles: { crestStyle: "round" } }), 4, "ronde"), ...pair(cfg({ styles: { crestStyle: "round" } }), 9, "ronde").slice(1)],
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
      name: "Nœud papillon",
      emoji: "🎀",
      cost: 45,
      reason: "Sur la gorge à chaque pose — et sur la coquille au stade 0.",
      cells: worn(A.bowtie, [[0, "stade 0 · sur l'œuf"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Lunettes d'aviateur",
      emoji: "🥽",
      cost: 60,
      reason: "Posées sur le front, jamais sur les yeux — prêtes pour voler.",
      cells: worn(A.goggles, [[0, "stade 0 · sur l'œuf"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9"]]),
    },
    {
      name: "Armure dorée",
      emoji: "🛡️",
      cost: 200,
      minStage: 4,
      reason: "L'objet premium : plastron d'or qui gagne des épaulettes au stade 6 et un rubis au stade 7.",
      cells: worn(A.armor, [[4, "stades 4-5 · plastron"], [6, "stade 6 · épaulettes"], [7, "stades 7-9 · rubis"]]),
    },
    {
      name: "Maillot de bain",
      emoji: "🩱",
      cost: 60,
      reason: "Tradition des espèces : culotte sur l'œuf/le bébé, maillot rayé debout, étoile de champion au stade 6.",
      cells: worn(A.swimsuit, [[0, "stade 0 · l'œuf le porte"], [1, "stade 1 · culotte"], [2, "stades 2-3"], [4, "stades 4-5"], [6, "stades 6-9 · étoile"]]),
    },
    {
      name: "Bouée",
      emoji: "🛟",
      cost: 75,
      reason: "Tradition des espèces : l'œuf flotte dedans, puis le dragon s'assoit dans la bouée ; canard au stade 7.",
      cells: worn(A.swimRing, [[0, "stade 0 · l'œuf flotte"], [1, "stade 1"], [2, "stades 2-3"], [4, "stades 4-6"], [7, "stades 7-9 · canard"]]),
    },
  ];

  const direction = (
    letter: string,
    title: string,
    pitch: string,
    palette: string,
    rows: string[],
    RigC: Rig,
    recommended: boolean
  ) =>
    `<section class="dir ${recommended ? "reco" : ""}">
      <h2>Candidat ${letter} — ${title}${recommended ? ' <span class="badge">⭐ Recommandé</span>' : ""}</h2>
      <p>${pitch}</p>
      <p class="chips">${palette}</p>
      ${arcTable(rows)}
      ${strip(STAGES.map((s) => ({ svg: petSvg(RigC, s, cfg(), 130), caption: `stade ${s}` })))}
    </section>`;

  const html = `<!doctype html><meta charset="utf-8"><title>Proposition — Mon dragon</title><style>
    body{font-family:ui-rounded,'SF Pro Rounded',system-ui,sans-serif;background:#FFF7EC;color:#5A3A1E;margin:28px;max-width:1240px}
    h1{font-size:26px;margin-bottom:4px} h2{font-size:20px;margin:8px 0} h3{font-size:18px;margin:26px 0 10px} h4{font-size:15px;margin:0 0 4px}
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
    .card{background:rgba(255,255,255,.55);border-radius:16px;padding:12px 14px;margin:0 0 16px}
    .cost{background:#DDF3D8;border-radius:999px;padding:2px 8px;font-size:12px;margin-left:8px}
    .gate{background:#FFE1EC;border-radius:999px;padding:2px 8px;font-size:12px;margin-left:6px}
    .why{font-size:13px;color:#7A6248;margin:2px 0 0}
    ul{font-size:13.5px} .qa p,.qa li{font-size:13.5px}
  </style>
  <h1>🐉 Mon dragon — proposition de compagnon</h1>
  <p class="meta">Brief : dragon (garçon) · stade 0 = encore dans l'œuf mais le dragon doit être visible · stade 1 = garde des morceaux de coquille, façon mignonne · 26/07/2026</p>
  <div class="banner">⭐ Recommandation : <b>Candidat A « Flamme »</b> — silhouette « dragon » la plus lisible à 88 px, le vert manque au roster (licorne rose, chat roux, renard orange), et l'arc du feu offre le beat le plus spectaculaire. Les ~15 objets ci-dessous sont construits et rendus sur lui.</div>

  <h3>Les 3 pistes (mêmes stades 0-1 « œuf » du brief, silhouettes et arcs différents)</h3>
  ${direction(
    "A",
    "« Flamme », le dragonnet des volcans",
    "Le dragon classique des histoires : vert tendre, ailes de chauve-souris corail, crête en zigzag — il apprend à cracher sa première flamme et finit légende de feu.",
    [chip("#8FCF9F", "corps"), chip("#FFF1CE", "ventre"), chip("#FF9E7A", "ailes & crête"), chip("#FFE8C9", "cornes"), chip("#FF7043", "flamme")].join(""),
    [
      "Dans son œuf fêlé — frimousse endormie visible, pointe de queue qui dépasse",
      "Sorti ! coquille-chapeau sur la tête + morceau de coquille sur le flanc",
      "Premiers pas, petites bosses de cornes",
      "Les ailes poussent (bourgeons) + vraies cornes",
      "Ailes déployées + crête sur la tête",
      "Écailles du ventre + grande pointe de queue",
      "Première flamme ! + étincelle",
      "Grandes cornes à pointes d'or, ailes larges",
      "Halo doré + aura de braise",
      "Légende cracheuse de feu : ailes géantes, grand souffle, lumière au sol",
    ],
    CandidateA,
    true
  )}
  ${direction(
    "B",
    "« Nimbus », le dragon des orages",
    "Un dragon bleu ciel qui grandit dans les nuages : ailes-cumulus, queue-éclair, nageoire sur la tête et petit nuage de pluie apprivoisé.",
    [chip("#A9C6EF", "corps"), chip("#F2F7FF", "ventre"), chip("#FFFFFF", "ailes-nuages"), chip("#6FA6E8", "nageoire"), chip("#FFD54F", "éclairs")].join(""),
    [
      "Dans son œuf fêlé (taches bleutées), frimousse endormie visible",
      "Sorti ! coquille-chapeau + morceau sur le flanc",
      "Premiers pas, petites bosses de cornes",
      "Bourgeons d'ailes-nuages",
      "Queue-éclair dorée + nageoire sur la tête",
      "Écailles du ventre + grande nageoire",
      "Petit nuage compagnon + étincelle",
      "Cornes-éclairs dorées",
      "Halo bleuté + aura d'orage (le nuage pleut)",
      "Tonnerre : ailes-nuages géantes, souffle-éclair, lumière au sol",
    ],
    CandidateB,
    false
  )}
  ${direction(
    "C",
    "« Pépite », le dragon aux trésors",
    "Un dragon turquoise qui se couvre d'or et de gemmes en grandissant : ventre-armure doré, crête à joyaux, cornes d'or bouclées — un vrai gardien de trésor.",
    [chip("#85CDBB", "corps"), chip("#FFD98E", "ventre doré"), chip("#55A896", "ailes"), chip("#C9A24B", "bords d'or"), chip("#FF7EA8", "gemmes")].join(""),
    [
      "Dans son œuf fêlé (taches dorées), frimousse endormie visible",
      "Sorti ! coquille-chapeau + morceau sur le flanc",
      "Premiers pas, petites bosses de cornes",
      "Ailes sombres bordées d'or",
      "Ventre doré en armure",
      "Crête à gemmes",
      "Gemme sertie dans la queue + étincelle",
      "Cornes d'or bouclées",
      "Halo + gemme au front",
      "Trésor vivant : aura d'or, pluie de pièces, lumière au sol",
    ],
    CandidateC,
    false
  )}

  <h3>🎨 Couleurs (7) — écrites dans <code>config.colors</code></h3>
  ${colours.map(card).join("")}
  <h3>💇 Styles (3) — écrits dans <code>config.styles</code></h3>
  ${styles.map(card).join("")}
  <h3>🎒 Accessoires (5) — dont la paire de bain traditionnelle et 1 premium</h3>
  ${accessories.map(card).join("")}

  <section class="qa">
  <h3>🔍 Annexe QA</h3>
  <p>Boucle visuelle : rendu vitest → capture Chrome headless → relecture de chaque PNG. <b>3 itérations</b> avant le vert.</p>
  <ul>
    <li>✅ Espèce reconnaissable et silhouette propre aux stades 0 et 9 (les 3 candidats).</li>
    <li>✅ Chaque stade 3→9 montre un beat nouveau vs son voisin de gauche (bandes ci-dessus).</li>
    <li>✅ Accessoires : gorge sous le museau à toutes les poses (y compris couché 0-1), chapeau/lunettes posés sur le dôme, rien ne couvre les yeux, tailles suivant la tête qui rétrécit.</li>
    <li>✅ Œuf du brief : dragon visible au stade 0 (frimousse + queue qui dépasse), coquilles portées mignonnement au stade 1 ; la coquille contient la tête après élargissement (itération 2).</li>
    <li>✅ Couleurs/styles évidents d'un coup d'œil (planches avant/après).</li>
    <li>✅ Débordement haut/latéral des grands stades géré par le padding des cellules — rien de coupé par un vrai bord.</li>
    <li>Corrigé en itération 2 : queue invisible (même vert que le corps) → contour dérivé de la couleur du corps via <code>mix(body, INK, 0.35)</code> ; éclosion trop étroite à droite ; morceau de coquille caché derrière la tête ; flamme détachée de la bouche.</li>
    <li>Corrigé en itération 3 : boucles de bélier minuscules au stade 3 (plancher de taille).</li>
  </ul>
  <p><b>Doutes restants (honnêtes)</b> : les yeux fermés du stade 0 sont épais dans la fenêtre de l'œuf (style maison, même rendu que le renardeau) ; au stade 1 les lunettes d'aviateur recouvrent visuellement la coquille-chapeau (les deux se disputent le dôme) ; l'armure aux stades 4-5 évoque un peu un bavoir avant l'arrivée des épaulettes ; les cornes de bélier au stade 3 restent proches d'oreilles d'ourson à 88 px.</p>
  <p><b>Notes d'intégration</b> : profil de museau <code>NECK_K</code> le plus proche = renard (0,86) ; fentes proposées <code>bodyColor/bellyColor/wingColor</code>, <code>hornStyle/crestStyle/tailSize</code> ; défauts d'usine = les fallbacks <code>pick()</code> du rig (test <code>DEFAULT_LOOKS</code> ok d'office).</p>
  </section>`;

  out("proposal.html", html);
});
