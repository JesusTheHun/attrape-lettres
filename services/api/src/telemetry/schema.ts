import { z } from "@hono/zod-openapi";

/* -------------------------------------------------------------------------- */
/* The telemetry allowlist, RE-ENFORCED.                                       */
/*                                                                             */
/* `apps/game-web/src/telemetry.ts` already has a closed event list and a       */
/* closed property allowlist with no free-text escape hatch, and `sanitize()`   */
/* drops anything not on it. That protects us from our own accidents. It does   */
/* NOT protect this database: the endpoint is public and unauthenticated, and   */
/* anyone can POST to it.                                                       */
/*                                                                             */
/* So the same closed lists live here, and this copy is the one that decides    */
/* what gets stored. Without it, "no personal data reaches our server" would    */
/* mean "no personal data reaches our server as long as every client is         */
/* well-behaved" — a claim about our own code, not about the data.             */
/*                                                                             */
/* Keep in step with the client list. Adding an event or a property means       */
/* editing BOTH, and extending the tests that assert a name cannot appear in a  */
/* payload — never widening one side by reflex.                                 */
/* -------------------------------------------------------------------------- */

export const TELEMETRY_EVENTS = [
  "exercise_started",
  "session_completed",
  "shop_opened",
  "item_bought",
  "mascot_grown",
  "trial_started",
  "trial_expired",
  "paywall_shown",
  "purchase_completed",
  "purchase_failed",
  "purchase_restored",
] as const;

export const EXERCISE_IDS = [
  "first-letter",
  "find-sound",
  "hear-syllable",
  "pick-vowel",
  "sound-twins",
  "read-image",
  "match-case",
  "match-script",
  "fill-blank",
  "order-syllables",
  "find-intruder",
  "spell-syllable",
  "spell-syllable-plus",
  "spell-two-syllables",
  "spell-syllable-plus-mixed",
  "spell-two-syllables-mixed",
  "spell-sound",
] as const;

/**
 * Every property that may be stored. All numeric except `exercise`, which is a
 * closed enum of ids — never a free string. `.strict()` is the load-bearing
 * word: an unknown key is a 400, not a silently-kept column.
 *
 * Exported so `test/infra.test.ts` can hold it against the `props` struct in
 * `infra/template.yaml`. A property added here and not there is written to S3
 * and then invisible to every query — the expensive kind of missing data, the
 * kind you believe you already have.
 */
export const eventProps = z
  .object({
    exercise: z.enum(EXERCISE_IDS).optional(),
    level: z.number().int().min(0).max(1000).optional(),
    rounds: z.number().int().min(0).max(1000).optional(),
    perfect: z.number().int().min(0).max(1000).optional(),
    points: z.number().int().min(0).max(1_000_000).optional(),
    cost: z.number().int().min(0).max(1_000_000).optional(),
    stage: z.number().int().min(0).max(9).optional(),
    daysLeft: z.number().int().min(-3650).max(3650).optional(),
  })
  .strict();

/** App version, stamped into the bundle at build time. The only build-time value. */
const appVersion = z.string().min(1).max(40);

export const eventBatch = z
  .object({
    v: appVersion,
    events: z
      .array(z.object({ event: z.enum(TELEMETRY_EVENTS), props: eventProps }).strict())
      .min(1)
      .max(100),
  })
  .strict();

/**
 * Error reports. NOT consent-gated, because the payload carries no identifier —
 * that split is deliberate, so we still hear about the bug that breaks the game
 * for the ~60% of parents who decline analytics.
 *
 * `message` and `stack` are the only free-form strings this service accepts
 * anywhere. The client never attaches app state (the roster holds children's
 * first names, and one careless context dump would ship them here) and
 * truncates before sending; we truncate again on arrival, because a client's
 * word is not a constraint.
 */
export const errorReport = z
  .object({
    v: appVersion,
    where: z.string().max(60),
    message: z.string().max(300),
    stack: z.string().max(2000),
  })
  .strict();

export type EventBatch = z.infer<typeof eventBatch>;
export type ErrorReport = z.infer<typeof errorReport>;
