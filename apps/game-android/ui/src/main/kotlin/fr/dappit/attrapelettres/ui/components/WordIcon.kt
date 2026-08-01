package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import fr.dappit.attrapelettres.art.images.WordImage
import fr.dappit.attrapelettres.core.domain.ImageKey
import fr.dappit.attrapelettres.ui.design.Typography

// Port of `src/components/WordIcon.tsx` — the picture for a word
// (iOS: Sources/ALUI/Components/WordIcon.swift).
//
//   export function WordIcon({ emoji, img, size, alt = "" }) {
//     if (img)
//       return <img src={img} alt={alt} draggable={false} className="select-none"
//                   style={{ width: size, height: size,
//                            objectFit: "contain", display: "block" }} />;
//     return <span aria-hidden style={{ fontSize: size, lineHeight: 1.1,
//                                       display: "block" }}>{emoji}</span>;
//   }
//
// « We reach for `img` only where the closest emoji misrepresents the word (a
// cupcake for « macaron », a hut for « igloo »…); `emoji` stays as the text
// fallback so nothing ever renders blank. »
//
// The four drawings are ALREADY DRAWN, in :art's `images/WordImages.kt`, off the
// same `d` strings as the SVG files. Nothing in this file authors geometry, and
// NO `when` OVER `ImageKey` LIVES HERE, deliberately: `WordImages.draw` is the
// single exhaustive one — `else`-less, so a fifth key is a compile error there
// rather than a picture that silently renders nothing. A second table here would
// reintroduce exactly the failure that design prevents.
//
// `size` in the TSX is a CSS length and every call site passes a `clamp(…)`;
// callers resolve it (`FluidSpec.resolve`) and hand over the Dp. The box stays
// square, so `object-fit: contain` on a square 128-unit viewBox is the identity
// and needs no aspect handling.

object WordIconMetrics {
    /** `lineHeight: 1.1` on the emoji span. */
    const val EMOJI_LINE_HEIGHT: Float = 1.1f
}

/**
 * [alt] is `alt` on the `<img>`. Empty (the default) means decorative: « every
 * call site already labels the word in text or an aria-label on the surrounding
 * tile/button ». The emoji branch is `aria-hidden` and ignores [alt] entirely,
 * exactly as the TSX does.
 */
@Composable
fun WordIcon(
    emoji: String,
    size: Dp,
    modifier: Modifier = Modifier,
    img: ImageKey? = null,
    alt: String = "",
) {
    if (img != null) {
        // :art's `WordImage` carries the drawing's own French `<title>` as its
        // content description; the web `<img>` never sees that title (it is
        // inside the referenced SVG file), so this wrapper replaces it with the
        // TSX's `alt` — nothing when decorative.
        Box(
            modifier
                .size(size)
                .clearAndSetSemantics {
                    if (alt.isNotEmpty()) {
                        contentDescription = alt
                        role = Role.Image
                    }
                },
        ) {
            WordImage(img, Modifier.fillMaxSize())
        }
    } else {
        BasicText(
            text = emoji,
            style = Typography.style(
                size = size,
                ratio = WordIconMetrics.EMOJI_LINE_HEIGHT,
                fontScale = LocalDensity.current.fontScale,
            ),
            // `<span aria-hidden>`.
            modifier = modifier.clearAndSetSemantics { },
        )
    }
}
