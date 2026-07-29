import ALCore
import Foundation

#if canImport(UIKit)
    import UIKit
#elseif canImport(AppKit)
    import AppKit
#endif

/* -------------------------------------------------------------------------- */
/* Reduced motion — ONE source of truth (D14, invariant 6).                     */
/*                                                                             */
/* The mascot, the confetti and the sheen all honour it. Three independent      */
/* `@Environment(\.accessibilityReduceMotion)` reads would be three chances to  */
/* forget one, and neither the tests nor the render harness could force it on.  */
/* So: this reads the system once, keeps listening, and everything downstream   */
/* reads the injected `ReduceMotionSource`.                                    */
/* -------------------------------------------------------------------------- */

/**
 * `ReduceMotionSource` over the platform accessibility setting.
 *
 * iOS: `UIAccessibility.isReduceMotionEnabled` plus
 * `reduceMotionStatusDidChangeNotification`.
 * macOS: `NSWorkspace.shared.accessibilityDisplayShouldReduceMotion` plus
 * `NSWorkspace.accessibilityDisplayOptionsDidChangeNotification` — which is
 * posted on `NSWorkspace.shared.notificationCenter`, NOT on `.default`; that is
 * why the centre is a parameter and not hard-coded.
 *
 * The value is CACHED, not read through on every access. Two reasons: the
 * setting is read on the render path where a hop to the main actor is not
 * available, and `ReduceMotionSource` is `Sendable` with a non-isolated getter.
 * A lock around a `Bool` is the whole cost.
 */
public final class SystemReduceMotion: ReduceMotionSource, @unchecked Sendable {
    private let lock = NSLock()
    private var value: Bool
    private let read: @Sendable () -> Bool
    private let center: NotificationCenter
    private var observer: NSObjectProtocol?

    /**
     * - Parameters:
     *   - center: where the change notification arrives. Defaults to the right
     *     one for the platform.
     *   - notification: the change notification's name.
     *   - read: how to sample the system. Injectable so a host test can prove
     *     the observation actually re-samples — a test that could only read the
     *     real setting could never change it, and would prove nothing.
     */
    public init(
        center: NotificationCenter = SystemReduceMotion.systemCenter,
        notification: Notification.Name? = SystemReduceMotion.systemNotification,
        read: @escaping @Sendable () -> Bool = SystemReduceMotion.readSystem
    ) {
        self.center = center
        self.read = read
        self.value = read()
        if let notification {
            self.observer = center.addObserver(
                forName: notification, object: nil, queue: nil
            ) { [weak self] _ in
                self?.refresh()
            }
        }
    }

    deinit {
        if let observer { center.removeObserver(observer) }
    }

    public var isReduced: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    /// Re-sample now. Called by the change notification; also the hook for a
    /// scene-activation re-check, since the setting can change while backgrounded.
    public func refresh() {
        let fresh = read()
        lock.lock()
        value = fresh
        lock.unlock()
    }

    // MARK: - Platform wiring

    /// The current system setting. The ONLY place the platform API is named.
    public static let readSystem: @Sendable () -> Bool = {
        #if canImport(UIKit)
            return UIAccessibility.isReduceMotionEnabled
        #elseif canImport(AppKit)
            return NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        #else
            return false
        #endif
    }

    public static var systemCenter: NotificationCenter {
        #if canImport(UIKit)
            return .default
        #elseif canImport(AppKit)
            return NSWorkspace.shared.notificationCenter
        #else
            return .default
        #endif
    }

    public static var systemNotification: Notification.Name? {
        #if canImport(UIKit)
            return UIAccessibility.reduceMotionStatusDidChangeNotification
        #elseif canImport(AppKit)
            return NSWorkspace.accessibilityDisplayOptionsDidChangeNotification
        #else
            return nil
        #endif
    }
}
