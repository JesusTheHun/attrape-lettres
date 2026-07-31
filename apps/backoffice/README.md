# apps/backoffice — not built yet

Nothing here, and unlike `services/api` there is no contract waiting: no client
asks for it, no endpoint serves it, nothing in the repo references it.

Expected shape when it happens: Vite + React + TypeScript, same toolchain as
`apps/game-web`, consuming `services/api` over tRPC — which is most of why the
API is TypeScript.

## Decide what it is for before building it

The plausible jobs pull in different directions and want different data:

- **Support** — "this family lost their stars, what does the server hold?" Needs
  household lookup by id, and little else.
- **Content** — editing words, syllable splits, levels. Note that this fights an
  invariant: content is authored in `content.ts` and pre-split by a human who
  checked how the fragment sounds, and every clip is baked from it ahead of time.
  A runtime content editor means a runtime VO pipeline. That is a product
  decision, not a feature.
- **Telemetry** — what gets played, where children stall. The event list and the
  property allowlist are closed, so this can only ever show counts by exercise,
  level and outcome. That is the design, not a limitation to route around.

## The constraint that outlives whichever job it is

The server holds no names, because `toWire` strips them before upload. A
backoffice cannot show you "Léa's progress" — it can show you a household id and
some counters, and that is what makes the privacy posture true rather than
merely claimed.

If a job here seems to *need* a child's name, the answer is not to start sending
names. Re-read invariant 10 in the root `CLAUDE.md` first.
