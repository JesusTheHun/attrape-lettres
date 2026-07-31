import ALCore
import Testing

@testable import ALPlatform

// Haptics: the PWA has NONE, so the port ships none.
//
// "No haptics" is the awkward thing to test — an absent buzz on a Mac with no
// Taptic Engine is indistinguishable from a buzz nobody can feel. Hence the
// event log: `.off` records nothing because it emits nothing, and `.system`
// records what it would fire, so the seam is proved to exist AND proved not to
// be wired.

@Suite("Haptics — none ship")
struct AudioHapticsTests {

    @Test("the shipping configuration emits nothing, on any platform")
    func shippingIsSilent() {
        let haptics = PlatformHaptics.shipping
        haptics.light()
        haptics.soft()
        haptics.light()
        haptics.prepare()
        #expect(haptics.events.isEmpty)
    }

    @Test("the default mode is off — a new call site cannot accidentally buzz")
    func defaultModeIsOff() {
        let haptics = PlatformHaptics()
        haptics.light()
        haptics.soft()
        #expect(haptics.events.isEmpty)
    }

    @Test("the system mapping exists behind the flag, ready for a product decision")
    func systemModeIsWiredButNotShipped() {
        let haptics = PlatformHaptics(mode: .system)
        haptics.prepare()
        haptics.light()  // an accepted tap
        haptics.soft()   // a WRONG tap — soft, never .error (invariant 3)
        #expect(haptics.events == [.light, .soft])
    }

    @Test("it is injectable as ALCore's Haptics, and still emits nothing through the protocol")
    func injectableThroughTheProtocol() {
        // The exercises only ever see `ALCore.Haptics`. Going through the
        // existential is what a call site does, and it must not re-enable
        // anything the concrete type turned off.
        let shipping: Haptics = PlatformHaptics.shipping
        shipping.light()
        shipping.soft()
        #expect(PlatformHaptics.shipping.events.isEmpty)

        // ALCore's own default must be interchangeable with it.
        let fallback: Haptics = NoopHaptics()
        fallback.light()
        fallback.soft()

        let both: [Haptics] = [shipping, fallback]
        #expect(both.count == 2)
    }
}
