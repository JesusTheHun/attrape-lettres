import ALArt
import ALCore
import SwiftUI

/// The app's entry view. Replaced by the hub once the shell package lands; for
/// now it renders through the SVG path runtime so the simulator build proves the
/// whole chain end to end.
public struct RootView: View {
    public init() {}

    public var body: some View {
        VStack(spacing: 24) {
            SVGShape(
                "M 50 12 L 61 38 L 89 38 L 66 55 L 75 82 L 50 66 L 25 82 L 34 55 L 11 38 L 39 38 Z",
                viewBox: CGRect(x: 0, y: 0, width: 100, height: 100)
            )
            .fill(.yellow)
            .frame(width: 120, height: 120)

            Text("Attrape-Lettres")
                .font(.largeTitle.bold())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 0.29, green: 0.56, blue: 0.89))
        .accessibilityIdentifier("root")
    }
}
