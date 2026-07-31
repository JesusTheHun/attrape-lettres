# DECISIONS.md

Repo-level decisions: the shape of the monorepo and what crosses between its
products. Decisions *inside* the iOS app live in `apps/game-ios/DECISIONS.md`
(D1–D57) and are not repeated here.

---

## R1 — The native port and the shell work merged before anything moved

The port had been developed on a branch that forked before `main` grew the
native shell, the 14-day trial, first-party telemetry and household sync. Two
lines of work, neither aware of the other, and a reshape on top of an unmerged
fork would have made the merge unreadable.

Exactly one conflict, in `useProfile`'s `preview`: `main` had moved the ledger to
per-device grow-only counters (`ledgerOf(profile.clears)`), while the port branch
had dropped `previewReward`'s `difficulty` argument because every row now pays
the completion curve. Both kept — the counters supply the ledger, the signature
stays at three arguments.

Nothing else needed a hand, which is the useful finding: the port was written
against the v4 counter model that already lived on `main`, so `ProfileStore`,
`Merge.swift` and the wire format agreed with the web app before they ever met.

## R2 — Capacitor removed

The phones are real native apps. The Capacitor shell — the same web bundle in a
WebView — was the earlier route and is gone rather than left dormant.

This was not deleting a config file. Capacitor reached into four places:

- **`kv.ts` collapses to localStorage.** Its whole reason for existing was that
  `@capacitor/preferences` is async while every read in this app happens on a
  path that cannot await (`useState(initialRoster)`, and award/spend inside a
  pointer-down handler). So it kept a memory cache hydrated once at boot, and
  `main.tsx` held the splash screen over that window. localStorage is
  synchronous: the cache, `hydrateKv()`, the boot gate and the splash hold all
  go. The try/catch wrapper stays — private mode and a full quota still throw.
- **`updates.ts` goes entirely.** Self-hosted live updates were legal because
  interpreted code run by WebKit sits inside the DPLA §3.3.1(B) carve-out, and a
  native binary is not interpreted code. **Nothing replaces it.** A content fix
  now waits for store review. That is a real cost and it is written down in
  `CLAUDE.md` rather than discovered during an incident.
- **Both `appStateChange` listeners become `visibilitychange`.** Sync in
  `useProfile` and entitlement refresh in `useEntitlement` were written to run
  "on mount and on resume", but on the web only the mount half ever ran — the
  native event never arrived and the `.catch` swallowed it. The web now does what
  its own comment always claimed, matching what iOS does through `scenePhase`.
- **`store.ts` loses `nativeStore`.** It described a StoreKit 2 / Play Billing
  implementation no TypeScript build could reach. The finished iOS one is
  `StoreKitPurchaseStore` in ALPlatform, written against this same interface.
  `UNREACHABLE` and the fail-open rule stay: that is invariant 11.

One thing deliberately **not** removed: the iOS app still reads
`CapacitorStorage.*`-prefixed keys (`apps/game-ios/.../UserDefaultsKVStore.swift`,
D7). No Capacitor build ever shipped, so nothing depends on it today, but it is
tested, harmless, and the cost of keeping a read path is lower than the cost of
being wrong about who has what installed.

## R3 — Layout: `apps/` + `services/` + `packages/`

```
apps/       things a person opens      game-web, game-ios, game-android, backoffice
services/   things they don't          api
packages/   shared TypeScript          (empty — see its README)
```

`src/` became `apps/game-web/`, `ios-native/` became `apps/game-ios/`. 1244 of
1251 changed files were pure renames.

The root `package.json` is a workspace root that delegates; the web app owns its
own manifest as `@attrape/game-web`. `apps/game-ios` and `apps/game-android`
carry no `package.json`, so pnpm skips them — they are absent from
`pnpm-workspace.yaml` on purpose, not by omission.

**What actually crosses the boundary:** only the VO clip bank. 855 clips live
once, in `apps/game-web/src/vo/clips/`, and are hard-linked into the iOS bundle
by `apps/game-ios/scripts/stage-vo.sh` rather than committed twice (13.7 MiB and
a free drift bug). Three things had to be re-pathed for the move, and all three
are that bank:

- `stage-vo.sh` looks for `apps/game-web/src/vo/clips`, and still prefers the
  repository's MAIN working tree over a linked worktree's copy — the guard
  against baking a stale bank into the app.
- `UtterancesTests` and `PreviewUtterancesTests` walk up to `apps/` and back down
  into their sibling, rather than out to the repo root.

Both tests were mutation-checked after the move: a wrong path fails 39
assertions, not zero. They also carry an explicit "the clip bank is where the
test thinks it is, and it is full" test, which is what keeps the other 723
non-vacuous.

## R4 — The VO generator stays in the web app

`generate-vo.mjs` did not move to a `tools/` directory. It boots a Vite server
rooted at the web app and `ssrLoadModule`s `src/vo/utterances.ts` to enumerate
what to bake, then writes into `src/vo/clips/`. It is that app's build tool, not
repo tooling; relocating it would mean a second Vite install reaching back
across a package boundary to do the same job. No `tools/` directory was created,
because it would have held exactly this one file.

## R5 — Backend: tRPC and Postgres, with REST kept for the devices

The service gets two doors: **REST for devices**, unchanged; **tRPC for the
backoffice**, where both ends are TypeScript.

The reason is not that a Swift client cannot consume tRPC. It can — the HTTP
adapter has a documented, language-agnostic shape, and codegen for non-TS
clients exists. Two better reasons:

**The sync protocol is conditional-request-shaped and RPC is not.** The etag is a
response header, the precondition (`If-Match`) is a request header, and the
conflict is a status code (412). That is the entire concurrency design, because
the merge happens on the device *between* pull and push. Modelling it as a
procedure means putting the etag in the payload and the conflict in an
application-level error — reimplementing conditional requests inside a body.

**tRPC's type inference does not cross a language boundary, and it is the whole
point of tRPC.** The safety comes from TypeScript inferring over the `AppRouter`
type. A Swift or Kotlin client re-declares or generates those types, which is the
codegen pipeline tRPC exists to remove — so the device door would pay tRPC's
costs and collect none of its benefit.

Third, smaller: the REST contract is already frozen in two shipped clients, so
changing it is a migration rather than a design choice.

The merge stays on the device. The server never merges; it only refuses a write
built on a superseded read (412), and the client re-pulls and retries. Full
contract in `services/api/README.md`.

**The privacy constraint travels with the schema.** `toWire` strips a child's
name and its stamp before upload, so the server receives counters and opaque
ids. The database must not become the place where PII starts — see invariant 10.

## R6 — Android is native, and unbuilt

The route is decided (Kotlin + Compose, mirroring `apps/game-ios`), the app is
not started. R2 removed the alternative rather than leaving both open. The
directory exists with a README naming what a port has to reproduce — the
invariants, the economy's persistence contract, the v4 counter schema, the sync
wire, and the ways Play differs from the App Store on trials and family sharing.

## R7 — `packages/` starts empty

`content.ts`, `levels.ts`, `rewards.ts` and `types.ts` are the obvious shared
domain and were deliberately left in the web app. They carry invariants and a
persistence contract (`ledgerKey` produces `"<exercise>:<level>"`, the key of
every counter on disk and on the wire in two shipped clients), so extracting
them is a change to re-verify against tests, not a file move — and it buys
nothing until a second consumer exists.
