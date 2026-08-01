package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography

// Port of `src/components/Finished.tsx` — the shared end-of-session screen
// (iOS: Sources/ALUI/Components/Finished.swift).
//
//   <div className="relative z-[41] flex flex-1 flex-col items-center
//                   justify-center gap-5 px-6 text-center">
//     <div style={{ fontSize: "clamp(64px,20vw,110px)", lineHeight: 1 }}>🤩</div>
//     {earned > 0 && <EarnBadge earned={earned} />}
//     <div className="flex flex-wrap justify-center gap-1">
//       {stars.map((s, i) => (
//         <span key={i} style={{ fontSize: 28,
//           ...(s ? null : { filter: "grayscale(1)", opacity: 0.45 }) }}>⭐</span>
//       ))}
//     </div>
//     <h2 className="m-0 font-black text-[#5A3A1E]"
//         style={{ fontSize: "clamp(26px,7vw,40px)" }}>{title}</h2>
//     <EndButtons onMenu={onMenu} onNext={onNext} />
//   </div>
//
// INVARIANT 8, twice over:
//
//  - `earned` is `sessionReward`'s return value, shown as-is. This file performs
//    no arithmetic on it. The only decision it makes is whether the pill APPEARS
//    — `earned > 0`, a visibility rule. Every finished run now pays at least the
//    curve's floor, so the guard no longer fires; it stays because the day an
//    exercise pays nothing again, a « +0 » would read as a punishment.
//  - `stars` is the same per-round first-try array the in-game strip rendered. A
//    greyed star is still DRAWN: the round counts as played (invariants 3 and
//    8). Never filter the array, never re-derive it.
//
// Both claims are pure, so they live in [FinishedDisplay] where a host test can
// assert them without a composition, and the composable consumes that model
// rather than deciding again.

/** What the screen shows, as data. */
data class FinishedDisplay(
    /** `earned > 0 && <EarnBadge earned>` — null means « no pill ». */
    val earned: Int?,
    val stars: List<Boolean>,
    val title: String,
)

object FinishedMetrics {
    /** `gap-5`. */
    val GAP: Dp = 20.dp

    /** `px-6`. */
    val PADDING_X: Dp = 24.dp

    /** `gap-1` between recap stars. */
    val STAR_GAP: Dp = 4.dp

    /** `fontSize: 28` — a plain number in the TSX, not a clamp. */
    val STAR_SIZE: Dp = 28.dp

    /** `clamp(64px, 20vw, 110px)` on the 🤩. */
    val CHEER_SIZE = FluidSpec(64f, 20f, 110f)

    /** `clamp(26px, 7vw, 40px)` on the title. */
    val TITLE_SIZE = FluidSpec(26f, 7f, 40f)

    /** `z-[41]` — one above the GameFrame confetti canvas (`zIndex: 40`). */
    const val Z_INDEX: Float = 41f

    /**
     * `{earned > 0 && <EarnBadge earned={earned} />}` and nothing else: the
     * points are passed through untouched, the stars are passed through
     * unfiltered.
     */
    fun display(stars: List<Boolean>, earned: Int, title: String): FinishedDisplay =
        FinishedDisplay(
            earned = if (earned > 0) earned else null,
            stars = stars,
            title = title,
        )
}

@Composable
fun Finished(
    stars: List<Boolean>,
    earned: Int,
    title: String,
    reduceMotion: ReduceMotionSource,
    onMenu: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = FinishedMetrics.display(stars, earned, title)
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = modifier
            .zIndex(FinishedMetrics.Z_INDEX)
            // flex-1 + items-center + justify-center: fill the frame, centre both ways.
            .fillMaxSize()
            .padding(horizontal = FinishedMetrics.PADDING_X),
        verticalArrangement = Arrangement.spacedBy(FinishedMetrics.GAP, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No `aria-hidden` in the TSX — the 🤩 and the recap stars are announced
        // on the web, so they are announced here too. Freezing behaviour means
        // not tidying the screen-reader output either.
        BasicText(
            text = Copy.Finished.CHEER_EMOJI,
            style = Typography.style(
                size = FinishedMetrics.CHEER_SIZE.resolve(viewport),
                fontScale = fontScale,
            ),
        )

        model.earned?.let { shown ->
            EarnBadge(earned = shown, reduceMotion = reduceMotion)
        }

        StarRecap(model.stars, fontScale)

        BasicText(
            text = model.title,
            style = Typography.style(
                size = FinishedMetrics.TITLE_SIZE.resolve(viewport),
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ).copy(textAlign = TextAlign.Center), // text-center
        )

        EndButtons(onMenu = onMenu, onNext = onNext)
    }
}

/**
 * The star row: `flex flex-wrap justify-center gap-1` — it wraps on a long run
 * rather than shrinking the stars.
 */
@Composable
private fun StarRecap(stars: List<Boolean>, fontScale: Float) {
    val style: TextStyle = Typography.style(
        size = FinishedMetrics.STAR_SIZE,
        fontScale = fontScale,
    )

    WrapRow(spacing = FinishedMetrics.STAR_GAP) {
        for (won in stars) {
            // `{ filter: "grayscale(1)", opacity: 0.45 }` on a lost round — the
            // star is greyed, never dropped: the round still counts as played.
            val modifier =
                if (won) Modifier
                else Modifier
                    .graphicsLayer { alpha = Palette.Lost.opacity }
                    .grayscale(1f - Palette.Lost.saturation)
            BasicText(text = Copy.Finished.STAR, style = style, modifier = modifier)
        }
    }
}
