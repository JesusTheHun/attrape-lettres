import QuartzCore
import SwiftUI
import Testing

@testable import ALUI

// The layer handle contract `Anim` and the screens rely on: the handle grips
// the hosted layer weakly, so a torn-down view (SwiftUI `.id()` remount, route
// change) leaves a nil handle — which `Anim` treats as "nothing to animate,
// still complete". A strong reference here would keep dead layers alive across
// every exercise remount.

@Suite("LayerHost — the handle")
@MainActor
struct LayerHostTests {

    @Test("the handle holds the layer weakly and reads nil once the layer is gone")
    func handleIsWeak() {
        let handle = LayerHandle()
        #expect(handle.layer == nil, Comment(rawValue: "a fresh handle grips nothing"))

        var layer: CALayer? = CALayer()
        handle.layer = layer
        #expect(handle.layer === layer)

        layer = nil
        #expect(handle.layer == nil, Comment(rawValue: "weak: the view teardown must release the layer"))
    }

    #if canImport(UIKit)
    @Test("the container exposes ITS layer and hosts the content edge-to-edge, transparent")
    func containerHostsContent() {
        let container = HostContainerView(rootView: Text(verbatim: "x"))
        #expect(container.hosting.view.superview === container)
        #expect(container.hosting.view.backgroundColor == .clear)
        // The animated layer must be an ancestor of the content's render
        // surface, or Anim.press would squish an empty container.
        #expect(container.hosting.view.layer.superlayer === container.layer)
    }
    #endif
}
