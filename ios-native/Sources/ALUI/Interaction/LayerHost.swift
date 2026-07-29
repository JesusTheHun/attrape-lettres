import QuartzCore
import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

// The hosted CALayer that `Anim` drives, exposed to SwiftUI.
//
// Invariant 2 needs a real layer to animate — `Anim.press`/`shake`/`pop`/
// `pulse` are Core Animation, and SwiftUI views do not expose one. `LayerHost`
// wraps its SwiftUI content in a UIKit container and hands the container's
// layer out through a `LayerHandle` the parent holds, so a `touchDown` handler
// can say `Anim.press(handle.layer, …)` synchronously, off the render path.
// The layer's transform applies to the whole subtree, so the hosted content
// squishes/shakes exactly as the web's `el.animate` moves the element and
// everything inside it.
//
// The content is hosted through a `UIHostingController` added without a parent
// view controller. D5 rejected that plumbing for the TOUCH primitive (a
// recogniser needs none of it); for the layer there is no alternative — the
// animated layer must be an ANCESTOR of the content's render surface, and
// hosting is the only supported way to put SwiftUI content under a layer we
// own. Environment values still flow in through the representable's rootView.
//
// macOS (D1): a no-op host — the content renders unchanged, `handle.layer`
// stays nil, and every `Anim` entry point drops the animation but still runs
// its completion. The macOS build exists to run the host test suite, not to
// ship; the animations themselves are asserted as data in `AnimTests`.

/// The parent's grip on the hosted layer. Create one per animated element
/// (`@State private var handle = LayerHandle()`), pass it to `LayerHost`, and
/// hand `handle.layer` to `Anim`. The reference is weak: when SwiftUI tears the
/// view down the layer goes with it and the handle reads nil, which `Anim`
/// treats as "nothing to animate".
@MainActor
public final class LayerHandle {
    public internal(set) weak var layer: CALayer?

    public init() {}
}

/// Wraps `content` so its backing `CALayer` is reachable through `handle`.
public struct LayerHost<Content: View>: View {
    private let handle: LayerHandle
    private let content: Content

    public init(handle: LayerHandle, @ViewBuilder content: () -> Content) {
        self.handle = handle
        self.content = content()
    }

    public var body: some View {
        #if canImport(UIKit)
        HostRepresentable(handle: handle, content: content)
        #else
        content
        #endif
    }
}

#if canImport(UIKit)

private struct HostRepresentable<Content: View>: UIViewRepresentable {
    let handle: LayerHandle
    let content: Content

    func makeUIView(context: Context) -> HostContainerView<Content> {
        let view = HostContainerView(rootView: content)
        handle.layer = view.layer
        return view
    }

    func updateUIView(_ uiView: HostContainerView<Content>, context: Context) {
        uiView.hosting.rootView = content
        handle.layer = uiView.layer
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: HostContainerView<Content>,
        context: Context
    ) -> CGSize? {
        // The host is invisible chrome: it sizes exactly to its SwiftUI
        // content, so wrapping a view in a LayerHost never changes layout.
        uiView.hosting.sizeThatFits(
            in: CGSize(
                width: proposal.width ?? UIView.layoutFittingExpandedSize.width,
                height: proposal.height ?? UIView.layoutFittingExpandedSize.height
            )
        )
    }
}

final class HostContainerView<Content: View>: UIView {
    let hosting: UIHostingController<Content>

    init(rootView: Content) {
        hosting = UIHostingController(rootView: rootView)
        super.init(frame: .zero)

        hosting.view.backgroundColor = .clear
        // The container is not a screen edge; the PWA's safe-area handling
        // lives on the screen roots, not on tiles.
        hosting.safeAreaRegions = []

        addSubview(hosting.view)
        hosting.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            hosting.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            hosting.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            hosting.view.topAnchor.constraint(equalTo: topAnchor),
            hosting.view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("HostContainerView is code-only")
    }
}

#endif
