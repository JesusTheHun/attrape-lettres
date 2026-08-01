package fr.dappit.attrapelettres.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.LetterScript

// The type ramp (iOS Sources/ALUI/Design/Typography.swift).
//
// The PWA sets ONE font family on every screen root --
//   ui-rounded,'SF Pro Rounded',system-ui,sans-serif
// -- and everything inside inherits it. On Apple platforms that resolves to SF
// Pro Rounded, which SwiftUI reaches natively; on Android there is NO rounded
// system face, so the first stack entry has no analogue and `system-ui` is what
// a Chrome-on-Android build of this same PWA would resolve to as well. The app
// font here is therefore `FontFamily.SansSerif` -- Roboto -- and the difference
// from iOS is a platform fact, not a port bug. Shipping a rounded webfont was
// rejected for the same reason the web rejected an ecole-cursive one: the app
// deliberately carries no font files.
//
// Sizes are the computed Tailwind numbers, not class names: a class is shorthand
// for a px value and this file carries the value. CSS px == Android dp, so the
// authored numbers travel unchanged.
//
// Sizes are FIXED, not font-scale-scaled -- see `fixedSp` in Fluid.kt. CSS px
// does not scale and neither does `Font.system(size:)` on iOS, so all three apps
// agree. The accessibility floor (invariant 6) is met the way the web meets it:
// tap-target size and content descriptions, not text scaling. A glyph whose
// tile came from `fluid` would overflow that tile at a 2x system font scale,
// which is a worse outcome for the child than a fixed 64 dp letter.

object Typography {

    // Families ---------------------------------------------------------------

    /**
     * The CSS stack every screen root sets. Kept for the record (and for a
     * future pixel-diff harness); the app renders through [appFamily].
     */
    const val CSS_FONT_STACK = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif"

    /**
     * `SCRIPT_FONT.cursive` in `letterForms.ts` -- the OS handwriting stack.
     * Zero-dependency by design: no ecole-cursive webfont is shipped, and the
     * joined « attaché » shape a French six-year-old learns is close enough.
     */
    const val CSS_CURSIVE_STACK =
        "'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive"

    /**
     * The cursive stack, in order, as font families a browser would try. NONE of
     * them exists on Android -- Snell Roundhand and Apple Chancery are Apple's,
     * Segoe Script is Microsoft's, Bradley Hand is Apple's -- so the browser
     * would fall through to the generic `cursive` keyword, which is exactly what
     * [cursiveFamily] resolves to natively. Kept so the order is auditable
     * against the CSS.
     */
    val cursiveFamilies = listOf(
        "Snell Roundhand",
        "Apple Chancery",
        "Segoe Script",
        "Bradley Hand",
    )

    /** `system-ui` on Android: Roboto. The app's one voice. */
    val appFamily: FontFamily = FontFamily.SansSerif

    /**
     * The generic `cursive` family. AOSP's `fonts.xml` aliases it to Dancing
     * Script, a joined handwriting face -- which is the one thing the cursive
     * exercises need, because a printed fallback would teach the wrong letter
     * shape. If a device has no `cursive` alias the platform silently falls back
     * to the default family, which is the same fail-soft the iOS port chose: a
     * missing font must never render blank (invariant 3's spirit -- nothing in a
     * letter game may dead-end).
     */
    val cursiveFamily: FontFamily = FontFamily.Cursive

    /**
     * The family a [LetterScript] must be drawn in. `print` is the app's own
     * sans -- the same face as every other glyph, which is the point: a printed
     * letter on a tile is the letter the child sees everywhere else.
     *
     * The letter's screen-reader label is NOT built here -- `core.domain.faceLabel`
     * owns it and is unit-tested there.
     */
    fun letterFamily(script: LetterScript): FontFamily = when (script) {
        LetterScript.PRINT -> appFamily
        LetterScript.CURSIVE -> cursiveFamily
    }

    // Sizes --------------------------------------------------------------------

    /**
     * The Tailwind text scale actually used in this app, in CSS px == dp.
     * Convert at the call site with [sp], which pins the physical size.
     */
    object Size {
        /** `text-[11px]` -- the hub's repeat-coin badge. */
        val xxs: Dp = 11.dp

        /** `text-xs` -- shop price chips. */
        val xs: Dp = 12.dp

        /** `text-sm` -- hints, captions, the gate's prose. */
        val sm: Dp = 14.dp

        /** `text-base` -- adult body copy. */
        val base: Dp = 16.dp

        /** `text-lg` -- hub chips, « ← Menu », section labels. */
        val lg: Dp = 18.dp

        /** `text-xl` -- headings, the end button. */
        val xl: Dp = 20.dp

        /** `text-2xl` -- level numbers, « Suivant », the name field. */
        val xxl: Dp = 24.dp

        /** The ramp in authored order, for the test that pins its monotonicity. */
        val ramp: List<Dp> = listOf(xxs, xs, sm, base, lg, xl, xxl)
    }

    /** A ramp size at a FIXED physical size. See `fixedSp`. */
    fun sp(size: Dp, fontScale: Float): TextUnit = fixedSp(size.value, fontScale)

    // Weights -------------------------------------------------------------------

    /**
     * CSS numeric weights, one for one. Compose names weights by NUMBER, so the
     * trap the iOS port has to dodge -- SwiftUI calling 800 `.heavy` and 900
     * `.black`, which makes `font-extrabold` read plausibly as `.black` -- does
     * not exist here. The test still pins the numbers, because that mapping is
     * what an eventual three-way pixel diff rests on.
     */
    object Weight {
        val semibold: FontWeight = FontWeight.SemiBold // 600
        val bold: FontWeight = FontWeight.Bold // 700
        val extrabold: FontWeight = FontWeight.ExtraBold // 800
        val black: FontWeight = FontWeight.Black // 900
    }

    // Line height ----------------------------------------------------------------

    /** Tailwind `leading-*` multipliers, as CSS ratios. */
    object LineHeight {
        const val none = 1.0f // leading-none
        const val tight = 1.25f // leading-tight
        const val snug = 1.375f // leading-snug
    }

    /**
     * CSS `line-height` is the TOTAL height of a line box, and so is Compose's
     * `TextStyle.lineHeight` -- the two mean the same thing, so the conversion is
     * a plain multiply.
     *
     * This is where Android is a closer port than iOS: SwiftUI's `.lineSpacing`
     * is the EXTRA gap between lines, so `Typography.swift` has to subtract the
     * font's natural line height and records the resulting drift. Nothing to
     * subtract here.
     */
    fun lineHeightPx(size: Float, ratio: Float): Float = size * ratio

    fun lineHeight(size: Dp, ratio: Float, fontScale: Float): TextUnit =
        fixedSp(lineHeightPx(size.value, ratio), fontScale)

    // Styles ----------------------------------------------------------------------

    /**
     * One authored line of type. `fontScale` comes from the ambient `Density`
     * (`LocalDensity.current.fontScale`) and is divided back out, so the result
     * is the same physical size the web draws.
     */
    fun style(
        size: Dp,
        weight: FontWeight = Weight.black,
        color: Color = Palette.ink.color,
        family: FontFamily = appFamily,
        ratio: Float = LineHeight.none,
        fontScale: Float = 1f,
    ): TextStyle = TextStyle(
        color = color,
        fontSize = sp(size, fontScale),
        fontWeight = weight,
        fontFamily = family,
        lineHeight = lineHeight(size, ratio, fontScale),
    )
}
