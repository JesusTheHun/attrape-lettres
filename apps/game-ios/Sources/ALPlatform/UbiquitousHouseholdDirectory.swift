import Foundation

import ALCore

/* -------------------------------------------------------------------------- */
/* `HouseholdDirectory` over iCloud key-value store.                            */
/*                                                                             */
/* One Apple ID, every device signed into it: a parent's phone and the family   */
/* iPad pair with no screen, no QR and no interaction at all. Two parents are   */
/* two Apple IDs and this does nothing for them — Apple exposes no family       */
/* identifier, on purpose, so the scanned link stays the only way to cross      */
/* that line.                                                                  */
/*                                                                             */
/* ── Why KVS and not CloudKit ──────────────────────────────────────────────── */
/* This carries one short string. KVS is 1 MB and needs no schema, no record    */
/* types, no container migration and no error handling worth the name — it is   */
/* a plist that syncs. CloudKit would buy queries and sharing we do not want    */
/* here, at the price of a second backing store beside the API we already run.  */
/*                                                                             */
/* ── Every failure is silence, and that is correct ─────────────────────────── */
/* No iCloud account, iCloud Drive switched off, a device that has never been   */
/* online: KVS keeps working as a LOCAL plist and simply never syncs. Reads     */
/* return this device's own last write, `reconcile` compares a claim with       */
/* itself, and nothing happens. There is no error to surface and nothing for a  */
/* parent to fix — invariant 11's philosophy: the family keeps playing, and     */
/* pairing falls back to the QR they can always reach.                          */
/*                                                                             */
/* THE 1 MB QUOTA CANNOT BE HIT HERE. Three keys, a UUID, an integer and a      */
/* device id — under 128 bytes for the lifetime of the app.                     */
/* -------------------------------------------------------------------------- */

public final class UbiquitousHouseholdDirectory: HouseholdDirectory {
    /// Deliberately the same names `SyncClient` uses in local `KVStore`. They
    /// live in different stores, and one name for one fact is worth more than
    /// the chance of confusing two namespaces that never meet.
    static let idKey = SyncClient.householdKey
    static let revAtKey = SyncClient.householdRevAtKey
    static let revByKey = SyncClient.householdRevByKey

    private let store: NSUbiquitousKeyValueStore
    private var observer: NSObjectProtocol?
    private var onChange: ((HouseholdClaim?) -> Void)?

    public init(store: NSUbiquitousKeyValueStore = .default) {
        self.store = store
    }

    deinit {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - HouseholdDirectory

    public func read() -> HouseholdClaim? {
        guard let id = store.string(forKey: Self.idKey), !id.isEmpty else { return nil }
        // Validated on the way IN as well as the way out. iCloud is a shared
        // mutable store; another build of this app — an older one, a beta, a
        // future one — could have written something this version will not
        // accept, and joining a household we cannot parse is worse than
        // ignoring it.
        guard PairingLink.isWellFormed(id) else { return nil }
        return HouseholdClaim(
            id: id,
            rev: Rev(at: store.longLong(forKey: Self.revAtKey), by: store.string(forKey: Self.revByKey) ?? "")
        )
    }

    public func write(_ claim: HouseholdClaim) {
        store.set(claim.id, forKey: Self.idKey)
        store.set(claim.rev.at, forKey: Self.revAtKey)
        store.set(claim.rev.by, forKey: Self.revByKey)
        // Hints that now is a good moment; iCloud decides. Its return value is
        // "did it write to disk", not "did it reach the other device", so
        // there is nothing here worth branching on.
        store.synchronize()
    }

    public func observe(_ onChange: @escaping (HouseholdClaim?) -> Void) {
        self.onChange = onChange
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        observer = NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: store,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            self.onChange?(self.read())
        }
        // The store is populated lazily; without this a cold launch would not
        // see what another device published while this one was shut.
        store.synchronize()
    }
}
