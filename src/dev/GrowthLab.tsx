import { Mascot } from "../mascot/Mascot";
import { ProposalMascot, PROPOSAL_NAMES, proposalNote } from "./proposals";
import { GROWTH_STAGES, type MascotConfig, type Species } from "../types";

/* -------------------------------------------------------------------------- */
/* Growth-stage REDESIGN review — dev only, routed at #growth-lab.             */
/* One table per mascot: Actuel · Proposition 1 · Proposition 2, every stade.  */
/* Stades 1-3 (index 0-2) are kept as-is; the redesign targets 4-10.           */
/* -------------------------------------------------------------------------- */

const BG = "linear-gradient(180deg,#FFF3E0 0%,#EAF4FF 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";
const INK = "#5A3A1E";

const SPECIES: { species: Species; label: string }[] = [
  { species: "unicorn", label: "🦄 Licorne" },
  { species: "cat", label: "🐱 Chat" },
  { species: "fox", label: "🦊 Renard" },
];

const STAGES = Array.from({ length: GROWTH_STAGES }, (_, i) => i);

const cfg = (species: Species, stage: number): MascotConfig => ({
  species,
  stage,
  colors: {},
  styles: {},
  accessories: [],
});

const CELL = "px-3 py-3 align-top text-center";
const HEAD = "px-3 py-2 text-sm font-black";

function Card({ children, sub, dim }: { children: React.ReactNode; sub?: string; dim?: boolean }) {
  return (
    <div
      className="mx-auto flex w-[132px] flex-col items-center gap-1 rounded-2xl p-2 shadow"
      style={{ background: dim ? "rgba(255,255,255,0.5)" : "rgba(255,255,255,0.85)" }}
    >
      <div className="flex h-[120px] w-full items-end justify-center">{children}</div>
      {sub && (
        <span className="min-h-[28px] text-[11px] font-bold leading-tight text-[#7A5A3A]">{sub}</span>
      )}
    </div>
  );
}

export function GrowthLab({ onClose }: { onClose: () => void }) {
  return (
    <div className="min-h-screen w-full px-4 py-6" style={{ background: BG, fontFamily: ROUNDED, color: INK }}>
      <div className="mx-auto max-w-5xl">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-3">
          <h1 className="m-0 text-2xl font-black">Refonte des évolutions — Actuel vs 2 propositions</h1>
          <button onClick={onClose} className="rounded-full bg-white/85 px-4 py-1.5 text-sm font-black active:scale-95">
            ✕ Fermer
          </button>
        </div>
        <p className="mb-6 mt-0 max-w-3xl text-sm text-[#7A5A3A]">
          Les 3 premiers stades sont conservés à l'identique. La refonte cible les stades 4→10 : un
          changement franc et « nommable » à chaque niveau, et un effet « waouh » en fin de parcours.
        </p>

        {SPECIES.map(({ species, label }) => {
          const [n1, n2] = PROPOSAL_NAMES[species];
          return (
            <section key={species} className="mb-10">
              <h2 className="mb-3 text-xl font-black">{label}</h2>
              <div className="overflow-x-auto rounded-2xl bg-white/40 p-1">
                <table className="w-full table-fixed border-collapse">
                  <colgroup>
                    <col style={{ width: "12%" }} />
                    <col style={{ width: "29.33%" }} />
                    <col style={{ width: "29.33%" }} />
                    <col style={{ width: "29.33%" }} />
                  </colgroup>
                  <thead>
                    <tr className="text-[#5A3A1E]">
                      <th className={HEAD}>Niveau</th>
                      <th className={HEAD}>Actuel</th>
                      <th className={HEAD}>
                        Proposition 1
                        <div className="text-[11px] font-bold text-[#9A7A5A]">{n1}</div>
                      </th>
                      <th className={HEAD}>
                        Proposition 2
                        <div className="text-[11px] font-bold text-[#9A7A5A]">{n2}</div>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {STAGES.map((stage) => {
                      const kept = stage < 3;
                      return (
                        <tr key={stage} className="border-t border-white/70">
                          <td className={CELL}>
                            <div className="text-lg font-black">{stage + 1}</div>
                            <div className="text-[11px] font-bold text-[#9A7A5A]">
                              {stage === 0 ? "bébé" : stage === GROWTH_STAGES - 1 ? "majestueux" : `stade ${stage}`}
                            </div>
                            {kept && <div className="mt-1 text-[10px] font-black text-[#66BB6A]">conservé</div>}
                          </td>
                          <td className={CELL}>
                            <Card>
                              <Mascot config={cfg(species, stage)} mood="idle" size={104} />
                            </Card>
                          </td>
                          <td className={CELL}>
                            <Card dim={kept} sub={kept ? undefined : proposalNote(species, stage, 1)}>
                              <ProposalMascot species={species} stage={stage} proposal={1} size={104} />
                            </Card>
                          </td>
                          <td className={CELL}>
                            <Card dim={kept} sub={kept ? undefined : proposalNote(species, stage, 2)}>
                              <ProposalMascot species={species} stage={stage} proposal={2} size={104} />
                            </Card>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}
