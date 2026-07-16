import { useEffect, useMemo, useState } from "react";
import { clipUrl } from "../vo/clips";
import { enumerateUtterances } from "../vo/utterances";
import { enumeratePreviewUtterances, type VoKind } from "../vo/preview";

/* -------------------------------------------------------------------------- */
/* Dev audition bench — every baked VO clip, playable, with a reject toggle.    */
/* Not part of the kid flow: reachable only at URL hash #vo (App routes it).    */
/* This is the LISTEN half of the generator's bake-and-listen loop: play each   */
/* clip, flag the misreads (✗), then « Copier les rejets » exports the flagged  */
/* utterances so their clips can be deleted and re-baked. Verdicts persist in   */
/* localStorage so an audit can span reloads.                                   */
/*                                                                             */
/* Rows are sorted freshest-bake-first (missing clips on top): the clips still  */
/* being re-rolled surface at the top, the long-approved tail sinks. Bake time  */
/* comes from a HEAD request per clip (Last-Modified) — dev-only traffic.       */
/* -------------------------------------------------------------------------- */

const STAGE_BG = "linear-gradient(180deg,#FFF3E0 0%,#EAF4FF 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

const REJECTS_KEY = "vo-audit-rejects";

type Row = { text: string; kind: VoKind | "phrase"; url: string | undefined };

const KINDS = ["all", "phrase", "syllable", "letter"] as const;
type KindFilter = (typeof KINDS)[number];

// A "phrase" is something an exercise SAYS (consigne, réussite, mot annoncé) —
// the « comme dans » clips live here, by design. Tiles only ever speak the two
// tuile kinds.
const KIND_LABEL: Record<KindFilter, string> = {
  all: "tout",
  phrase: "consigne / mot",
  syllable: "tuile syllabe",
  letter: "tuile lettre",
};

function loadRejects(): Set<string> {
  try {
    const raw = localStorage.getItem(REJECTS_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

export function VoGallery({ onClose }: { onClose: () => void }) {
  const [filter, setFilter] = useState("");
  const [kind, setKind] = useState<KindFilter>("all");
  const [rejects, setRejects] = useState<Set<string>>(loadRejects);
  const [playing, setPlaying] = useState<string | null>(null);

  const rows = useMemo<Row[]>(() => {
    const phrases: Row[] = enumerateUtterances().map((text) => ({
      text,
      kind: "phrase",
      url: clipUrl(text),
    }));
    const preview: Row[] = enumeratePreviewUtterances().map((it) => ({
      text: it.text,
      kind: it.kind,
      url: clipUrl(it.text),
    }));
    const seen = new Set<string>();
    return [...phrases, ...preview].filter((r) => !seen.has(r.text) && seen.add(r.text));
  }, []);

  // Bake time per clip (Last-Modified of the served file) → newest-first sort.
  const [bakedAt, setBakedAt] = useState<Map<string, number>>(new Map());
  useEffect(() => {
    let cancelled = false;
    void Promise.all(
      rows.map(async (r) => {
        if (!r.url) return [r.text, 0] as const;
        try {
          const res = await fetch(r.url, { method: "HEAD" });
          const lm = res.headers.get("last-modified");
          return [r.text, lm ? Date.parse(lm) : 0] as const;
        } catch {
          return [r.text, 0] as const;
        }
      }),
    ).then((entries) => {
      if (!cancelled) setBakedAt(new Map(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [rows]);

  const shown = useMemo(() => {
    // Missing clips (about to be re-baked) first, then freshest bake first.
    const at = (r: Row) => (r.url ? bakedAt.get(r.text) ?? 0 : Number.MAX_SAFE_INTEGER);
    return rows
      .filter(
        (r) =>
          (kind === "all" || r.kind === kind) &&
          r.text.toLowerCase().includes(filter.toLowerCase()),
      )
      .sort((a, b) => at(b) - at(a));
  }, [rows, bakedAt, kind, filter]);
  const missing = rows.filter((r) => !r.url).length;

  const when = (r: Row) => {
    const t = bakedAt.get(r.text);
    if (!r.url || !t) return "";
    return new Date(t).toLocaleString("fr-FR", {
      day: "2-digit",
      month: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  const toggle = (text: string) => {
    const next = new Set(rejects);
    if (next.has(text)) next.delete(text);
    else next.add(text);
    localStorage.setItem(REJECTS_KEY, JSON.stringify([...next]));
    setRejects(next);
  };

  const play = (row: Row) => {
    if (!row.url) return;
    setPlaying(row.text);
    const audio = new Audio(row.url);
    audio.onended = () => setPlaying((p) => (p === row.text ? null : p));
    void audio.play();
  };

  const copyRejects = () => {
    void navigator.clipboard.writeText([...rejects].join("\n"));
  };

  const clearRejects = () => {
    localStorage.removeItem(REJECTS_KEY);
    setRejects(new Set());
  };

  return (
    <div
      className="min-h-screen w-full px-4 py-6"
      style={{ background: STAGE_BG, fontFamily: ROUNDED, color: "#5A3A1E" }}
    >
      <div className="mx-auto max-w-3xl">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h1 className="m-0 text-2xl font-black">
            Écoute des voix ({rows.length} clips{missing > 0 ? `, ${missing} manquants` : ""})
          </h1>
          <div className="flex items-center gap-2">
            <button
              onClick={copyRejects}
              className="rounded-full bg-white/85 px-4 py-1.5 text-sm font-black active:scale-95"
            >
              📋 Copier les rejets ({rejects.size})
            </button>
            <button
              onClick={clearRejects}
              disabled={rejects.size === 0}
              className="rounded-full bg-white/85 px-4 py-1.5 text-sm font-black active:scale-95 disabled:opacity-40"
            >
              🧹 Vider les rejets
            </button>
            <button
              onClick={onClose}
              className="rounded-full bg-white/85 px-4 py-1.5 text-sm font-black active:scale-95"
            >
              ✕ Fermer
            </button>
          </div>
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Filtrer… (ex : comme dans)"
            className="rounded-full border-none px-4 py-1.5 text-sm font-bold shadow"
          />
          {KINDS.map((k) => (
            <button
              key={k}
              onClick={() => setKind(k)}
              className="rounded-full px-3 py-1.5 text-sm font-black active:scale-95"
              style={{
                background: kind === k ? "#66BB6A" : "rgba(255,255,255,0.85)",
                color: kind === k ? "#fff" : "#5A3A1E",
                border: "none",
              }}
            >
              {KIND_LABEL[k]}
            </button>
          ))}
        </div>

        <div className="flex flex-col gap-1.5">
          {shown.map((row) => (
            <div
              key={row.text}
              className="flex items-center gap-3 rounded-2xl bg-white/70 px-3 py-2 shadow"
              style={{ opacity: row.url ? 1 : 0.5 }}
            >
              <button
                onClick={() => play(row)}
                disabled={!row.url}
                aria-label={`Écouter ${row.text}`}
                className="h-9 w-9 shrink-0 rounded-full text-base font-black text-white active:scale-95"
                style={{ background: playing === row.text ? "#FB8C00" : "#42A5F5", border: "none" }}
              >
                ▶
              </button>
              <span className="min-w-0 flex-1 truncate text-sm font-bold">
                {row.url ? row.text : `${row.text} — pas de clip (voix robot)`}
              </span>
              <span className="shrink-0 text-xs font-bold text-[#9A7A5A]">
                {when(row) ? `${KIND_LABEL[row.kind]} · ${when(row)}` : KIND_LABEL[row.kind]}
              </span>
              <button
                onClick={() => toggle(row.text)}
                aria-label={`Rejeter ${row.text}`}
                className="h-9 w-9 shrink-0 rounded-full text-base font-black active:scale-95"
                style={{
                  background: rejects.has(row.text) ? "#EF5350" : "rgba(255,255,255,0.9)",
                  color: rejects.has(row.text) ? "#fff" : "#C9B29A",
                  border: "none",
                }}
              >
                ✗
              </button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
