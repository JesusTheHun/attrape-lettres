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

## R8 — The API is Hono + Zod, and the schemas are the contract

Built. Four routes, ~600 lines, 31 tests that need no database.

**Zod schemas earn their place three times over**, which is why they drive the
routes rather than being bolted on after: runtime validation on a public write
endpoint, TypeScript types inferred for free, and an OpenAPI document generated
for the Android port to work from. One source of truth, no spec file to drift.

`@hono/zod-openapi` was chosen over bare Hono for that third job. What it does
*not* pull in is a second protocol: the backoffice can later take Hono's `hc`
typed client and get end-to-end inference over these same ordinary REST routes,
which is the thing tRPC would have been for (R5).

**The app is a pure function of its dependencies.** `buildApp({households,
telemetry})` reads no env, opens no pool and listens on nothing; `server.ts` does
all three. That is what lets every route test exercise real routing and real
validation against an in-memory store in milliseconds — and the in-memory store
parses etags exactly as the Postgres one does, so the double is not a
simplification.

**Privacy is re-enforced server-side.** The clients already strip names and
already have a closed telemetry allowlist; that proves our clients behave, and
says nothing about the database, because the endpoints are public and
unauthenticated. Every schema is `.strict()`, so an unknown key is a 400 rather
than a silently-kept column, and validation failures never echo the payload back.
Both guarantees were mutation-checked: dropping `.strict()` from `WireChild`
fails the "rejects a child's name" test, dropping it from the telemetry props
fails "there is no free-text escape hatch".

Two things the build turned up that the plan did not know:

- **Zod 4's `z.record` is EXHAUSTIVE on an enum key.** It rejected a document
  missing any of the five mascots. Every client fills all five today, so it
  would have passed — and would have turned a future client that trims an
  untouched species into a silent sync outage, since both clients swallow a 400.
  Now `z.partialRecord`. The key stays closed, which means **this service must be
  deployed before any client that adds a sixth mascot.**
- **Anything that strips or weakens the `ETag` header breaks sync permanently
  and invisibly.** Both clients read `etag ?? ""` and send no `If-Match` when it
  is empty, so a proxy dropping ETag turns every push into a create → 412 → three
  retries → give up, forever, with no error anywhere because both clients fail
  silent by design. Recorded at the top of the service's README because it is the
  easiest way to break this without anyone noticing.

Deliberately absent, each for a reason written down in that README: no auth (a
household id is the only credential and the server holds nothing worth
stealing), no counter ceiling (no shared economy — a cheat gets your own kid free
hats, while a false rejection stops sync silently), no CORS, no migration runner
until there is a second migration.

The merge stays on the device. The server never merges; it only refuses a write
built on a superseded read (412), and the client re-pulls and retries. Full
contract in `services/api/README.md`.

**The privacy constraint travels with the schema.** `toWire` strips a child's
name and its stamp before upload, so the server receives counters and opaque
ids. The database must not become the place where PII starts — see invariant 10.

## R9 — Households go to DynamoDB, telemetry to S3, and no relational database

Built. `postgres.ts`, the telemetry table and `001_init.sql` are gone; the
service has no database and no pool.

Nothing in this service uses a database. No join, no aggregate, no second index —
the join code *is* the household id, so there is no code-to-id lookup to serve —
no transaction across rows, and no query that is not "get by primary key". What
it needs is a conditional write, and Postgres, S3 and DynamoDB all have one:
`WHERE revision = $n`, `If-Match` on the object ETag, and a `ConditionExpression`
respectively. Capability did not separate them. Three other things did.

**Postgres bills for existing.** €15–50/month whether or not one family syncs,
plus a version to patch and a pool to size, for a workload of a few hundred
requests a day. That is the whole case against it.

**S3 was close, and lost on the ETag.** Its ETag is a hash of the content, so
identical content yields an identical tag. Sync would still be correct — the
merge is idempotent and commutative, so a write landing on identical content is a
no-op — but `InMemoryHouseholdStore` parses etags as revision numbers, and it
would stop mirroring production. Thirty-one tests would keep passing while
proving something subtly different from what runs. DynamoDB keeps a stored
integer revision, so `etagOf`/`revisionOf` and the double are unchanged. Its
free allowance is also real rather than symbolic: S3's ~2 000 writes/month is
about thirteen families, DynamoDB's 25 GB + 25 RCU/WCU covers actual usage.

**Telemetry does not go in the same store.** It is append-many, read-rarely, and
the reads are aggregations — the one thing a key-value store is bad at. NDJSON
under a date prefix in S3 costs cents, keeps everything, and is read with Athena
or by downloading a day. It also keeps family data and analytics physically
apart, which is the isolation the split was asked for in the first place.

Being wrong costs one file. `HouseholdStore` is `read(id)` and
`write(id, roster, ifMatch)`; the routes never see the store. A change of mind
is another implementation, not a migration.

### The 400 KB ceiling, and what it made us build

DynamoDB caps one item at 400 KB, and the household document is the only
unbounded thing here — children × five mascots × up to five hundred owned
accessories × a per-device clear counter for every exercise and level,
accumulating for years. Crossing that line fails the push, and both clients
swallow it, so a family would simply stop syncing with nobody told.

So a household is **not one item**. It is a root plus one sidecar per child, all
in one partition:

```
pk = <householdId>   sk = "#root"        rev, removed, order
pk = <householdId>   sk = "child#<id>"   touchedAt, profile
```

The ceiling becomes per child rather than per family, and a pull is still one
round trip — one `Query` on the partition returns everything, root first because
`#` sorts before `c`. **None of this is visible on the wire**; the frozen
contract is still one document and one ETag.

Three things the implementation forced, each of which would have been a silent
data-loss bug:

- **The write must be one transaction.** Writing children first and the root
  last looks equivalent and loses stars: a push whose root condition fails has
  already overwritten a sidecar with a merge built on an older revision, so the
  winning revision points at a child missing the other device's newest play.
  `TransactWriteItems` puts the root's precondition in charge of every sidecar.
- **Tombstoned children must still be returned.** `mergeRoster` resurrects a
  child whose `touchedAt` is later than the tombstone — deliberately, so a
  parent tidying the roster on one phone cannot erase a week of play from the
  other. Filtering them server-side would have been a merge rule on the server
  and would have made that resurrection impossible. It is also why nothing
  sweeps a removed child's sidecar.
- **The root has to store the child order.** A `Query` returns sort-key order,
  which would have silently re-alphabetised every family's roster on first sync.
  Order is observable in the merge, so it is stored, and a pull returns exactly
  the array that was pushed.

And one coupling worth knowing before it bites: a transaction takes at most 100
items and a push writes one root plus one per child, so `wireRoster`'s cap of 64
children is load-bearing. Raising it past 99 would start rejecting valid rosters
at the store rather than at the schema.

All four are mutation-checked: alphabetising the children, filtering tombstones,
dropping the root's precondition, or treating every cancelled transaction as a
412 each fail a test that names the consequence.

### The stubs were not enough, and running it for real said so

The unit suite stubs every store call. It proves the commands we build are the
ones we meant to build and nothing about whether DynamoDB accepts them — and a
single-threaded double cannot lose a race, so its "concurrency" tests were two
sequential writes wearing the same etag. The property the whole design exists
for had never been executed against anything that could actually race.

So there is now an integration suite against `amazon/dynamodb-local`
(`pnpm test:integration`, skipped loudly without `DYNAMO_ENDPOINT`). Eight
genuinely concurrent writers on one household: exactly one wins. Five concurrent
creators: exactly one wins. It also caught two things a stub never would — the
paging loop had never run, and the whole-app block was killed by a table dropped
in the wrong `afterAll`.

It also found a hole worth keeping in view: **a child can be legal by the schema
and too large for DynamoDB.** `colors` and `styles` are records with no bound on
key count, and `owned` allows 500×128 characters per species across five
species; the suite writes a 430 KB child. Sharding moved the ceiling from per
family to per child, it did not remove it. Nothing pre-rejects on size — refusing
a write DynamoDB would have accepted is worse than the error it prevents — so
the store names the largest child in the log on any non-conflict failure, which
may be the only trace that a family stopped syncing.

And a limit of the harness itself, recorded as a passing test rather than a
comment: **DynamoDB Local does not enforce the 400 KB item cap.** The 430 KB
child is accepted there. Item-size behaviour is therefore not covered by any
test — which is why R10 moved the guard up to validation, where it can be.

## R10 — The service is one Lambda, and most of its infrastructure is detection

Built. `infra/template.yaml`, `infra/athena.sql`, `scripts/deploy.sh`,
`src/lambda.ts`, `src/log.ts`, `test/infra.test.ts`.

Everything here follows from one sentence that was already true and had no
consequences until the service had to run somewhere: **both clients swallow every
response that is not a 200, a 404 or a 412 and keep playing offline.** That is
right for a six-year-old mid-round, and it means nothing that breaks on this
server reaches a user, a support inbox or a crash reporter. It surfaces months
later as two phones disagreeing about a child's stars.

**Lambda, because the workload is idle.** A household syncs on app open and on
resume, so a family of three devices makes a handful of requests a day. Anything
always-on bills for the twenty-three hours nobody is playing, and Lambda's free
tier is a million requests a month permanently rather than for twelve months.
Scale-to-zero is also the honest shape for a product with no users yet.

**An HTTP API in front, not a Function URL** — the free option, rejected twice
over. The endpoint is compiled into a native binary that changes only through
store review (R2), so it must outlive the stack that created it, and a Function
URL dies with its function: recreate the stack and every installed app points at
nothing, with no fix that is not a release. And a custom domain over a Function
URL means CloudFront, which forwards no request headers by default — `If-Match`
would vanish, every push would become a create, every create would conflict, and
sync would stop for everyone. The one documented way to break this service would
have been introduced by its own deployment. `DomainName` is optional in the
template and mandatory in practice, and the README says so in a block quote.

**The name is `api.attrape-lettres.app`, and it is close to irreversible.** It
is compiled into native binaries that change only through store review, so
moving it strands every installed app until a release clears. `api.` rather
than the apex because the apex holds one A record set, and spending it on a
backend API would foreclose ever putting the site or the PWA there. Two
properties of that name fail quietly and are written down in the service README
rather than trusted to memory: `.app` is on the HSTS preload list, so there is
no HTTP fallback ever; and the domain's ICANN registrant verification, if left
unclicked, suspends the registration after fifteen days — at which point the
first symptom is the `sync-went-quiet` alarm and nothing else.

**The bundle includes the AWS SDK.** This is a pnpm workspace, so zipping
`node_modules` produces broken symlinks; and which SDK version a managed runtime
ships is not ours to choose, and has changed before. One esbuild output, not
minified, because a mangled stack in the only record of an outage is a bad trade
for a smaller zip.

**The per-child ceiling moved to validation.** R9 left it unenforced: `colors`
and `styles` are unbounded records and `owned` allows 500×128 characters per
species, so a schema-legal child could be twenty times larger than a real one and
the guard was "an alarm in production". `MAX_CHILD_BYTES` is 256 KB of JSON —
five times the largest profile the game can produce at ten devices, and safely
under 400 KB because JSON counts the quotes and braces that DynamoDB's own
accounting does not. A byte ceiling rather than tighter field bounds, so the
server is not coupled to the client's catalogue and a new accessory does not mean
deploying the server first. It does not make the failure visible on its own — a
400 is swallowed exactly as a 500 is — but it moves the rejection before the
transaction, deterministically, where one log line can name the household and the
child.

**Two alarms are the interesting ones.** A conflict *ratio*, on metric math,
because a 412 is ordinary traffic and only its proportion is diagnostic: strip
the ETag header anywhere in front and the rate goes to ~100% and stays there. And
an alarm on **absence** — a full day with no successful push, missing data
treated as breaching — because an expired certificate, a changed DNS record or an
app built against the wrong hostname all look identical from inside the service,
which is to say they look like nothing at all. Nothing else in the stack looks
for silence. It ships disabled, since a product with no users would fire it
nightly.

**The logs became a new way to leak, and two holes were already open.** A
household id is the only credential this service has, and it sits in the path of
every household request; the store's existing failure line was printing it in the
clear. The request log now records the matched route template and never the raw
path, the store logs a hash, and Zod issue paths are scrubbed — they walk into
record keys, and in this schema those keys are device ids.

**The strings that connect code to stack are tested.** Metric filters match
literal substrings of the log lines, and the Glue columns restate the telemetry
schema; neither side imports the other, and both would drift silently — a stale
alarm never fires, and a stale column answers nothing, which reads like an
answer. `test/infra.test.ts` reads the template as text, drives the real app, and
fails if a pattern no longer matches a real line or a column no longer matches
the Zod shape. `deploy.sh` smoke-tests the ETag round trip on every run, because
that is the one question a successful deploy cannot answer.

**Telemetry became readable** via Glue tables with partition projection — no
crawler and no `MSCK REPAIR`, so nothing has to run when a new day starts and no
maintenance job can quietly stop running — plus an Athena workgroup whose real
purpose is its two guards: results expire in seven days and a single query cannot
scan more than a gigabyte. `infra/athena.sql` holds the questions, and its header
says the thing that matters most about this data: there is no device id,
household id or session id anywhere in it, so it counts events and must never be
read as people.

Not done: no CI (the deploy script runs the tests and refuses to continue), no
API Gateway access log (the function already writes one line per request), no
`DescribeTable` probe on Lambda (the table name arrives from a `Ref`, so there is
no typo to catch).

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
