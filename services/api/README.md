# services/api — not built yet

Node + TypeScript, tRPC, Postgres.

Nothing here yet, but this is not a blank page: **three endpoints are already
specified and already consumed by shipped clients.** The contract below is what
the web app and the iOS app send today, and both fail silent when the endpoint
is absent. Getting it wrong doesn't produce an error — it produces a family
whose two phones quietly stop agreeing.

## The contract the devices already speak

Base URL comes from `VITE_SYNC_URL` (web) / `ALSyncURL` (iOS). Both ship empty,
so sync is inert until this exists.

```
GET  {base}/household/{id}
     200 → WireRoster + ETag header
     404 → no document yet; the client pushes to create it
     no cookies, no credentials, no auth header (credentials: "omit")

PUT  {base}/household/{id}      If-Match: <etag>     (absent on first write)
     200 → new ETag header
     412 → stale; the client re-pulls, re-merges locally, retries

POST {base}/events              { v: "<appVersion>", events: [{event, props}] }
POST {base}/errors              stack + app version, no identifier of any kind
```

`WireRoster` is `{ children: WireChild[], removed: Record<string, number> }`,
where `WireChild` is a child profile **minus `name` and `nameRev`**.

Two properties of that shape are load-bearing:

- **The 412 is the whole concurrency design.** Merging happens on the *device*,
  between the pull and the push — `merge.ts` / `Merge.swift`, pure, commutative
  and idempotent. The server never merges. It only refuses to accept a write
  built on a read it has since superseded, so Mum's phone can't clobber Dad's.
  Serve a strong ETag and compare it exactly.
- **No name ever arrives.** `toWire` strips the child's first name and its LWW
  stamp before upload, so what reaches the server is genuinely anonymous rather
  than pseudonymous. That is invariant 10, it has tests on both clients, and it
  is the difference between a one-paragraph privacy policy and a compliance
  project. **The database must not become the place where PII starts.**

## Where tRPC stops, and why

Not "Swift cannot call tRPC" — it can. The HTTP adapter has a documented,
language-agnostic shape (`GET /trpc/<proc>?input=`, `POST` for mutations, a
`{"result":{"data":…}}` envelope, `?batch=1`), `URLSession` speaks it fine, and
there is codegen that emits Swift clients as well as adapters that expose REST
routes off the same procedures. Two real reasons, neither of them that:

**1. The sync protocol is conditional-request-shaped; RPC is not.** The etag is a
response header, the precondition is a request header, and the conflict is a
status code. The whole concurrency design rests on that: the merge happens on the
device *between* the pull and the push, and 412 is what stops one parent's phone
clobbering the other's. Expressing it as a procedure means moving the etag into
the payload and the conflict into an application-level error — reimplementing
conditional requests inside a body, against a spec that already does it.

**2. The type inference does not cross the language boundary, and it is the whole
point.** tRPC's safety comes from TypeScript inferring over the `AppRouter` type;
that inference exists in `tsc` and nowhere else. A Swift or Kotlin client
re-declares those types by hand or generates them — which is the codegen pipeline
tRPC exists to remove. The device door would pay tRPC's costs (the envelope, the
batching format, coupling to a wire its authors treat as internal across major
versions) and collect none of its benefit.

Third, smaller, but true: the REST contract is already frozen in two shipped
clients. Changing it is a migration, not a design choice.

So the surface splits:

- **Device-facing: plain REST, unchanged.** HTTP's own concurrency mechanism,
  native clients first-class.
- **Backoffice-facing: tRPC.** Both ends are TypeScript, so the inference is real
  and worth having.

One service, two doors.

## Postgres

A household is one row. Sketch, not a schema:

```
household(id uuid primary key, doc jsonb, etag text, updated_at timestamptz)
```

`UPDATE household SET doc = $1, etag = $2 WHERE id = $3 AND etag = $4` returning
zero rows *is* the 412. The document is counters and opaque ids — small, and it
merges on the device, so the server needs no domain knowledge of it at all.

Open, and worth deciding before writing code: whether households are ever
enumerated or expired (nothing today can list them), and whether a join code is
minted server-side or stays the raw uuid the client generates now.

## Before adding a field

Anything new on this wire has to answer: could this identify a six-year-old?
The telemetry allowlist is closed and has no free-text escape hatch on purpose —
a free-text field is how a child's first name ends up on a server. Extend the
tests that assert a name cannot appear in a payload, don't extend the allowlist
by reflex.
