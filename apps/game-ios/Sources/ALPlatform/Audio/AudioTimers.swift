import Foundation

// `window.setTimeout` / `clearTimeout`, on the main queue.
//
// The watchdogs are the only thing standing between a stalled engine and a
// round that never advances, so the scheduler is a seam: the live one below,
// and a fake in the tests that fires deadlines on demand. A test that waits out
// an 8-second provisional watchdog is a test nobody runs.

@MainActor
public final class MainQueueAudioTimers: AudioTimerScheduling {

    public nonisolated init() {}

    public func schedule(afterMs: Double, _ body: @escaping () -> Void) -> AudioTimer {
        let timer = WorkItemTimer()
        let item = DispatchWorkItem {
            guard !timer.isCancelled else { return }
            MainActor.assumeIsolated { body() }
        }
        timer.item = item
        DispatchQueue.main.asyncAfter(deadline: .now() + afterMs / 1000, execute: item)
        return timer
    }
}

final class WorkItemTimer: AudioTimer, @unchecked Sendable {
    var item: DispatchWorkItem?
    private(set) var isCancelled = false

    func cancel() {
        isCancelled = true
        item?.cancel()
        item = nil
    }
}
