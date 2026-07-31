# packages/ — empty on purpose

Shared TypeScript goes here. Nothing does yet, and the obvious candidate was
deliberately left where it is.

## The candidate

`content.ts`, `levels.ts`, `rewards.ts` and `types.ts` are the domain: the word
lists, the difficulty ladders, the reward curve and the shapes everything else
is written against. They currently live in `apps/game-web/src/`, and both the
API and the backoffice will eventually want them — the API to validate a wire
payload, the backoffice to name an exercise.

Extracting them into `packages/domain` was scoped out of the reshape rather than
forgotten. Two reasons to do it deliberately, later:

- Those four files carry invariants (8, 9, 11) and a **persistence contract**.
  `ledgerKey` produces `"<exercise>:<level>"`, and that string is the key of
  every counter on disk and on the wire, in two shipped clients. Moving the file
  is trivial; moving it without re-verifying against the tests that hold those
  invariants is how a repo silently breaks a child's saved stars.
- It buys nothing until a second consumer exists. Right now the only importer is
  the app the files already live in.

## What does *not* belong here

The iOS app's ported copies. `apps/game-ios/Sources/ALCore` has its own
`Rewards.swift`, `Levels.swift` and content tables, hand-written against the
TypeScript and pinned by ~1450 tests. That duplication is the design — two
native apps, not one core with shells — and no shared package removes it.
