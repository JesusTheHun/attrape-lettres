import Foundation

/* -------------------------------------------------------------------------- */
/* Handing a household id from one phone to the other.                          */
/*                                                                             */
/* `SyncClient` has had `createHousehold()` and `joinHousehold(_:)` since it    */
/* was written, and until now nothing called either outside a test: the         */
/* endpoint was empty, so `enabled` was false whatever they did. With a server  */
/* to talk to, the missing half is the one thing no amount of merge logic can   */
/* supply — two devices agreeing on WHICH household they are.                   */
/*                                                                             */
/* There is no TSX to port here. The PWA has no pairing screen either, so this  */
/* is the first behaviour the two apps do not share; the divergence is recorded */
/* in DECISIONS.md rather than hidden.                                          */
/*                                                                             */
/* ── The id IS the credential ──────────────────────────────────────────────── */
/* There are no accounts, no e-mail and no login. Whoever holds this string can */
/* read and write the household document, which is why `toWire` strips names    */
/* before anything leaves the device: what it unlocks is progress counters and  */
/* opaque child ids, never a six-year-old's first name. That is what makes it   */
/* safe to put in a QR code a parent holds up in their own kitchen — and it is  */
/* also why this file refuses to join anything it does not recognise, rather    */
/* than trusting a string that arrived from outside the app.                    */
/*                                                                             */
/* ── Why a custom scheme, and what it costs ────────────────────────────────── */
/* A universal link would be better: it cannot be claimed by another app, and   */
/* it degrades to a web page when the app is not installed. It needs an         */
/* associated-domains entitlement and an apple-app-site-association served from */
/* the API — both cheap now that we own the domain, neither free. A custom      */
/* scheme can be registered by any app on the device, so a hostile one could    */
/* intercept a pairing link. What it would gain is a stranger's household id,   */
/* which grants anonymous progress counters and nothing else; the same          */
/* stranger could equally photograph the QR. Worth revisiting when the API      */
/* serves an AASA, not worth blocking pairing on today.                         */
/* -------------------------------------------------------------------------- */

public enum PairingLink {
    /// Registered in the app target's `Info.plist` under `CFBundleURLTypes`.
    /// Matching that file is a contract; `PairingLinkTests` cannot see the
    /// plist, so `AppLinkContractTests` reads it as text and compares.
    public static let scheme = "attrape-lettres"
    public static let host = "pair"
    public static let householdQueryItem = "h"

    // MARK: - Minting

    /// The string a QR encodes and the share sheet sends.
    ///
    /// Returns nil for an id this module would refuse to accept back, so a
    /// malformed household can never be handed out in the first place — the
    /// check sits on both sides of the wire on purpose.
    public static func url(household id: String) -> URL? {
        guard isWellFormed(id) else { return nil }
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.queryItems = [URLQueryItem(name: householdQueryItem, value: id)]
        return components.url
    }

    // MARK: - Accepting

    /**
     * The household id in an incoming link, or nil.
     *
     * Nil for every kind of wrong: another scheme, another host, a missing or
     * repeated query item, and — the one that matters — an id that is not a
     * shape this app mints. A link is untrusted input; it arrives from a
     * camera pointed at a stranger's poster as readily as from the other
     * parent's phone.
     *
     * Deliberately total: it answers nil rather than throwing, because the
     * caller's only sane response to a bad link is to ignore it. Invariant 3's
     * spirit — nothing about this can produce an error state a family has to
     * clear.
     */
    public static func household(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme,
            url.host?.lowercased() == host,
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            let items = components.queryItems
        else { return nil }

        let matches = items.filter { $0.name == householdQueryItem }
        // Exactly one. Two would make "which one wins" a security question,
        // and the answer would differ between whoever wrote the parser and
        // whoever wrote the attack.
        guard matches.count == 1, let id = matches[0].value, isWellFormed(id) else {
            return nil
        }
        return id
    }

    // MARK: - What counts as an id

    /**
     * The two shapes this product mints, and nothing else.
     *
     * iOS: `UUID().uuidString.lowercased()`.
     * Web: `crypto.randomUUID()`, or `h_<ts36><rand36>` where `crypto` is
     * missing — `SyncClient.createHousehold()`'s comment and
     * `src/sync/client.ts:119` respectively. A phone can legitimately be
     * handed an id minted by the PWA, so both are accepted.
     *
     * Strict rather than permissive because the id is interpolated into the
     * request path (`{endpoint}/household/{id}`). The server validates too;
     * that is not a reason for the client to hand it something it never
     * generated.
     */
    public static func isWellFormed(_ id: String) -> Bool {
        isLowercaseUUID(id) || isWebFallbackId(id)
    }

    /// 8-4-4-4-12 lowercase hex. `UUID(uuidString:)` is not used: it accepts
    /// uppercase and several looser spellings, and "what we mint" is narrower
    /// than "what Foundation parses".
    private static func isLowercaseUUID(_ id: String) -> Bool {
        let groups = id.split(separator: "-", omittingEmptySubsequences: false)
        guard groups.count == 5 else { return false }
        let widths = [8, 4, 4, 4, 12]
        for (group, width) in zip(groups, widths) {
            guard group.count == width, group.allSatisfy(isLowercaseHex) else {
                return false
            }
        }
        return true
    }

    /// `h_` then base-36 digits: a timestamp and a random, concatenated with
    /// no separator, so only the alphabet and a sane length can be checked.
    private static func isWebFallbackId(_ id: String) -> Bool {
        guard id.hasPrefix("h_") else { return false }
        let body = id.dropFirst(2)
        guard (1...64).contains(body.count) else { return false }
        return body.allSatisfy { $0.isASCII && ($0.isNumber || ("a"..."z").contains($0)) }
    }

    private static func isLowercaseHex(_ c: Character) -> Bool {
        c.isASCII && (c.isNumber || ("a"..."f").contains(c))
    }
}
