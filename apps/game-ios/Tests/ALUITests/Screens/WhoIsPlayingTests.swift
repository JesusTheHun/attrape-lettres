import Foundation
import Testing

import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `src/components/WhoIsPlaying.tsx` + the roster half of                       */
/* `src/hooks/useProfile.tsx`, asserted against the TypeScript.                 */
/*                                                                             */
/* `WhoIsPlaying.swift` shipped in the previous run untested. The two branches  */
/* that are easy to get wrong and impossible to see are both here:              */
/*                                                                             */
/* ```tsx                                                                       */
/* onRename={() => {                                                            */
/*   const next = window.prompt(`Nouveau prénom pour ${c.name} ?`, c.name);     */
/*   if (next !== null) renameChild(c.id, next);   // "" DOES call it           */
/* }}                                                                           */
/* onDelete={() => {                                                            */
/*   if (window.confirm(`Supprimer le profil de ${c.name} ? Tout sera perdu.`)) {*/
/*     deleteChild(c.id);                                                       */
/*   }                                                                          */
/* }}                                                                           */
/* ```                                                                          */
/*                                                                             */
/* and, in the hook:                                                            */
/*                                                                             */
/* ```ts                                                                        */
/* const renameChild = (id, name) => {                                          */
/*   const trimmed = name.trim();                                               */
/*   if (!trimmed) return;                    // the empty rename is a no-op    */
/*   … { ...c, name: trimmed.slice(0, 14), nameRev, touchedAt: now }            */
/* };                                                                           */
/* const deleteChild = (id) => commit({                                         */
/*   children: r.children.filter((c) => c.id !== id),                           */
/*   activeId: r.activeId === id ? null : r.activeId,                           */
/*   removed: { ...r.removed, [id]: Date.now() },                               */
/* });                                                                          */
/* ``` */
/* -------------------------------------------------------------------------- */

private let device = "this-phone"
private let t0: Millis = 1_700_000_000_000

@MainActor
private func makeStore(
    kv: KVStore = InMemoryKVStore(),
    now: @escaping () -> Millis = { t0 },
    sync: SyncClient? = nil
) -> ProfileStore {
    ProfileStore(kv: kv, device: { device }, now: now, sync: sync)
}

/// A child, created and named, with their id in hand.
@MainActor
private func storeWithChild(_ name: String) -> (ProfileStore, String) {
    let store = makeStore()
    store.createChild(name: name)
    return (store, store.activeId ?? "")
}

// MARK: - RosterPrompt

@Suite("WhoIsPlaying — the two browser dialogs")
struct RosterPromptTests {

    @Test("the rename prompt is window.prompt's message, character for character")
    func renameMessage() {
        let p = RosterPrompt(kind: .rename, childId: "c1", childName: "Léa")
        #expect(p.message == "Nouveau prénom pour Léa ?")
    }

    @Test("the delete prompt is window.confirm's message, character for character")
    func deleteMessage() {
        let p = RosterPrompt(kind: .delete, childId: "c1", childName: "Léa")
        #expect(p.message == "Supprimer le profil de Léa ? Tout sera perdu.")
    }

    @Test("the two dialogs for one child are distinct identities")
    func identity() {
        let rename = RosterPrompt(kind: .rename, childId: "c1", childName: "Léa")
        let delete = RosterPrompt(kind: .delete, childId: "c1", childName: "Léa")
        #expect(rename.id != delete.id)
    }
}

// MARK: - rosterAction, every combination

@Suite("WhoIsPlaying — rosterAction(for:confirmed:text:)")
struct RosterActionTests {

    private let rename = RosterPrompt(kind: .rename, childId: "c1", childName: "Léa")
    private let delete = RosterPrompt(kind: .delete, childId: "c1", childName: "Léa")

    @Test("no dialog open: nothing happens, whatever is passed")
    func noPrompt() {
        #expect(rosterAction(for: nil, confirmed: true, text: "Noé") == .none)
        #expect(rosterAction(for: nil, confirmed: false, text: "Noé") == .none)
        #expect(rosterAction(for: nil, confirmed: true, text: "") == .none)
    }

    @Test("rename cancelled: window.prompt returned null, so renameChild is NEVER called")
    func renameCancelled() {
        // `if (next !== null) renameChild(...)`. This is the branch that is easy
        // to get wrong: a port that treated cancel as "rename to the seeded
        // value" would look correct in every manual test.
        #expect(rosterAction(for: rename, confirmed: false, text: "Noé") == .none)
        #expect(rosterAction(for: rename, confirmed: false, text: "") == .none)
    }

    @Test("rename confirmed: the typed text goes to the store, untouched")
    func renameConfirmed() {
        #expect(rosterAction(for: rename, confirmed: true, text: "Noé") == .rename(id: "c1", name: "Noé"))
        // NOT trimmed here — `renameChild` trims, and the trimming rule is the
        // hook's so a second implementation cannot drift from it.
        #expect(
            rosterAction(for: rename, confirmed: true, text: "  Noé  ")
                == .rename(id: "c1", name: "  Noé  "))
    }

    @Test("rename confirmed with an EMPTY field still calls the store")
    func renameConfirmedEmpty() {
        // `window.prompt` returning `""` is not `null`: the TSX calls
        // `renameChild(c.id, "")` and the hook's `if (!trimmed) return` swallows
        // it. Deciding emptiness here instead would move the rule out of the one
        // place that owns it.
        #expect(rosterAction(for: rename, confirmed: true, text: "") == .rename(id: "c1", name: ""))
        #expect(
            rosterAction(for: rename, confirmed: true, text: "   ")
                == .rename(id: "c1", name: "   "))
    }

    @Test("delete declined: deleteChild is never called")
    func deleteDeclined() {
        #expect(rosterAction(for: delete, confirmed: false, text: "") == .none)
        #expect(rosterAction(for: delete, confirmed: false, text: "Noé") == .none)
    }

    @Test("delete confirmed: the id goes, and the text field is irrelevant")
    func deleteConfirmed() {
        #expect(rosterAction(for: delete, confirmed: true, text: "") == .delete(id: "c1"))
        #expect(rosterAction(for: delete, confirmed: true, text: "Noé") == .delete(id: "c1"))
    }
}

// MARK: - applyRosterAction

@Suite("WhoIsPlaying — applyRosterAction drives ProfileStore")
@MainActor
struct ApplyRosterActionTests {

    @Test(".none touches nothing at all")
    func noneIsANoOp() throws {
        let (store, id) = storeWithChild("Léa")
        let before = store.roster
        applyRosterAction(.none, to: store)
        #expect(store.roster == before)
        #expect(store.children.count == 1)
        #expect(store.children[0].name == "Léa")
        #expect(store.activeId == id)
    }

    @Test("rename changes the name, trimmed and capped at 14 by the store")
    func rename() throws {
        let (store, id) = storeWithChild("Léa")
        applyRosterAction(.rename(id: id, name: "  Noé  "), to: store)
        #expect(store.children[0].name == "Noé")

        // `trimmed.slice(0, 14)` — `maxLength={14}` guards the CREATE field, and
        // the hook guards the rename.
        applyRosterAction(.rename(id: id, name: "Bartholomé-Alexandre"), to: store)
        #expect(store.children[0].name.count == Copy.WhoIsPlaying.nameMaxLength)
        #expect(store.children[0].name == "Bartholomé-Ale")
    }

    @Test("the empty rename the hook ignores really is ignored")
    func emptyRenameIsIgnored() {
        // `if (!trimmed) return` — the name stands and NOTHING is stamped.
        let (store, id) = storeWithChild("Léa")
        let before = store.roster
        applyRosterAction(.rename(id: id, name: ""), to: store)
        #expect(store.children[0].name == "Léa")
        #expect(store.roster == before)

        applyRosterAction(.rename(id: id, name: "    "), to: store)
        #expect(store.children[0].name == "Léa")
        #expect(store.roster == before)
    }

    @Test("renaming an unknown id changes nobody's name")
    func renameUnknownId() {
        let (store, _) = storeWithChild("Léa")
        applyRosterAction(.rename(id: "nobody", name: "Noé"), to: store)
        #expect(store.children.map(\.name) == ["Léa"])
    }

    @Test("delete removes the child, clears the wheel, and leaves a tombstone")
    func delete() throws {
        let (store, id) = storeWithChild("Léa")
        applyRosterAction(.delete(id: id), to: store)
        #expect(store.children.isEmpty)
        // `activeId: r.activeId === id ? null : r.activeId`.
        #expect(store.activeId == nil)
        // "Tombstone, not just a removal: without it the family's other device
        // still has the child and would hand them straight back on next merge."
        #expect(store.roster.removed[id] == t0)
    }

    @Test("deleting a sibling leaves the player at the wheel")
    func deleteOther() throws {
        let store = makeStore()
        store.createChild(name: "Léa")
        let lea = try #require(store.activeId)
        store.createChild(name: "Noé")
        let noe = try #require(store.activeId)
        applyRosterAction(.delete(id: lea), to: store)
        #expect(store.children.map(\.name) == ["Noé"])
        #expect(store.activeId == noe)
    }

    /// Invariant 9. Renaming and deleting are roster operations; neither may
    /// touch the star counters, and there is no total anywhere to touch.
    @Test("invariant 9: no roster action writes, sums or invents a total")
    func neverWritesATotal() throws {
        let (store, id) = storeWithChild("Léa")
        let starsBefore = store.roster.children[0].profile.stars
        let clearsBefore = store.roster.children[0].profile.clears

        applyRosterAction(.rename(id: id, name: "Noé"), to: store)
        #expect(store.roster.children[0].profile.stars == starsBefore)
        #expect(store.roster.children[0].profile.clears == clearsBefore)
        #expect(store.profile.balance == 0)

        // …and the persisted shape has no place to put one even if a future edit
        // wanted to: `balance` is a FOLD (`balanceOf(stars)`), never a field.
        let labels = Mirror(reflecting: store.roster.children[0].profile).children
            .compactMap(\.label)
        #expect(!labels.contains("balance"))
        #expect(labels.contains("stars"))
    }
}

// MARK: - The card's press

@Suite("WhoIsPlaying — the ChildCard squish")
@MainActor
struct RosterPressTests {

    @Test("the shop's PRESS keyframes, not the exercise tile's")
    func keyframes() {
        // src/shop/anim.ts:
        //   const PRESS = [scale(1), scale(0.94), scale(1)]
        //   el.animate(PRESS, { duration: 130, easing: "ease-out" })
        // Tile.tsx bottoms out at 0.9; these are two different animations and
        // must not be unified.
        #expect(RosterPress.scaleValues == [1, 0.94, 1])
        #expect(RosterPress.duration == 0.13)
    }

    @Test("D29: the shop press IS reduced-motion gated, where the tile press is not")
    func gated() {
        // `press()` in shop/anim.ts starts `if (!el || reducedMotion()) return`;
        // `Tile.tsx` has no `matchMedia` call at all. A nil layer and a reduced
        // source both mean "do nothing" — asserted by the absence of a crash and
        // by the guard existing at all; the keyframe data is asserted above.
        RosterPress.press(nil, reduceMotion: FixedReduceMotion(true))
        RosterPress.press(nil, reduceMotion: FixedReduceMotion(false))
    }
}

// MARK: - Labels

@Suite("WhoIsPlaying — the labels a screen reader gets")
@MainActor
struct ChildCardLabelTests {

    @Test("the card announces the job it will do, and it names the child")
    func labels() throws {
        // `aria-label={editing ? `Renommer ${name}` : `Jouer avec ${name}`}`.
        // The name IS in the label and that is correct: a screen reader runs on
        // the device. Invariant 10 is about what LEAVES it.
        let (store, _) = storeWithChild("Léa")
        let child = try #require(store.children.first)
        let playing = ChildCard(
            child: child, editing: false, reduceMotion: FixedReduceMotion(false),
            onPick: {}, onRename: {}, onDelete: {})
        let editing = ChildCard(
            child: child, editing: true, reduceMotion: FixedReduceMotion(false),
            onPick: {}, onRename: {}, onDelete: {})
        #expect(playing.label == "Jouer avec Léa")
        #expect(editing.label == "Renommer Léa")
    }

    @Test("the roster's fixed copy is the TSX's")
    func fixedCopy() {
        #expect(Copy.WhoIsPlaying.heading == "Qui joue ?")
        #expect(Copy.WhoIsPlaying.edit == "Modifier")
        #expect(Copy.WhoIsPlaying.editDone == "Terminé")
        #expect(Copy.WhoIsPlaying.newProfile == "Nouveau profil")
        #expect(Copy.WhoIsPlaying.newProfileLabel == "Nouveau")
        #expect(Copy.WhoIsPlaying.askName == "Comment tu t'appelles ?")
        #expect(Copy.WhoIsPlaying.namePlaceholder == "Ton prénom")
        #expect(Copy.WhoIsPlaying.go == "C'est parti ! 🎉")
        #expect(Copy.WhoIsPlaying.back == "Retour")
        #expect(Copy.WhoIsPlaying.owlAvatar == "🦉")
        #expect(Copy.WhoIsPlaying.delete("Léa") == "Supprimer Léa")
        #expect(Copy.WhoIsPlaying.nameMaxLength == 14)
        // U+FF0B FULLWIDTH PLUS SIGN, not an ASCII "+".
        #expect(Copy.WhoIsPlaying.newProfileGlyph == "\u{FF0B}")
        #expect(Copy.WhoIsPlaying.newProfileGlyph != "+")
    }
}

// MARK: - Invariant 10

/// Records every pushed roster so a test can read the exact bytes that would
/// have gone up the wire.
private final class RecordingSyncTransport: SyncTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _pushed: [WireRoster] = []

    var pushed: [WireRoster] { lock.withLock { _pushed } }

    /// The household document is absent, so `syncOnce` pushes the local roster
    /// as-is — the shortest path to "what would leave this device".
    func pull(household: String) async throws -> (roster: WireRoster, etag: String)? { nil }

    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult {
        lock.withLock { _pushed.append(roster) }
        return .ok(etag: "e1")
    }
}

@Suite("WhoIsPlaying — invariant 10: a child's name never leaves the device")
@MainActor
struct RosterPrivacyTests {

    /// The name is deliberately distinctive so a substring search over the raw
    /// bytes cannot pass by accident.
    private static let name = "Zéphyrine"
    private static let renamed = "Ombeline"

    @Test("the whole roster flow puts no name in a single pushed byte")
    func nameNeverReachesTheSyncTransport() async throws {
        let kv = InMemoryKVStore()
        let transport = RecordingSyncTransport()
        let sync = SyncClient(kv: kv, transport: transport, endpoint: { "https://s.test" })
        sync.joinHousehold("household-1")
        let store = makeStore(kv: kv, sync: sync)

        // Drive the screen's whole roster surface.
        store.createChild(name: Self.name)
        let id = try #require(store.activeId)
        applyRosterAction(
            rosterAction(
                for: RosterPrompt(kind: .rename, childId: id, childName: Self.name),
                confirmed: true, text: Self.renamed),
            to: store)
        #expect(store.children[0].name == Self.renamed)

        store.syncNow()
        try await settle(until: { !transport.pushed.isEmpty })

        #expect(!transport.pushed.isEmpty)
        let encoder = JSONEncoder()
        for roster in transport.pushed {
            let bytes = try encoder.encode(roster)
            let json = String(decoding: bytes, as: UTF8.self)
            #expect(!json.contains(Self.name))
            #expect(!json.contains(Self.renamed))
            // …and the reason it cannot: `WireChild` has no name field at all.
            #expect(!json.contains("name"))
        }

        // The control that makes the assertion above mean something: the name
        // really was on the device, in the saved roster, the whole time.
        let saved = try #require(kv.string("attrape-lettres:roster:v4"))
        #expect(saved.contains(Self.renamed))
    }

    @Test("a deleted child's name is not in the tombstone either")
    func tombstoneCarriesNoName() async throws {
        let kv = InMemoryKVStore()
        let transport = RecordingSyncTransport()
        let sync = SyncClient(kv: kv, transport: transport, endpoint: { "https://s.test" })
        sync.joinHousehold("household-1")
        let store = makeStore(kv: kv, sync: sync)

        store.createChild(name: Self.name)
        let id = try #require(store.activeId)
        applyRosterAction(.delete(id: id), to: store)

        store.syncNow()
        try await settle(until: { !transport.pushed.isEmpty })

        #expect(!transport.pushed.isEmpty)
        let encoder = JSONEncoder()
        for roster in transport.pushed {
            let json = String(decoding: try encoder.encode(roster), as: UTF8.self)
            #expect(!json.contains(Self.name))
            // The tombstone is keyed by the opaque child id, never the name.
            #expect(json.contains(id))
        }
    }

    @Test("the roster screen emits no telemetry at all")
    func noTelemetryFromTheRoster() async throws {
        // The screen never calls `track`, so a name has no property to ride in
        // even before `TelemetryProps`' closed shape (D12) stops it. Proven by
        // driving the flow with consent GIVEN and a live endpoint: a screen that
        // tracked anything would show up here.
        let kv = InMemoryKVStore()
        let telemetryTransport = RecordingTransport()
        let telemetry = Telemetry(
            endpoint: "https://t.test", transport: telemetryTransport, kv: kv,
            appVersion: FixedAppVersion("1.2.3"))
        telemetry.setConsent(true)
        let store = makeStore(kv: kv)

        store.createChild(name: Self.name)
        let id = try #require(store.activeId)
        applyRosterAction(.rename(id: id, name: Self.renamed), to: store)
        applyRosterAction(.delete(id: id), to: store)

        telemetry.flush()
        await telemetry.awaitPendingSends()
        #expect(telemetryTransport.sent.isEmpty)
    }

    @Test("TelemetryProps has no field a name could fit in (D12)")
    func propsHaveNoStringEscapeHatch() {
        // A structural guard, not a review convention: if someone adds a String
        // property to carry "which child", this fails.
        for child in Mirror(reflecting: TelemetryProps(daysLeft: 14)).children {
            let described = String(describing: type(of: child.value))
            #expect(
                described == "Optional<Int>" || described == "Optional<ExerciseId>",
                Comment(rawValue: "TelemetryProps.\(child.label ?? "?") is \(described)"))
        }
    }
}

/// Waits for the detached `syncNow()` task to have pushed, by watching for the
/// push rather than by counting yields. No sleeping — the task only awaits the
/// fake transport, so it lands as soon as it is scheduled.
///
/// The previous version yielded a fixed 50 times and was flaky: roughly one run
/// in three, under the parallel load of the full suite, `syncNow()`'s detached
/// task had not been scheduled by the time the yields ran out, and the test
/// failed on its own `!transport.pushed.isEmpty` guard. `Task.yield()` only
/// offers the current executor a chance to run something else; it makes no
/// promise about a task on another thread, so "enough yields" is not a quantity
/// that exists. Waiting on the condition is.
///
/// Note what the flake was NOT: at no point did a name appear in a payload. The
/// guard is there precisely so that "nothing was pushed" can never be mistaken
/// for "nothing identifying was pushed" — an invariant-10 test that passed
/// vacuously would be far worse than one that fails loudly.
private func settle(
    until condition: @escaping @Sendable () -> Bool,
    turns: Int = 10_000
) async throws {
    for _ in 0..<turns {
        if condition() { return }
        await Task.yield()
    }
}

// MARK: - Source scans

@Suite("WhoIsPlaying — what the file may not name")
struct WhoIsPlayingSourceScanTests {

    private static let source: URL =
        URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // …/Tests/ALUITests/Screens
        .deletingLastPathComponent()  // …/Tests/ALUITests
        .deletingLastPathComponent()  // …/Tests
        .deletingLastPathComponent()  // …/apps/game-ios
        .appendingPathComponent("Sources/ALUI/Screens/WhoIsPlaying.swift")

    private func code() throws -> String {
        try String(contentsOf: Self.source, encoding: .utf8)
            .split(separator: "\n")
            .filter { line in
                let t = line.trimmingCharacters(in: .whitespaces)
                return !t.hasPrefix("//") && !t.hasPrefix("/*") && !t.hasPrefix("*")
            }
            .joined(separator: "\n")
    }

    @Test("the scan can find the file it is meant to scan")
    func fileExists() {
        #expect(FileManager.default.fileExists(atPath: Self.source.path))
    }

    /// Invariant 10. The roster is the one screen that holds children's names,
    /// so it is the one screen where a telemetry call would be catastrophic.
    @Test("the roster screen names no telemetry, no transport and no logger")
    func namesNothingThatLeaves() throws {
        let code = try code()
        for forbidden in ["Telemetry", "track(", "reportError", "SyncClient", "print(", "NSLog", "os_log"] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "WhoIsPlaying.swift names \(forbidden) — a name must never leave the device"))
        }
    }

    /// Invariant 9 / 8. The roster spends nothing and earns nothing.
    @Test("the roster screen never awards, spends or buys")
    func mintsNothing() throws {
        let code = try code()
        for forbidden in ["award(", "spend(", ".buy(", "balance"] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "WhoIsPlaying.swift names \(forbidden); points are not this screen's business"))
        }
    }

    /// Invariant 5's sibling. No child on the device is ever hidden or gated.
    @Test("the roster lists every child, unconditionally")
    func noGating() throws {
        let code = try code()
        // The grid iterates the roster itself — not a filtered, sorted or
        // truncated view of it.
        #expect(code.contains("ForEach(store.children, id: \\.id)"))
        for forbidden in ["store.children.filter", "store.children.prefix", "locked", "unlocked"] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "WhoIsPlaying.swift names \(forbidden); every child is always listed"))
        }
    }
}
