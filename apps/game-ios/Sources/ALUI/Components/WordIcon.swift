import SwiftUI

import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/WordIcon.tsx` — the picture for a word.                     */
/*                                                                             */
/* ```tsx                                                                      */
/* export function WordIcon({ emoji, img, size, alt = "" }) {                  */
/*   if (img)                                                                  */
/*     return <img src={img} alt={alt} draggable={false} className="select-none"*/
/*                 style={{ width: size, height: size,                          */
/*                          objectFit: "contain", display: "block" }} />;      */
/*   return <span aria-hidden style={{ fontSize: size, lineHeight: 1.1,        */
/*                                     display: "block" }}>{emoji}</span>;     */
/* }                                                                           */
/* ```                                                                         */
/*                                                                             */
/* « We reach for `img` only where the closest emoji misrepresents the word (a  */
/* cupcake for « macaron », a hut for « igloo »…); `emoji` stays as the text    */
/* fallback so nothing ever renders blank. »                                    */
/*                                                                             */
/* The four drawings are ALREADY DRAWN, in `ALArt/Images/WordImages.swift`, off */
/* the same `d` strings as the SVG files (D2). Nothing in this file authors     */
/* geometry: it is the SwiftUI wrapper and nothing else.                        */
/*                                                                             */
/* NO `switch` OVER `ImageKey` LIVES HERE, deliberately. `WordImages.draw` is   */
/* the single exhaustive one — `default:`-less, so a fifth key is a compile     */
/* error there rather than a picture that silently renders nothing. Adding a    */
/* second table here (« a dictionary lets a new key compile and render          */
/* nothing ») would reintroduce exactly the failure that design prevents.       */
/*                                                                             */
/* `size` in the TSX is a CSS length — every call site passes a `clamp(…)`.     */
/* Resolve it with `fluid(min:vw:max:viewport:)` and hand over the point value; */
/* the box stays square, so `object-fit: contain` on a square 128 viewBox is    */
/* the identity and needs no aspect handling.                                   */
/* -------------------------------------------------------------------------- */

public struct WordIcon: View {

    /// `lineHeight: 1.1` on the emoji span.
    public static let emojiLineHeight: CGFloat = 1.1

    /// The emoji fallback — always present, even when `img` wins.
    public let emoji: String
    /// The dedicated illustration, when the word has one.
    public let img: ImageKey?
    /// The box side, in points (the caller resolves the CSS `clamp`).
    public let size: CGFloat
    /// `alt` on the `<img>`. Empty (the default) means decorative: « every call
    /// site already labels the word in text or an aria-label on the surrounding
    /// tile/button ».
    public let alt: String

    public init(emoji: String, img: ImageKey? = nil, size: CGFloat, alt: String = "") {
        self.emoji = emoji
        self.img = img
        self.size = size
        self.alt = alt
    }

    public var body: some View {
        if let img {
            drawing(img)
        } else {
            // `<span aria-hidden>` — the emoji branch ignores `alt` entirely,
            // exactly as the TSX does.
            Text(verbatim: emoji)
                .font(.system(size: size))
                .lineSpacing(Typography.lineSpacing(size: size, ratio: Self.emojiLineHeight))
                .accessibilityHidden(true)
        }
    }

    private func drawing(_ key: ImageKey) -> some View {
        Canvas { context, canvasSize in
            var canvas = SVGCanvas(
                viewBox: WordImages.viewBox,
                fitting: CGRect(origin: .zero, size: canvasSize)
            )
            WordImages.draw(key, into: &canvas)
            var ctx = context
            canvas.render(into: &ctx)
        }
        .frame(width: size, height: size)
        // `alt=""` is the decorative default; a non-empty alt makes it an image
        // with that name. The drawing's own `<title>` (`WordImages.label`) is
        // NOT used here — the web `<img>` never sees it, since it is inside the
        // referenced SVG file.
        .accessibilityHidden(alt.isEmpty)
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(Text(verbatim: alt))
    }

    /// The draw list this view would render for `key` — the seam the tests use
    /// to prove every `ImageKey` resolves to real geometry rather than an empty
    /// canvas.
    static func drawList(for key: ImageKey) -> [SVGDrawNode] {
        var canvas = SVGCanvas()
        WordImages.draw(key, into: &canvas)
        return canvas.nodes
    }
}
