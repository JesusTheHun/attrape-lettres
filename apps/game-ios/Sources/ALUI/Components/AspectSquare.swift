import SwiftUI

/* -------------------------------------------------------------------------- */
/* Tailwind `aspect-square`, ported correctly — D52.                            */
/*                                                                             */
/* The obvious spelling is wrong, it compiles, and it looks plausible on the    */
/* screen, which is the worst combination a port can offer:                    */
/*                                                                             */
/*     Text("1")                                                               */
/*         .frame(maxWidth: .infinity)                                         */
/*         .aspectRatio(1, contentMode: .fit)   // ← NOT aspect-square         */
/*                                                                             */
/* `.fit` means "shrink me until I fit inside the proposal", and the proposal a */
/* `LazyVGrid` cell hands down carries the CONTENT's ideal height. A 24 pt      */
/* digit is 28.67 pt tall, so a 60 pt-wide column rendered a 28.67 pt square,   */
/* centred, with 31 pt of dead space around it. On the hub that meant every     */
/* level button was under Apple's 44 pt floor (invariant 6), and the reward     */
/* pill — an overlay, so proposed the button's width — was squeezed to 28.67 pt */
/* and truncated « +10 ⭐ » to « +1 ». It was reported as three separate visual  */
/* bugs and was one line.                                                      */
/*                                                                             */
/* CSS derives the height FROM THE WIDTH. `Color.clear` has no ideal size and   */
/* accepts whatever it is proposed, so `.aspectRatio` on it resolves against    */
/* the width alone and yields a real width × width square; the content then     */
/* rides on top rather than driving the box.                                   */
/*                                                                             */
/* `AspectSquareTests` asserts the resolved size, so the difference between the */
/* two spellings is a failing test rather than a screenshot someone happens to  */
/* look at closely.                                                            */
/* -------------------------------------------------------------------------- */

/// A square whose side is the width it is offered, with `content` centred in it.
struct AspectSquare<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { content }
    }
}
