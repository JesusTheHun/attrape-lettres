import { createHash } from "node:crypto";

/* -------------------------------------------------------------------------- */
/* What a redemption code is.                                                  */
/*                                                                             */
/* A code is read off a card, a slip of paper or a screenshot, and typed by a   */
/* parent on a phone keyboard with a six-year-old leaning on their arm. Every   */
/* decision here follows from that:                                            */
/*                                                                             */
/*   • CROCKFORD BASE32. Thirty-two symbols, and the four that get confused —   */
/*     O/0, I/1, L/1 — are MAPPED rather than banned. Someone who reads « O »   */
/*     where we printed « 0 » is right, and the code works.                     */
/*   • SEPARATORS ARE FREE. Hyphens, spaces, tabs, nothing — all normalise to   */
/*     the same twelve symbols. We print `XXXX-XXXX-XXXX`; we accept whatever   */
/*     arrives.                                                                */
/*   • A CHECKSUM. Eleven payload symbols and one check symbol, so a typo is    */
/*     rejected on the device without a network call and without an attempt     */
/*     against the store. That is a courtesy to the parent and, below, the      */
/*     cheapest half of the brute-force defence.                                */
/*                                                                             */
/* ── Entropy, and why there is no rate limiter yet. ───────────────────────────*/
/*                                                                             */
/* Eleven payload symbols is 32^11 = 2^55. A guesser has to make ~2^54 HTTPS    */
/* round trips through an API Gateway to expect one hit; at a thousand requests */
/* a second that is half a billion years, and they pay for every one of them.   */
/* The checksum means 31 of every 32 malformed guesses never reach DynamoDB.    */
/*                                                                             */
/* That is the entropy argument, and it is the whole defence today. It is not a */
/* substitute for a rate limit — it just means the rate limit is not urgent. If */
/* codes ever become valuable enough to attack, the answer is a WAF rate rule   */
/* on this route, not a longer code. `codes.miss` is logged so a sweep is       */
/* visible before it is expensive.                                             */
/*                                                                             */
/* ── Codes are stored HASHED. ─────────────────────────────────────────────────*/
/*                                                                             */
/* We keep sha256 of the normalised code, never the code. A dump of this table  */
/* is then a list of hashes and redemption counts rather than a list of free    */
/* unlocks, and nobody with database access — including us — can read a code    */
/* back out and use it. The cost is that a lost code cannot be recovered, only  */
/* revoked and reissued, which is the right trade for a thing we mint in        */
/* batches for free.                                                           */
/* -------------------------------------------------------------------------- */

/** Crockford base32: no I, no L, no O, no U. */
export const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** Eleven payload symbols plus one check symbol. */
export const PAYLOAD_LENGTH = 11;
export const CODE_LENGTH = PAYLOAD_LENGTH + 1;

/**
 * Crockford's confusion map, applied before anything else.
 *
 * `U` is excluded from the alphabet and deliberately NOT mapped: Crockford
 * leaves it out to avoid accidental obscenities, and mapping it to `V` would
 * silently turn one valid-looking code into another.
 */
const CONFUSABLE: Record<string, string> = { O: "0", I: "1", L: "1" };

/**
 * Anything a human typed → the twelve canonical symbols, or null.
 *
 * Returns null rather than throwing: this is called on user input, and every
 * caller's answer to bad input is the same 400.
 */
export function normalise(raw: string): string | null {
  let out = "";
  for (const character of raw.toUpperCase()) {
    const mapped = CONFUSABLE[character] ?? character;
    if (ALPHABET.includes(mapped)) out += mapped;
    // Everything else — hyphens, spaces, punctuation, U — is dropped as
    // formatting. A code containing a real `U` cannot exist, so dropping it
    // fails the checksum a moment later rather than passing something wrong.
  }
  return out.length === CODE_LENGTH ? out : null;
}

/**
 * The check symbol for eleven payload symbols.
 *
 * A position-weighted sum mod 32. It catches every single-symbol error and
 * every transposition of two adjacent DIFFERENT symbols, which between them are
 * essentially all the mistakes a person makes copying twelve characters.
 *
 * MIRRORED IN SWIFT — `apps/game-ios/Sources/ALCore/Licensing/RedemptionCode.swift`.
 * The two implementations share test vectors and must not drift; a mismatch
 * means every code minted here is rejected on the device before it is ever sent,
 * which looks exactly like "the codes do not work".
 */
export function checkSymbol(payload: string): string {
  let sum = 0;
  for (let i = 0; i < payload.length; i++) {
    const value = ALPHABET.indexOf(payload[i]!);
    if (value < 0) return "";
    sum += (i + 1) * value;
  }
  return ALPHABET[sum % ALPHABET.length]!;
}

/** Does this normalised code carry its own check symbol? */
export function isWellFormed(normalised: string): boolean {
  if (normalised.length !== CODE_LENGTH) return false;
  const payload = normalised.slice(0, PAYLOAD_LENGTH);
  return checkSymbol(payload) === normalised[PAYLOAD_LENGTH];
}

/** `XXXX-XXXX-XXXX` — what we print. Purely cosmetic; nothing parses it. */
export function format(normalised: string): string {
  return normalised.replace(/(.{4})(?=.)/g, "$1-");
}

/**
 * The key a code is stored under. sha256 of the NORMALISED form, so the
 * hyphenated and unhyphenated spellings of one code are one row.
 */
export function codeHash(normalised: string): string {
  return createHash("sha256").update(normalised).digest("hex");
}

/**
 * Mint one code from 11 random symbols. Used by `scripts/mint-codes.mjs`;
 * exported here so the format has exactly one owner.
 *
 * `random` takes the alphabet length and returns an index — injected so the
 * tests can mint deterministically. Production passes a CSPRNG.
 */
export function mint(random: (bound: number) => number): string {
  let payload = "";
  for (let i = 0; i < PAYLOAD_LENGTH; i++) payload += ALPHABET[random(ALPHABET.length)];
  return payload + checkSymbol(payload);
}
