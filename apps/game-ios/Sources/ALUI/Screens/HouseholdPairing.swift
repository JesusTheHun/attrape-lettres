import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* « Les mêmes progrès sur tous les appareils » — the pairing screen.           */
/*                                                                             */
/* NO TSX. The PWA has no pairing screen, so this is the first behaviour the    */
/* two apps do not share; DECISIONS.md records the divergence rather than       */
/* leaving someone to find it by diffing.                                       */
/*                                                                             */
/* Written for the grown-up and deliberately plain, like Onboarding and the     */
/* gate — `stageAdult`, no mascot, no bounce. A screen that looks like the game */
/* invites a six-year-old to press things on it, and this one hands out the     */
/* household credential.                                                        */
/*                                                                             */
/* ── No camera, and that is a decision ─────────────────────────────────────── */
/* An in-app scanner would be nicer, and it would cost an NSCameraUsageDescription*/
/* on an app whose permission set is otherwise empty — one more line to justify */
/* in a Kids Category review, for something a parent does once. The system      */
/* camera already reads QR codes and offers to open the app, so the QR encodes  */
/* a `PairingLink` URL rather than a bare id. The Android manifest test states  */
/* the same principle out loud for the other store: "a permission this app      */
/* cannot use is one more line to justify".                                     */
/*                                                                             */
/* ── What this screen can and cannot promise ───────────────────────────────── */
/* It says the third-device sentence because nothing else can. Re-pairing a     */
/* device leaves any OTHER device on the abandoned household; that device keeps */
/* every star and keeps working, but stops converging, and `SyncClient` cannot  */
/* detect it — the abandoned document is on a server it no longer asks about.   */
/* -------------------------------------------------------------------------- */

// MARK: - The code itself (pure)

/// QR generation, kept out of the view so a test can decode what it produces.
///
/// CoreImage rather than a package: the whole job is one built-in filter, and
/// this app takes no dependency it can avoid.
public enum QRCode {

    /// `correctionLevel` "M" — 15 % recovery. "L" makes a smaller, denser code
    /// and a pairing link is read once, across a kitchen table, off a screen
    /// that might be smudged. The extra modules cost nothing here.
    public static func cgImage(for text: String, scale: CGFloat = 12) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // Nearest-neighbour by construction: CIQRCodeGenerator emits one pixel
        // per module, so scaling up is exact and never blurs an edge a camera
        // has to resolve.
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return CIContext().createCGImage(scaled, from: scaled.extent)
    }
}

// MARK: - The screen

public struct HouseholdPairingView: View {
    /// nil when this build has no sync endpoint. The screen says so rather
    /// than showing a code that would pair a device with nothing.
    private let link: URL?
    /// True right after a link was accepted, so the same screen can confirm
    /// rather than needing a second one.
    private let joined: Bool
    private let onDone: () -> Void

    public init(link: URL?, joined: Bool = false, onDone: @escaping () -> Void) {
        self.link = link
        self.joined = joined
        self.onDone = onDone
    }

    public var body: some View {
            VStack(alignment: .leading, spacing: 18) {
                Text(Copy.Pairing.heading)
                    .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)

                if joined {
                    confirmation
                } else if let link {
                    offer(link)
                } else {
                    Text(Copy.Pairing.unavailable)
                        .font(Typography.rounded(Typography.Size.base))
                        .foregroundStyle(Palette.inkProse.color)
                }

                Button(action: onDone) {
                    Text(Copy.Pairing.done)
                        .font(Typography.rounded(Typography.Size.lg, Typography.Weight.extrabold))
                        .foregroundStyle(Palette.ink.color)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            // The copy is long and a parent may be at a large Dynamic Type
            // setting; this is one of the few screens that genuinely needs to
            // scroll.
            .modifier(PageScroll(enabled: true))
            .stageWash(Palette.stageAdult)
    }

    // MARK: Parts

    @ViewBuilder
    private var confirmation: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(Copy.Pairing.joinedTitle)
                .font(Typography.rounded(Typography.Size.xl, Typography.Weight.extrabold))
                .foregroundStyle(Palette.ink.color)
            prose(Copy.Pairing.joined)
            prose(Copy.Pairing.thirdDevice)
        }
    }

    @ViewBuilder
    private func offer(_ link: URL) -> some View {
        prose(Copy.Pairing.intro)
        prose(Copy.Pairing.privacy)

        Text(Copy.Pairing.showTitle)
            .font(Typography.rounded(Typography.Size.lg, Typography.Weight.extrabold))
            .foregroundStyle(Palette.ink.color)

        code(link)

        prose(Copy.Pairing.scanHint)

        ShareLink(item: link, subject: Text(Copy.Pairing.shareMessage)) {
            Text(Copy.Pairing.share)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.semibold))
                .foregroundStyle(Palette.ink.color)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(Palette.inkFaint.color, lineWidth: 2)
                )
        }

        prose(Copy.Pairing.automatic)
        prose(Copy.Pairing.thirdDevice)
    }

    @ViewBuilder
    private func code(_ link: URL) -> some View {
        if let image = QRCode.cgImage(for: link.absoluteString) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)  // a blurred module is an unreadable code
                .resizable()
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: 260)
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.white)
                )
                .frame(maxWidth: .infinity, alignment: .center)
                .accessibilityLabel(Copy.Pairing.qrLabel)
        }
    }

    private func prose(_ text: String) -> some View {
        Text(text)
            .font(Typography.rounded(Typography.Size.base))
            .foregroundStyle(Palette.inkProse.color)
            .fixedSize(horizontal: false, vertical: true)
    }
}
