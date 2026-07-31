# services/api

Household sync + first-party telemetry. Hono · Zod · Postgres. The only server
this product has.

```bash
DATABASE_URL=postgres://…  pnpm dev     # tsx watch
pnpm test                               # 31 tests, no database needed
```

`src/server.ts` is the only file that reads env, opens a pool or listens.
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

Concurrency control is the `WHERE` clause, not a lock:

```sql
UPDATE household SET doc = $1, revision = revision + 1
 WHERE id = $2 AND revision = $3
```

Zero rows affected *is* the 412. No row is ever held while a phone thinks.

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
nothing about this database: the endpoints are public and unauthenticated.

So the same closed lists live here, and this copy is the one that decides what
gets stored. Every schema is `.strict()`: an unrecognised key is a 400, not a
silently-kept column. `WireChild` has no `name` and no `nameRev`, and a payload
carrying one is rejected. Validation failures never echo the payload back —
reflecting rejected input is how a server that holds no personal data starts
logging some.

Telemetry rows carry no device id, no household id and no session id. There is
deliberately nothing to group them by, which is what makes them anonymous rather
than pseudonymous — and why the table needs no retention policy.

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
- **No migration runner.** `001_init.sql` is idempotent and runs on boot. Write
  the runner when there is a second migration, not before.
