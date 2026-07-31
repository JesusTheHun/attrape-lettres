# services/api

Household sync + first-party telemetry. Hono · Zod · DynamoDB · S3. The only
server this product has, and it has no database.

```bash
HOUSEHOLD_TABLE=… TELEMETRY_BUCKET=… pnpm dev   # tsx watch
pnpm test                                       # 46 tests, no AWS needed
```

`src/server.ts` is the only file that reads env, opens a client or listens.
Everything else is a pure function of its dependencies, which is why the tests
run against real routing and real validation with an in-memory store, in
milliseconds.

## The contract

Frozen. It is not designed here — it is *described* here, from two shipped
clients that already speak it (`apps/game-web/src/sync/client.ts`,
`apps/game-ios/Sources/ALCore/Sync/Wire.swift`).

```
GET  /household/{id}                    → 200 + ETag | 404
PUT  /household/{id}  If-Match: "<n>"   → 200 + ETag | 412 | 400
POST /events                            → 204 | 400
POST /errors                            → 204 | 400
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
the only unbounded thing here: children × five mascots × up to five hundred
owned accessories × a per-device clear counter for every exercise and level,
accumulating for years. As one blob, a large family approaches that ceiling; per
child, each item would have to reach 400 KB on its own. Crossing it fails the
push — and both clients swallow the failure, so the family would simply stop
syncing and nobody would be told.

**Why the write is a transaction.** Writing children first and the root last
looks equivalent and silently loses stars: a push whose root condition fails has
already overwritten a sidecar with a merge built on an older revision, so the
winning revision now points at a child document missing the other device's
newest play. `TransactWriteItems` makes the root's precondition govern every
sidecar in the same push. It costs double write units — a rounding error on a
rounding error at this volume.

**Two numbers that are coupled.** A transaction takes at most 100 items and a
push writes one root plus one item per child, which is what makes `wireRoster`'s
cap of 64 children load-bearing rather than polite. Raising it above 99 would
start rejecting valid rosters at the store instead of at the schema.

**Tombstoned children are still returned.** A tombstone does not delete: the
device-side merge resurrects a child whose `touchedAt` is later than the
tombstone, because a parent tidying the roster on one phone must not erase a
week of play that happened on the other. Filtering them here would be a merge
rule living on the server — the one thing this design does not do — and it would
make that resurrection impossible.

**The root stores the child order.** A `Query` returns sort-key order, which
would quietly re-alphabetise a family's roster on its first sync. Order is
observable in the merge, so it is stored rather than recomputed, and a pull
returns exactly the array that was pushed.

### The table

One table, no secondary index, no stream. Nothing is ever queried across
households — there is no code-to-id lookup, because the join code *is* the
household id.

```bash
aws dynamodb create-table \
  --table-name attrape-households \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST
```

The service will not create it. A process that can create its own store will
happily create a second, empty one after a typo in `HOUSEHOLD_TABLE` — at which
point every family looks brand new and nothing errors. It calls `DescribeTable`
on boot and refuses to start instead.

**`If-Match` absent means "I believe this household does not exist"** — which is
exactly what a client says after a 404. So it creates, and conflicts if
something is already there. It is never a blind overwrite: a client that lost
its etag must re-read before it can write. A malformed etag is likewise a 412
rather than a 400, because "re-pull and retry" is the right client response and
a 400 would be swallowed and retried forever.

## Two operational hazards

**Do not let anything strip or rewrite the `ETag` header.** Both clients read
`etag ?? ""` on the pull and send no `If-Match` when it is empty — so a proxy
that drops ETag turns every push into a create, which conflicts, which retries
three times and gives up. Sync then fails permanently and *silently*, because
both clients swallow the error and keep playing offline. Compression middleware
that weakens ETags does the same thing. This is the single easiest way to break
this service without anyone noticing.

**Deploy this before a client that adds a mascot.** The species key is a closed
enum. A sixth mascot shipped to clients first would 400 every push from an
updated device.

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

Telemetry rows carry no device id, no household id and no session id. There is
deliberately nothing to group them by, which is what makes them anonymous rather
than pseudonymous — and why the data needs no retention policy. The object key
is random rather than derived from anything in the payload, so it cannot quietly
become a grouping handle either.

Telemetry lands in S3, not in the households table. Newline-delimited JSON under
`events/dt=YYYY-MM-DD/` and `errors/dt=YYYY-MM-DD/`, one object per accepted
batch — one request to write, readable with Athena or by downloading a day, and
nothing running in between. It is a different store from family data for the
same reason it is a different port in the code.

`message` and `stack` on `/errors` are the only free-form strings this service
accepts anywhere. Both ends truncate.

Adding a field to any of this means extending the tests that assert a name
cannot appear in a payload. Never widen one side by reflex.

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
  swept sidecar would make that unrecoverable. The cost is one small item per
  removed child, read on that family's pulls and nobody else's.
- **No secondary index, no stream, no TTL.** Nothing is queried across
  households, so there is nothing to index. "How many families are there" is a
  scan or an S3 inventory report, run when someone actually asks.
