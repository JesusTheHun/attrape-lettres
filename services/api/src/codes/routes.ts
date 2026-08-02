import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";

import { householdRef, log } from "../log.js";
import { householdId } from "../household/wire.js";
import { CODE_LENGTH, codeHash, isWellFormed, normalise } from "./code.js";
import type { CodeStore } from "./store.js";

/* -------------------------------------------------------------------------- */
/* Redemption — two routes.                                                    */
/*                                                                             */
/*   POST /redeem                  spend a code on a household                 */
/*   GET  /entitlement/{id}        what that household currently holds         */
/*                                                                             */
/* ── This is NOT a payment endpoint, and the distinction is not cosmetic. ─────*/
/*                                                                             */
/* App Review 3.1.1 forbids unlocking paid functionality through anything but   */
/* in-app purchase. A code that is GIVEN AWAY is not a purchase — nobody paid   */
/* anything, here or anywhere — which is why this route takes no price, no      */
/* payment token and no receipt, and why there is no way to buy a code. The     */
/* moment one is sold, this endpoint becomes an alternative payment mechanism   */
/* in a Kids Category app, and that is an account-level problem rather than a   */
/* rejected build. See D61.                                                     */
/*                                                                             */
/* ── What a 500 means to the client. ──────────────────────────────────────────*/
/*                                                                             */
/* Nothing. Invariant 11: a store outage may not tell a family they are not     */
/* unlocked. The client treats every non-answer as "say nothing, change         */
/* nothing", so the only responses that change anything on a device are the     */
/* four business ones below.                                                    */
/* -------------------------------------------------------------------------- */

/**
 * Twelve symbols, but accepted with any separators — so the schema caps LENGTH
 * generously and `normalise` does the real work. A tighter regex here would
 * reject `AB CD-EF GH-JK MN`, which is a perfectly good way to type a code.
 */
const redeemBody = z
  .object({
    code: z.string().min(CODE_LENGTH).max(64),
    household: householdId,
  })
  .strict()
  .openapi({ example: { code: "7FQ4-M2XB-9KDW", household: "5c1f0c8e-2b2a-4a1e-9a0e-9b1a2c3d4e5f" } });

/** What a device is told. No code, no counters, nothing about other families. */
const grantBody = z
  .object({
    kind: z.literal("unlock"),
    grantedAt: z.number().int().min(0),
  })
  .openapi({ example: { kind: "unlock", grantedAt: 1_760_000_000_000 } });

/** Every refusal answers in this shape, and never echoes the code. */
const refusal = z.object({ error: z.enum(["invalid", "unknown", "exhausted", "expired"]) });

const refused = (description: string) => ({
  description,
  content: { "application/json": { schema: refusal } },
});

const redeem = createRoute({
  method: "post",
  path: "/redeem",
  summary: "Spend a redemption code on a household",
  request: { body: { content: { "application/json": { schema: redeemBody } } } },
  responses: {
    200: {
      description:
        "Unlocked. Also returned when the household already held a grant, in "
        + "which case the code keeps its remaining uses.",
      content: { "application/json": { schema: grantBody } },
    },
    400: refused("The code is not well-formed, or the household is not an opaque token."),
    404: refused("No such code."),
    409: refused("Every use of this code is spent."),
    410: refused("This code has lapsed."),
  },
});

const entitlement = createRoute({
  method: "get",
  path: "/entitlement/{id}",
  summary: "What this household holds",
  request: {
    params: z.object({
      id: householdId.openapi({ param: { name: "id", in: "path" } }),
    }),
  },
  responses: {
    200: { description: "A grant.", content: { "application/json": { schema: grantBody } } },
    404: { description: "This household has redeemed nothing." },
  },
});

export function codeRoutes(store: CodeStore, now: () => number = Date.now): OpenAPIHono {
  const app = new OpenAPIHono();

  app.openapi(redeem, async (c) => {
    const body = c.req.valid("json");

    // Normalise and checksum BEFORE touching the store. Thirty-one of every
    // thirty-two malformed guesses die here, which is the cheap half of the
    // brute-force defence (see `code.ts`) and also what turns a parent's typo
    // into an instant answer instead of a round trip.
    const normalised = normalise(body.code);
    if (!normalised || !isWellFormed(normalised)) {
      // Logged at info, not warn: a mistyped code is the expected case, and an
      // alarm that fires every time a parent fumbles twelve characters is an
      // alarm nobody reads. The COUNT is what matters, and a metric filter can
      // have it without a level bump.
      log("info", "codes.malformed", { household: householdRef(body.household) });
      return c.json({ error: "invalid" as const }, 400);
    }

    const result = await store.redeem(codeHash(normalised), body.household, now());

    if (!result.ok) {
      // No code, no hash, no household id — `householdRef` is a one-way handle.
      // Enough to see a sweep in the logs, not enough to replay one.
      log("info", `codes.${result.reason}`, { household: householdRef(body.household) });
      const status = result.reason === "unknown" ? 404 : result.reason === "expired" ? 410 : 409;
      return c.json({ error: result.reason }, status);
    }

    log("info", "codes.redeemed", {
      household: householdRef(body.household),
      // False means the family was already unlocked and no use was spent. It is
      // the difference between "a code was consumed" and "a device asked again",
      // which is the only thing worth counting here.
      fresh: result.fresh,
    });
    return c.json(result.grant, 200);
  });

  app.openapi(entitlement, async (c) => {
    const { id } = c.req.valid("param");
    const found = await store.grant(id);
    if (!found) return c.body(null, 404);
    return c.json(found, 200);
  });

  return app;
}
