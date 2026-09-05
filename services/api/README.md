# services/api

Household sync + first-party telemetry. Hono · Zod · DynamoDB · S3. The only
server this product has, and it has no database.

```bash
HOUSEHOLD_TABLE=… TELEMETRY_BUCKET=… pnpm dev   # tsx watch
pnpm test                                       # 69 tests, no AWS needed
pnpm dynamo:local && pnpm test:integration      # + 11 against a real DynamoDB
pnpm bundle                                     # one esbuild file for Lambda
./scripts/deploy.sh                             # bundle, package, deploy, smoke
```

## The thing to understand first

**Both clients fail silent.** Anything that is not a 200, a 404 or a 412 is
swallowed and the device keeps playing offline. That is correct — a six-year-old
mid-round must never wait on a network call — and it means nothing that breaks
here reaches a user, a support inbox or a crash reporter. It surfaces months
later as two phones quietly disagreeing about a child's stars.

Every unusual decision below follows from that one sentence.

## The contract

Frozen. It is not designed here — it is *described* here, from two shipped
clients that already speak it (`apps/game-web/src/sync/client.ts`,
`apps/game-ios/Sources/ALCore/Sync/Wire.swift`).

```
GET  /household/{id}                    → 200 + ETag | 404
PUT  /household/{id}  If-Match: "<n>"   → 200 + ETag | 412 | 400
POST /events                            → 204 | 400
POST /errors                            → 204 | 400
POST /redeem                            → 200 | 400 | 404 | 409 | 410
GET  /entitlement/{id}                  → 200 | 404
GET  /health, /openapi.json
```

**The 412 is the whole design.** Merging happens on the *device*, between the
pull and the push — `merge.ts` / `Merge.swift`, pure, commutative, idempotent.
The server never merges and has no domain knowledge of a roster. Its entire
contribution is a revision counter that lets it refuse a write built on a read
that has since been superseded, so one parent's phone cannot clobber the other's.

Concurrency control is a condition on a write, not a lock: the root item's
`rev` must still be the one the pull returned. A failed condition *is* the 412.
Nothing is ever held while a phone thinks.

## Redemption codes

Codes are **given away, never sold** — press, schools, families who help us test.
That is not a marketing preference, it is what keeps this endpoint legal in a
Kids Category app: App Review 3.1.1 forbids unlocking paid functionality through
anything but in-app purchase, and a code nobody paid for is not a purchase. The
route takes no price, no payment token and no receipt, and there is no way to buy
one. The day a code is sold, this becomes an alternative payment mechanism and
the problem is the developer account, not the build.

Mint them from a laptop, with your own credentials:

```bash
pnpm build
node scripts/mint-codes.mjs --count 20 --label presse
node scripts/mint-codes.mjs --count 1 --uses 30 --days 60 --label "ecole-jules-ferry"
node scripts/mint-codes.mjs --count 50 --kind discount --label early-adopter
node scripts/mint-codes.mjs --count 5 --dry-run          # format check, writes nothing
```

`--kind` is what a code buys: `unlock` (the default) gives the game away;
`discount` gives only the right to buy it at the early-adopter price, through
in-app purchase. Neither is ever sold — see D61 for why that sentence is load-
bearing rather than decorative.

**The codes are printed once and nowhere else.** Only their sha256 reaches
DynamoDB, so the terminal output is the only copy that will ever exist. A dump of
the table is then a list of hashes and counters rather than a list of free
unlocks — and nobody, us included, can read a code back out. A lost code is
reissued, not recovered.

A code is twelve Crockford base32 symbols, eleven payload plus a check symbol:
`7FQ4-M2XB-9KDB`. `O`/`I`/`L` are *mapped* to `0`/`1`/`1` rather than banned, so
a parent who misreads a card is still right. Separators and case are decoration.

The format is implemented twice — `src/codes/code.ts` and
`apps/game-ios/Sources/ALCore/Licensing/RedemptionCode.swift` — and the two test
suites share vectors. Drift means every minted code is refused on the device
before it is ever sent, which produces no log line anywhere and looks exactly
like "the codes don't work".

Redemption is one conditional transaction, because the two ways to split it both
lose: counter first and a burnt code buys nothing, grant first and one code
unlocks the world. The three conditions *are* the rules — uses left, not lapsed,
household not already granted — and the last is what makes a second attempt
idempotent rather than wasteful.

`GET /entitlement/{id}` exists for completeness and is not on the client's hot
path: **the device writes the grant down once and never asks again.** That is
invariant 11 taken to its conclusion — an outage, a bad deploy or a mistyped
table name must not re-lock a family who redeemed six months ago. The honest
cost: revocation only reaches codes that have not been spent yet.

There is no rate limiter. Eleven payload symbols is 2^55, and the checksum kills
31 of every 32 malformed guesses before they reach DynamoDB, so a guesser needs
~2^54 round trips through API Gateway and pays for all of them. That is an
entropy argument, not a rate limit — if codes ever become worth attacking, add a
WAF rate rule on `/redeem`. `codes.malformed` is logged so a sweep is visible
first.

## How a household is stored

One partition per household. A root item, and one sidecar item per child:

```
pk = <householdId>   sk = "#root"        rev, removed, order
pk = <householdId>   sk = "child#<id>"   touchedAt, profile
```

**The sharding is invisible on the wire.** The clients pull one document with
one ETag and push it back with one `If-Match`; that contract is frozen and none
of this leaks into it. A pull is still one round trip, because everything lives
in one partition and a single `Query` returns it all — `#` sorts before `c`, so
the root arrives first.

**Why shard.** DynamoDB caps one item at 400 KB, and the household document is
the only unbounded thing here: children × five mascots × owned accessories × a
per-device clear counter for every exercise and level, accumulating for years.
As one blob, a large family approaches that ceiling; per child, each item would
have to reach 400 KB on its own.

**Why the write is a transaction.** Writing children first and the root last
looks equivalent and silently loses stars: a push whose root condition fails has
already overwritten a sidecar with a merge built on an older revision, so the
winning revision now points at a child document missing the other device's
newest play. `TransactWriteItems` makes the root's precondition govern every
sidecar in the same push. It costs double write units — a rounding error on a
rounding error at this volume.

**Three numbers that are one constraint.** A transaction takes at most 100 items
and 4 MB; a push writes one root plus one item per child. So `wireRoster` caps
children at 64, `MAX_CHILD_BYTES` caps one child at 256 KB, and `MAX_BODY_BYTES`
caps the request at 2 MB. Change any of them and check the other two.

**The per-child ceiling is enforced at validation.** `colors` and `styles` are
records with no bound on how many keys they hold, and `owned` allows 500 strings
of 128 characters per species across five species — so a child that is entirely
legal by the field rules can exceed 400 KB, and the integration suite writes
exactly such a child to prove it. `MAX_CHILD_BYTES` is the ceiling that stops it:
256 KB of JSON, which is five times the largest profile this game can produce at
ten devices and safely under DynamoDB's limit once its own accounting (which
does not count the quotes and braces JSON adds) is taken into account.

A 400 is swallowed by both clients exactly as a 500 is, so this does not make the
failure visible on its own. What it buys is that the rejection happens *before*
the transaction, deterministically, in one place that knows which household and
which child — which is what `household.oversized` and its alarm are built on.
Nothing pre-rejects on size at the store, because rejecting a write DynamoDB
would have accepted is worse than the error it prevents.

**Tombstoned children are still returned.** A tombstone does not delete: the
device-side merge resurrects a child whose `touchedAt` is later than the
tombstone, because a parent tidying the roster on one phone must not erase a
week of play that happened on the other. Filtering them here would be a merge
rule living on the server — the one thing this design does not do.

**The root stores the child order.** A `Query` returns sort-key order, which
would quietly re-alphabetise a family's roster on its first sync. Order is
observable in the merge, so it is stored rather than recomputed.

## Deploying

```bash
./scripts/deploy.sh          # everything is already defaulted
```

| | |
|---|---|
| Account | `697245141810`, via SSO profile `attrape` |
| Region | `eu-west-3` (Paris) |
| Hostname | **`api.attrape-lettres.app`** |
| Hosted zone | `Z0958531H2SK1733D6VT` |
| Alarms to | `jonathan.massuchetti@dappit.fr` |

Every one of those is a `${VAR:-default}` in `scripts/deploy.sh`; override any
of them from the environment. `PROFILE=""` falls back to ambient credentials.

One CloudFormation stack: a DynamoDB table, an S3 bucket, one Lambda behind an
HTTP API, the Glue tables that make the telemetry queryable, and the alarms.
`infra/template.yaml` is commented at length; the shape of it is:

**Lambda, not a container.** The workload is idle almost all the time — a
household syncs on app open and on resume — so anything always-on bills for the
twenty-three hours nobody is playing. The free tier is a million requests a
month, forever, rather than for twelve months.

**An HTTP API in front, not a Function URL.** Two reasons, both the same reason.
The endpoint is compiled into a native binary that changes only through store
review, so it must outlive the stack that created it, and a Function URL dies
with its function. And a custom domain over a Function URL means CloudFront,
which forwards no request headers by default — `If-Match` would vanish, every
push would become a create, every create would conflict, and sync would stop for
everyone. An HTTP API passes `If-Match` and `ETag` through untouched.

> **`api.attrape-lettres.app` is effectively permanent.** It gets compiled into
> native binaries that change only through store review, so moving it strands
> every installed app until a new release clears. `DomainName` is optional in
> the template and mandatory in practice: the generated
> `https://<id>.execute-api.<region>.amazonaws.com` dies with its stack.
>
> `api.` rather than the apex on purpose — the apex holds one A record set, and
> spending it on a backend API would foreclose ever putting the site or the PWA
> there.
>
> Two things about that name that fail quietly. `.app` is on the **HSTS preload
> list**, so HTTPS is mandatory at the browser level and there is no HTTP
> fallback, ever, including for debugging. And the domain's **ICANN registrant
> verification** must be completed or the registration is suspended after 15
> days — the domain simply stops resolving, and the first symptom is the
> `sync-went-quiet` alarm.

**The stack does not create everything it uses, on purpose.** The table, the
bucket and the log group are `Retain` on both delete and replace. `delete-stack`
is one command, and the table is every star every child in every family has
earned — unrecoverable, because a device that pulls a 404 creates a fresh
household and the join code linking a family's phones is gone.

**The role's omissions are the point.** No `DeleteItem`, `UpdateItem`, `Scan` or
`GetItem`; no `s3:GetObject` or `DeleteObject`; no `logs:CreateLogGroup`. A
compromised function can overwrite one household at a time under a condition it
must first satisfy, and append telemetry. It cannot enumerate families, erase a
child's progress, or read a single analytics row back. A test asserts this.

## What it costs, and how to stop it

The workload is one round trip on app open and on resume. Assume three devices
per family and four syncs per device per day — deliberately generous, since a
six-year-old does not open the app four times a day — so **12 requests per
family per day**, each a `Query` plus a `TransactWriteItems` of one root and one
sidecar per child.

| Families | Requests/month | Lambda | API Gateway | DynamoDB | S3 + Athena | **Total** |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 36 k | free tier | free tier¹ | ~€0.02 | ~€0.01 | **~€0** |
| 1 000 | 360 k | free tier | ~€0.35 | ~€0.20 | ~€0.05 | **< €1** |
| 10 000 | 3.6 M | ~€0.60 | ~€3.40 | ~€2.00 | ~€0.50 | **~€7** |

¹ API Gateway's free tier is twelve months; Lambda's million requests a month is
permanent. The 10 000-family row assumes no free tier at all.

Two fixed costs sit under all of it and do not scale with families: point-in-time
recovery on the table (~€0.20/GB-month, cents at this size) and CloudWatch log
storage (30-day retention, one line per request — the 10 000-family row is a few
hundred megabytes, under €1).

**The number that could actually hurt is not in that table.** A loop in a shipped
client cannot be patched without store review, so a bug that syncs in a tight
loop would bill for weeks. That is what `MaxConcurrency` is for: reserved
concurrency of 20, which is roughly a thousand families opening the app in the
same second and far beyond any real load. Past it, devices are throttled — a sync
they retry on the next resume, and an alarm we see immediately.

**A new AWS account cannot have that ceiling, and that is fine.** Lambda refuses
any reservation leaving the account under 10 unreserved executions, and a fresh
account's whole limit *is* 10 — so nothing above 0 is legal, and asking for 20
fails the stack create outright. `deploy.sh` defaults to `MAX_CONCURRENCY=auto`,
reads the live limit, and reserves nothing (`-1`) when there is no room. That
costs nothing while the account limit is 10, because 10 is a tighter ceiling than
20 would have been. It costs something the day the quota is raised — which is
why `auto` re-checks on every deploy and puts the reservation back by itself
rather than leaving a `-1` hard-coded somewhere nobody rereads. The warning it
prints carries the `service-quotas` command.

**Setting `MaxConcurrency=0` is the kill switch.** Every invocation is throttled,
which both clients swallow, so children keep playing offline and nobody sees an
error; families simply stop syncing between devices. `deploy.sh` asks for
confirmation before doing it, because it is the one deploy that looks like every
other one and quietly turns the product off.

```bash
MAX_CONCURRENCY=0 ./scripts/deploy.sh   # off
MAX_CONCURRENCY=20 ./scripts/deploy.sh  # back on
```

## Detection, because nothing else will tell you

`src/log.ts` writes one line of JSON per request, plus a line for a validation
refusal, an unhandled throw and a failed store write. The alarms in the template
are CloudWatch metric filters over those lines.

| Alarm | Fires when |
|---|---|
| `server-errors` | A handled 500. **Lambda's own `Errors` metric cannot see these** — `onError` catches the throw, so the invocation succeeded as far as Lambda knows. |
| `write-failures` | A push reached the store and did not land. |
| `oversized-child` | A child no longer fits in one item. That family's sync has stopped for good. |
| `conflict-storm` | Most pushes are conflicting. |
| `lambda-errors` / `lambda-throttles` | Init failure, timeout, no concurrency left. |
| `table-errors` | DynamoDB failed on its own side. |
| `sync-went-quiet` | A full day with no successful push. Ships disabled. |

The last two are the ones worth understanding.

**`conflict-storm` is the ETag alarm.** A 412 is ordinary traffic — it is how two
phones take turns — so its absolute count says nothing and its *ratio* says
everything. Strip or weaken the `ETag` header anywhere in front of this service
and both clients start sending no `If-Match` at all: every push becomes a create,
every create conflicts, the rate goes to ~100% and stays there while the apps
keep playing offline as though nothing happened. It is metric math over pushes
and conflicts, guarded on volume so a quiet hour cannot page anybody.

**`sync-went-quiet` alarms on absence.** An expired certificate, a changed DNS
record, an app built against the wrong hostname — none of them produce an error
here, because none of them reach here. They look identical from inside the
service: traffic simply stops. It treats missing data as breaching, because no
data *is* the alarm. It ships off, because a product with no users would fire it
nightly; turn it on the day there is a baseline to fall below.

**The strings are a contract, and `test/infra.test.ts` enforces it.** The filters
match literal substrings of the log lines. That test reads every `FilterPattern`
out of the template, drives the real app until it produces real log lines, and
fails if any pattern no longer matches one — so renaming an event breaks a test
instead of leaving a stack of alarms that read healthy forever. It does the same
for the Glue columns against the Zod schema, and for the partition template
against the keys the sink writes.

**`deploy.sh` smoke-tests the ETag round trip on every run.** Not "did it
deploy" — that is what the deploy command answered — but "does a stale
`If-Match` still come back 412 and a fresh one still come back 200". It is the
one question a successful deploy cannot answer.

## Reading the telemetry

The stack creates a Glue database with an `events` and an `errors` table over
the NDJSON in S3, plus an Athena workgroup. Partitions come from **projection**,
not a crawler: Athena works the date out of the `dt=YYYY-MM-DD` prefix, so
nothing runs when a new day starts and no maintenance job can quietly stop
running.

`infra/athena.sql` holds the questions — what gets played and what gets
abandoned, accuracy by exercise and level, the money funnel, purchase failures,
errors grouped by message, which app versions are still in the wild. Every one
filters on `dt`, which is the whole cost control: Athena bills per byte scanned
and the workgroup caps a single query at one gigabyte.

**None of this data has a device id, a household id or a session id.** Two rows
from one phone are indistinguishable from two rows from two phones. There is no
"users", no retention curve and no per-person cohort, by construction — see
invariant 10 and the Kids Category note in the root `CLAUDE.md`. Read these as
event counts, never as people.

## What the tests do and do not prove

`pnpm test` stubs every store call. It exercises real routing, real Zod
validation and the real store logic, and it proves the commands we build are the
ones we meant to build — **and nothing about whether DynamoDB accepts them**. A
stub also cannot lose a race: the in-memory double is single-threaded, so its
"concurrency" tests are two sequential writes wearing the same etag.

`pnpm test:integration` runs the same store against `amazon/dynamodb-local` for
the handful of properties only a real engine can answer: the condition
expressions parse, the transaction actually serialises under eight genuinely
concurrent writers, an empty map survives marshalling, and the paging loop runs
at all. Without `DYNAMO_ENDPOINT` that file skips loudly, so a skip is never
mistaken for coverage.

One thing neither can prove: **DynamoDB Local does not enforce the 400 KB item
limit.** A 430 KB child is written there without complaint. That is recorded as a
passing test asserting the gap — and asserting, in the same test, that the wire
schema refuses the child that the store would have taken.

`src/server.ts` and `src/lambda.ts` are the only files that read env or listen;
`src/aws.ts` is the only one that opens a client. Everything else is a pure
function of its dependencies, which is why the tests run against real routing and
real validation with an in-memory store, in milliseconds.

## Privacy is enforced here, not just on the clients

The clients already strip a child's first name before upload and already have a
closed telemetry allowlist. That proves *our clients* are well-behaved. It says
nothing about this store: the endpoints are public and unauthenticated.

So the same closed lists live here, and this copy is the one that decides what
gets stored. Every schema is `.strict()`: an unrecognised key is a 400, not a
silently-kept column. `WireChild` has no `name` and no `nameRev`, and a payload
carrying one is rejected. Validation failures never echo the payload back —
reflecting rejected input is how a server that holds no personal data starts
logging some.

**The logs are held to the same rule**, which took two fixes to get right. A
household id is the only credential this service has — whoever holds one can read
and overwrite that family's roster — and it sits in the path of every household
request. So the request log records the matched route template (`/household/:id`)
and never `c.req.path`, and the store's failure line records a hash of the id
rather than the id. A Zod issue path walks into record *keys*, and in this schema
those keys are device ids, so paths are scrubbed: a segment survives only if it
is an array index or looks like a schema field name, and every id this system
mints fails that test.

Telemetry rows carry no device id, no household id and no session id. There is
deliberately nothing to group them by, which is what makes them anonymous rather
than pseudonymous — and why the data needs no retention policy. The object key
is random rather than derived from anything in the payload, so it cannot quietly
become a grouping handle either. The 400-day lifecycle rule on the bucket is a
cost rule, not a privacy one.

`message` and `stack` on `/errors` are the only free-form strings this service
accepts anywhere. Both ends truncate.

Adding a field to any of this means extending the tests that assert a name
cannot appear in a payload. Never widen one side by reflex.

## Two operational hazards

**Do not let anything strip or rewrite the `ETag` header.** Both clients read
`etag ?? ""` on the pull and send no `If-Match` when it is empty — so a proxy
that drops ETag turns every push into a create, which conflicts, which retries
three times and gives up. Sync then fails permanently and *silently*.
Compression middleware that weakens ETags does the same thing. This is the
single easiest way to break this service without anyone noticing, which is why
`conflict-storm` exists and why `deploy.sh` checks it every time.

**Deploy this before a client that adds a mascot.** The species key is a closed
enum. A sixth mascot shipped to clients first would 400 every push from an
updated device.

## Deliberately not done

- **No auth.** A household id is a 128-bit secret and the only credential;
  that is the entire identity model, and it is why the server holds nothing
  worth stealing. Guessing one gets you a stranger's star counters.
- **No counter ceiling.** A hostile client can inflate its own child's stars.
  There is no shared economy and no leaderboard — their kid gets free hats.
  Capping risks rejecting a legitimate write, and for a fail-silent client that
  means sync stops with no visible cause.
- **No CORS.** The clients are native apps and a self-hosted PWA on one origin.
  A permissive default would hand any page on the internet a write endpoint. Add
  the one origin explicitly when the backoffice lands.
- **No cleanup of tombstoned sidecars.** A deleted child's item is kept, because
  a tombstone does not delete — a later `touchedAt` resurrects the child, and a
  swept sidecar would make that unrecoverable.
- **No secondary index, no stream, no TTL.** Nothing is queried across
  households, so there is nothing to index. "How many families are there" is a
  scan or an S3 inventory report, run when someone actually asks.
- **No `DescribeTable` probe on Lambda.** `server.ts` keeps it, because a human
  typing `HOUSEHOLD_TABLE` can typo it and every family would look brand new.
  Under CloudFormation the name comes from a `Ref` to the table it just created,
  so there is nothing to catch and no reason to spend a control-plane call on
  every cold start.
- **No API Gateway access log.** The function already writes one structured line
  per request; a second copy would double the log bill to say less.
- **No CI.** The deploy script runs the tests and refuses to continue if they
  fail, which is the property that matters. A pipeline is worth adding when more
  than one person deploys.
