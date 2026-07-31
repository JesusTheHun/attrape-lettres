import { handle } from "hono/aws-lambda";

import { buildApp } from "./app.js";
import { wireFromEnv } from "./aws.js";
import { log } from "./log.js";

/* -------------------------------------------------------------------------- */
/* The entrypoint that ships. One Lambda behind an HTTP API, and that is the    */
/* whole deployment — see `infra/template.yaml`.                                */
/*                                                                             */
/* WHY LAMBDA. This service is idle almost all of the time: a household syncs   */
/* on app open and on resume, so a family of three devices makes a handful of   */
/* requests a day. Anything always-on bills for the twenty-three hours nobody   */
/* is playing, and the free tier here is a million requests a month, forever,   */
/* rather than for twelve months. Scale-to-zero is also the honest shape for a  */
/* product with no users yet.                                                   */
/*                                                                             */
/* WHY AN HTTP API IN FRONT AND NOT A FUNCTION URL. A Function URL is free and  */
/* one resource simpler, and it was the wrong choice for two reasons. The URL   */
/* is baked into a native binary that changes only through store review, so it  */
/* has to outlive the stack that created it — a Function URL dies with its      */
/* function, and every installed app would point at nothing with no way to fix  */
/* it. And a custom domain over a Function URL means CloudFront, which by       */
/* default forwards no request headers at all: `If-Match` would vanish, every   */
/* push would become a create, every create would conflict, and sync would stop */
/* permanently and silently for everyone. An HTTP API passes `If-Match` and     */
/* `ETag` through untouched, which is the one thing this contract cannot lose.  */
/*                                                                             */
/* NO `DescribeTable` PROBE HERE, unlike `server.ts`. That guard exists because */
/* a human typing `HOUSEHOLD_TABLE` can typo it, at which point every family    */
/* looks brand new and nothing errors. Here CloudFormation sets it from a `Ref` */
/* to the table it just created, so the name cannot be wrong, and the probe     */
/* would be a control-plane call on every cold start buying nothing.            */
/* -------------------------------------------------------------------------- */

// Module scope, so the clients and their connection pools survive across
// invocations on a warm container. A failure here fails the init, which shows
// up as a Lambda error metric — loudly, which is right: nothing else about this
// service is loud.
const wiring = wireFromEnv();

log("info", "boot", { runtime: "lambda", telemetry: wiring.telemetry });

export const handler = handle(buildApp(wiring.deps));
