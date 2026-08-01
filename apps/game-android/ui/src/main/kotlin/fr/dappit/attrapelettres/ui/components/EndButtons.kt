package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown

// Port of `src/components/EndButtons.tsx` — leave, or keep going.
//
//   <div className="mt-1 flex flex-wrap items-center justify-center gap-3">
//     <button
//       onPointerDown={onMenu}
//       className="rounded-full bg-white/80 px-7 py-4 text-xl font-extrabold
//                  text-[#5A3A1E] shadow [touch-action:none]"
//     >🏠 Menu</button>
//     <button
//       onPointerDown={onNext}
//       className="rounded-full bg-[#66BB6A] px-9 py-4 text-2xl font-extrabold
//                  text-white [touch-action:none]"
//       style={{ boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)" }}
//     >🎉 Suivant</button>
//   </div>
//
// Both buttons fire on `onPointerDown`, not on click, so both go through the
// app-wide `touchDown` primitive (invariant 1). `[touch-action:none]` is the
// web's way of saying « this gesture is mine, do not scroll »; the Compose
// recogniser takes the down on `PointerEventPass.Initial` and treats a consumed
// move as a cancel, which is the same contract.
//
// Neither button is `font-black`: they are `font-extrabold` (800). The gap
// between 800 and 900 is small and visible, and getting it wrong here would
// quietly make the end screen louder than the hub.
//
// Tailwind spacing is n x 4 px: mt-1 = 4, gap-3 = 12, py-4 = 16, px-7 = 28,
// px-9 = 36.

object EndButtonsMetrics {
    /** `gap-3`, on both axes — the row wraps on a narrow phone. */
    val GAP: Dp = 12.dp

    /** `mt-1`. */
    val TOP_MARGIN: Dp = 4.dp

    /** « 🏠 Menu » — `px-7 py-4 text-xl`, `bg-white/80`. */
    val MENU_PADDING_X: Dp = 28.dp
    val MENU_PADDING_Y: Dp = 16.dp

    /** « 🎉 Suivant » — `px-9 py-4 text-2xl`, `bg-[#66BB6A]`. */
    val NEXT_PADDING_X: Dp = 36.dp
    val NEXT_PADDING_Y: Dp = 16.dp

    /** `0 8px 0 #43A047`. */
    val NEXT_LIP_DROP: Dp = 8.dp

    /** `0 14px 24px rgba(0,0,0,0.2)`. */
    val NEXT_SOFT_SHADOW = CssShadow(y = 14.dp, blur = 24.dp, opacity = 0.2f)

    /**
     * Tailwind's plain `shadow` utility, both layers:
     * `0 1px 3px 0 rgb(0 0 0/0.1), 0 1px 2px -1px rgb(0 0 0/0.1)`.
     *
     * Taken from the shared [Shadows] table rather than re-authored — the same
     * utility is on the shop's back button and the trial chip, and two copies of
     * a Tailwind default is how they drift.
     */
    val MENU_SHADOW: List<CssShadow> = Shadows.tailwind
}

/**
 * End-of-run choices, shared by every exercise's finished screen: leave to the
 * hub, or keep going to the next level. « Suivant » is the primary (green)
 * button.
 */
@Composable
fun EndButtons(
    onMenu: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale

    WrapRow(
        spacing = EndButtonsMetrics.GAP,
        modifier = modifier.padding(top = EndButtonsMetrics.TOP_MARGIN),
    ) {
        BasicText(
            text = Copy.EndButtons.MENU,
            style = Typography.style(
                size = Typography.Size.xl,
                weight = Typography.Weight.extrabold,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
            modifier = Modifier
                .cssShadow(EndButtonsMetrics.MENU_SHADOW, CircleShape)
                .background(Color.White.copy(alpha = Palette.White.o80), CircleShape)
                .touchDown { onMenu() }
                .padding(
                    horizontal = EndButtonsMetrics.MENU_PADDING_X,
                    vertical = EndButtonsMetrics.MENU_PADDING_Y,
                )
                .clearAndSetSemantics {
                    contentDescription = Copy.EndButtons.MENU
                    role = Role.Button
                },
        )

        BasicText(
            text = Copy.EndButtons.NEXT,
            style = Typography.style(
                size = Typography.Size.xxl,
                weight = Typography.Weight.extrabold,
                color = Color.White,
                fontScale = fontScale,
            ),
            modifier = Modifier
                .liftedPill(
                    fill = { SolidColor(Palette.green.color) },
                    lip = Palette.greenLip.color,
                    drop = EndButtonsMetrics.NEXT_LIP_DROP,
                    soft = EndButtonsMetrics.NEXT_SOFT_SHADOW,
                )
                .touchDown { onNext() }
                .padding(
                    horizontal = EndButtonsMetrics.NEXT_PADDING_X,
                    vertical = EndButtonsMetrics.NEXT_PADDING_Y,
                )
                .clearAndSetSemantics {
                    contentDescription = Copy.EndButtons.NEXT
                    role = Role.Button
                },
        )
    }
}
