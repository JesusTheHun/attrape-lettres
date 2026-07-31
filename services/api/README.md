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

## tRPC and the thing it cannot do

tRPC is a TypeScript-to-TypeScript protocol. The iOS app is Swift and speaks the
plain REST above through `URLSessionSyncTransport`; a Kotlin Android app will be
the same. Neither can consume a tRPC router, ever.

So the surface is expected to split:

- **Device-facing: plain REST, unchanged.** It is a frozen wire contract with
  shipped clients, ETag concurrency is HTTP's own mechanism rather than something
  layered on top, and it keeps native clients first-class.
- **Backoffice-facing: tRPC.** Both ends are TypeScript, so end-to-end types are
  free and worth having.

One service, two doors. Don't reach for a tRPC-to-REST bridge to make the phones
fit — the REST shape came first and the phones are already built against it.

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
