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
