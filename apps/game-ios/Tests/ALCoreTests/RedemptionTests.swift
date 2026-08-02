import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Redeeming a code, from the device's side.                                   */
/*                                                                             */
/* One rule dominates, and it is invariant 11 in its sharpest form: OF THE SIX  */
/* ANSWERS THIS ENDPOINT CAN GIVE, EXACTLY ONE WRITES ANYTHING. A code can turn */
/* the game on; no answer from our server may turn it off. That is what makes   */
/* an outage, a bad deploy, a mistyped table name and a flat network all        */
/* harmless — none of them can re-lock a family who redeemed six months ago.    */
/*                                                                             */
/* The cost is stated plainly in `LicenseState.codeGrantedAt`: a redeemed code  */
/* cannot be revoked on a device that already has it. These tests pin that as   */
/* intended behaviour rather than leaving it as something a future refactor     */
/* might "fix".                                                                 */
/* -------------------------------------------------------------------------- */

private let t0: Int64 = 1_700_000_000_000

/// A code whose checksum is right, so `accept` lets it through to the transport.
private let goodCode = "0123456789A" + String(RedemptionCode.checkSymbol("0123456789A")!)

private actor Recorder {
    private(set) var calls: [(code: String, household: String)] = []
    func record(_ code: String, _ household: String) { calls.append((code, household)) }
    var count: Int { calls.count }
    var last: (code: String, household: String)? { calls.last }
}

private struct ScriptedRedemption: RedemptionTransport {
    let answer: RedemptionAnswer
    let recorder: Recorder

    func redeem(code: String, household: String) async -> RedemptionAnswer {
        await recorder.record(code, household)
        return answer
    }
}

@MainActor
private func makeModel(
    answer: RedemptionAnswer,
    household: String? = "house-1",
    licence: LicenseState = .blank,
    recorder: Recorder = Recorder()
) -> (EntitlementModel, LicenseStore, Recorder, MutableTimeSource) {
    let kv = InMemoryKVStore()
    let persist = LicenseStore(kv)
    persist.save(licence)
    let time = MutableTimeSource(t0)
    let model = EntitlementModel(
        store: StubPurchaseStore(),
        persist: persist,
        time: time,
        redemption: ScriptedRedemption(answer: answer, recorder: recorder),
        household: { household }
    )
    return (model, persist, recorder, time)
}

@Suite("A code unlocks, and nothing else does anything")
@MainActor
struct RedemptionModelTests {

    @Test("a grant is written, persisted, and unlocks immediately")
    func granted() async {
        let (model, persist, _, _) = makeModel(answer: .granted(at: t0 + 5))
        let answer = await model.redeem(goodCode)

        #expect(answer == .granted(at: t0 + 5))
        #expect(model.license.codeGrantedAt == t0 + 5)
        #expect(persist.load().codeGrantedAt == t0 + 5)
        // No resume, no relaunch: the paywall has to go now.
        #expect(model.entitlement == .paid)
    }

    @Test("`already` unlocks too — it is a success, not a refusal")
    func already() async {
        let (model, _, _, _) = makeModel(answer: .already(at: t0 - 90 * dayMs))
        let answer = await model.redeem(goodCode)

        #expect(answer.isGrant)
        #expect(model.license.codeGrantedAt == t0 - 90 * dayMs)
        #expect(model.entitlement == .paid)
    }

    @Test("a second redemption never moves an existing grant")
    func grantIsNotMoved() async {
        let existing = LicenseState(codeGrantedAt: t0 - dayMs)
        let (model, _, _, _) = makeModel(answer: .granted(at: t0 + 999), licence: existing)
        _ = await model.redeem(goodCode)
        #expect(model.license.codeGrantedAt == t0 - dayMs)
    }

    /// The heart of it. Each of these four is a REFUSAL, and a refusal may not
    /// touch anything a family owns.
    @Test(
        "no refusal writes anything",
        arguments: [
            RedemptionAnswer.unknown,
            .exhausted,
            .expired,
            .unreachable,
        ]
    )
    func refusalsAreInert(_ answer: RedemptionAnswer) async {
        let before = LicenseState(
            paid: false, verifiedAt: nil, trialStartedAt: t0 - dayMs, clockHighWater: t0)
        let (model, persist, _, _) = makeModel(answer: answer, licence: before)

        let got = await model.redeem(goodCode)
        #expect(got == answer)
        #expect(model.license == before)
        #expect(persist.load() == before)
        #expect(model.license.codeGrantedAt == nil)
    }

    /// The other half: a family already unlocked stays unlocked whatever the
    /// server says next. This is the trade `codeGrantedAt` documents.
    @Test("a refusal cannot re-lock a family that already redeemed")
    func refusalCannotRevoke() async {
        let unlocked = LicenseState(codeGrantedAt: t0 - 180 * dayMs)
        let (model, _, _, _) = makeModel(answer: .unknown, licence: unlocked)
        _ = await model.redeem(goodCode)
        #expect(model.entitlement == .paid)
        #expect(model.license.codeGrantedAt == t0 - 180 * dayMs)
    }

    @Test("a malformed code never leaves the device")
    func checksumGate() async {
        let (model, _, recorder, _) = makeModel(answer: .granted(at: t0))
        // Right length and alphabet, wrong check symbol.
        let bad = "0123456789AA" == goodCode ? "0123456789AB" : "0123456789AA"
        let answer = await model.redeem(bad)

        #expect(answer == .unknown)
        #expect(await recorder.count == 0)
        #expect(model.license.codeGrantedAt == nil)
    }

    @Test("the normalised code is what goes on the wire")
    func sendsNormalised() async {
        let (model, _, recorder, _) = makeModel(answer: .granted(at: t0))
        _ = await model.redeem(RedemptionCode.format(goodCode).lowercased())
        #expect(await recorder.last?.code == goodCode)
        #expect(await recorder.last?.household == "house-1")
    }

    @Test("no household means nothing is sent, and nothing is learnt")
    func noHousehold() async {
        let (model, _, recorder, _) = makeModel(answer: .granted(at: t0), household: nil)
        let answer = await model.redeem(goodCode)
        #expect(answer == .unreachable)
        #expect(await recorder.count == 0)
        #expect(model.license.codeGrantedAt == nil)
    }

    /// A server clock in the future is recorded as the grant date — it is their
    /// grant — but `withClock` still runs on OUR clock, so it cannot advance the
    /// high-water mark and shorten somebody's trial.
    @Test("a skewed server clock cannot shorten a trial")
    func serverClockIsNotOurs() async {
        let year: Int64 = 365 * dayMs
        let (model, _, _, _) = makeModel(answer: .granted(at: t0 + year))
        _ = await model.redeem(goodCode)
        #expect(model.license.codeGrantedAt == t0 + year)
        #expect(model.license.clockHighWater == t0)
    }
}

@Suite("The entitlement rule for a redeemed code")
struct RedeemedEntitlementTests {

    @Test("a grant is paid, with no store answer at all")
    func grantIsPaid() {
        let state = LicenseState(codeGrantedAt: t0)
        #expect(entitlementOf(state, t0) == .paid)
        // And a year later, with nothing having been re-verified.
        #expect(entitlementOf(state, t0 + 365 * dayMs) == .paid)
    }

    @Test("it outranks an expired trial")
    func beatsExpiry() {
        let expired = LicenseState(
            paid: false, verifiedAt: nil, trialStartedAt: t0 - 30 * dayMs, clockHighWater: 0)
        #expect(entitlementOf(expired, t0) == .expired)

        var redeemed = expired
        redeemed.codeGrantedAt = t0
        #expect(entitlementOf(redeemed, t0) == .paid)
        #expect(canPlay(entitlementOf(redeemed, t0)))
    }

    @Test("it needs no verifiedAt and has no grace to run out")
    func noGraceWindow() {
        // A paid licence goes stale past the offline grace and falls through to
        // the trial clock. A code grant does not — there is nothing to re-verify.
        let paidStale = LicenseState(
            paid: true, verifiedAt: t0, trialStartedAt: t0 - 30 * dayMs, clockHighWater: 0)
        #expect(entitlementOf(paidStale, t0 + offlineGraceMs + dayMs) == .expired)

        let redeemed = LicenseState(codeGrantedAt: t0)
        #expect(entitlementOf(redeemed, t0 + offlineGraceMs + 999 * dayMs) == .paid)
    }

    @Test("a licence with no grant is exactly what it was")
    func absentGrantChangesNothing() {
        let trial = LicenseState(trialStartedAt: t0)
        #expect(entitlementOf(trial, t0) == .trial(daysLeft: trialDays, endsAt: t0 + trialMs))
    }
}

@Suite("The grant survives a round trip through storage")
struct RedemptionPersistenceTests {

    @Test("it encodes, decodes, and does not disturb the other fields")
    func roundTrip() throws {
        let state = LicenseState(
            paid: true, verifiedAt: t0, trialStartedAt: t0 - dayMs, clockHighWater: t0,
            codeGrantedAt: t0 + 1)
        let data = try JSONEncoder().encode(state)
        #expect(try JSONDecoder().decode(LicenseState.self, from: data) == state)
    }

    /// The migration, which is that there is none: a licence written before
    /// codes existed decodes with a nil grant and behaves identically.
    @Test("a licence from before codes existed still reads")
    func oldBlobDecodes() throws {
        let old = #"{"paid":false,"verifiedAt":null,"trialStartedAt":1700000000000,"clockHighWater":0}"#
        let decoded = try JSONDecoder().decode(LicenseState.self, from: Data(old.utf8))
        #expect(decoded.codeGrantedAt == nil)
        #expect(decoded.trialStartedAt == t0)
    }

    /// And the other direction: a licence with no grant emits no key, so the
    /// blob a PWA install reads is byte-for-byte what it was.
    @Test("no grant means no key on the wire")
    func absentGrantIsAbsent() throws {
        let data = try JSONEncoder().encode(LicenseState.blank)
        let json = String(decoding: data, as: UTF8.self)
        #expect(!json.contains("codeGrantedAt"))
    }

    @Test("an unknown key does not break the decode")
    func unknownKeys() throws {
        let future = #"{"paid":false,"clockHighWater":0,"codeGrantedAt":123,"somethingNew":"x"}"#
        let decoded = try JSONDecoder().decode(LicenseState.self, from: Data(future.utf8))
        #expect(decoded.codeGrantedAt == 123)
    }
}
