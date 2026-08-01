# Attrape-Lettres

A French early-reading game for young children (~6yo). Seventeen exercises across
two skills — letters and syllables — every level unlocked at all times, no fail
state, and nothing to buy in front of a child.

## The repo

```
apps/
  game-web/       the PWA — Vite · React 18 · TypeScript (strict) · Tailwind
  game-ios/       the native app — SwiftPM package + a thin Xcode wrapper
  game-android/   the native app — Kotlin · Compose · Gradle
  backoffice/     not built yet
services/
  api/            household sync + telemetry — Hono · Zod · DynamoDB · S3,
                  shipped as one Lambda behind an HTTP API
packages/         shared TypeScript — empty on purpose
```

`apps/game-web`, `apps/game-ios` and `apps/game-android` are independent
implementations of the same game. They are not a shared core with three shells:
each port was written against the web app line by line, and host tests are what
hold them in agreement — ~1450 on iOS, 1596 on Android. What they genuinely share is the baked
voice-over — 845 clips that live once, in `apps/game-web/src/vo/clips/`,
hard-linked into each app's bundle at build time and never committed twice.

Each app carries its own docs. `apps/game-ios/` and `apps/game-android/` have an
`ARCHITECTURE.md` and a `DECISIONS.md` recording every decision their port made
and why.

## Run

```bash
pnpm install
pnpm dev                          # the web app

cd apps/game-ios && swift test    # the iOS suite, on the host, no simulator
open apps/game-ios/App/AttrapeLettres.xcodeproj

cd apps/game-android && ./gradlew test        # the Android suite, no emulator
cd apps/game-android && ./gradlew assembleDebug
```

From the root, `pnpm build`, `pnpm typecheck` and `pnpm test` run across every
JS/TS package; each also works from inside a package.

## Three design decisions worth knowing

1. **Content is authored, not computed.** French syllabification is a rabbit
   hole. Syllables live in `content.ts` as data (`{ word, syllables, emoji }`),
   pre-split by a human who checked how the fragment actually sounds.
2. **Nothing identifying leaves the device.** A child's first name never goes on
   the wire, telemetry has a closed property allowlist with no free-text escape
   hatch, and the device id — which exists only so two phones can merge one
   child's stars — is never sent anywhere. Tests assert all of it.
3. **Money never fails closed.** An unreachable store, a timed-out receipt check
   or a flat network may not lock a child out. We would rather give the game away
   than show one paying six-year-old a paywall because StoreKit blinked.

`CLAUDE.md` has the full invariant list — the rules that keep the game feeling
alive to a child, and the ones you cannot break quietly.
