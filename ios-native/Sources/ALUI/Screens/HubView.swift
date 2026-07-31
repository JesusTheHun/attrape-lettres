import ALArt
import ALCore
import SwiftUI

/* -------------------------------------------------------------------------- */
/* The hub half of `src/App.tsx` (lines 156-263) — what the child sees first.   */
/*                                                                             */
/* Header chips, the mascot door, the title, the trial pill, then one section   */
/* per `Levels.exercises` row: icon + name + hint chips and a five-column grid  */
/* of level buttons with their reward pills.                                    */
/*                                                                             */
/* ── INVARIANT 5 LIVES ON THIS SCREEN ──────────────────────────────────────── */
/* Every level of every exercise is reachable, always. `hubLevelCells` renders  */
/* `1...levelCount` unconditionally — its signature takes the catalog row and   */
/* the reward PREVIEW function and nothing else, so a lock cannot be wired in   */
/* without changing a signature. There is no profile parameter to consult, no   */
/* `unlocked` flag to read, and the tap handler forwards every (exercise,       */
/* level) pair straight to the router.                                         */
/*                                                                             */
/* ── INVARIANT 7 ───────────────────────────────────────────────────────────── */
/* The section header renders `ExerciseIcon(id:)` — the drawn catalog — and     */
/* never `meta.emoji`, which exists in the data and must stay unused here       */
/* (shell.md §4.8). The chrome emoji (👤 🔊 ⭐ 🪙) are the web's own and stay.    */
/*                                                                             */
/* `preview(ex.id, lvl)` is called once per level button per body evaluation    */
/* (~75 calls), exactly as the TSX calls it per render. It is a pure read of    */
/* the folded ledger — it must never write (shell.md §7.3).                    */
/* -------------------------------------------------------------------------- */

// MARK: - Authored metrics (App.tsx Tailwind classes, verbatim)

public enum HubMetrics {

    /* -- the stage --------------------------------------------------------- */

    /// `min-h-[620px] w-full … overflow-hidden rounded-3xl px-5 pb-10 pt-8`.
    public static let stagePaddingX: CGFloat = 20
    public static let stagePaddingTop: CGFloat = 32
    public static let stagePaddingBottom: CGFloat = 40
    public static let cornerRadius: CGFloat = 24

    /// `max-w-md` — the header row and every section.
    public static let rowMaxWidth: CGFloat = 448

    /* -- header ------------------------------------------------------------ */

    /// `mb-4` under the header row; `gap-2` between 🔊 and ⭐.
    public static let headerBottomMargin: CGFloat = 16
    public static let headerActionGap: CGFloat = 8
    /// Chips: `px-4 py-2` (player, ⭐) / `px-3 py-2` (🔊), `text-lg`.
    public static let chipPaddingX: CGFloat = 16
    public static let listenPaddingX: CGFloat = 12
    public static let chipPaddingY: CGFloat = 8
    /// `max-w-[55%]` on the player chip — 55 % of the header row.
    public static let playerChipFraction: CGFloat = 0.55
    /// `active:scale-95` on every hub button.
    public static let activeScale: CGFloat = 0.95

    /* -- mascot + title ---------------------------------------------------- */

    /// `<Mascot size={112}>`, `mb-1` on its button.
    public static let mascotSize: CGFloat = 112
    public static let mascotBottomMargin: CGFloat = 4
    /// `fontSize: clamp(28px,8vw,44px)` on « Attrape-Lettres ».
    public static let titleSize = FluidSpec(min: 28, vw: 8, max: 44)
    /// `mb-2 mt-1` on the subtitle.
    public static let subtitleTopMargin: CGFloat = 4
    public static let subtitleBottomMargin: CGFloat = 8

    /* -- the trial pill ---------------------------------------------------- */

    /// `mb-6 … px-4 py-1.5 text-sm font-semibold` — and the `mb-6` spacer that
    /// replaces it when there is no notice.
    public static let trialPaddingX: CGFloat = 16
    public static let trialPaddingY: CGFloat = 6
    public static let trialBottomMargin: CGFloat = 24

    /* -- sections ---------------------------------------------------------- */

    /// `mb-6` per section; `mb-2` under the chip row; `gap-2` inside it.
    public static let sectionBottomMargin: CGFloat = 24
    public static let chipRowBottomMargin: CGFloat = 8
    public static let chipRowGap: CGFloat = 8
    /// `grid-cols-5 gap-2`, `rounded-2xl`, `text-2xl`.
    public static let gridColumns = 5
    public static let gridGap: CGFloat = 8
    public static let levelCornerRadius: CGFloat = 16

    /* -- shadows ------------------------------------------------------------ */

    /// Tailwind `shadow` — `0 1px 3px rgb(0 0 0 / 0.1)` (first layer, same
    /// simplification as `DashboardMetrics.cardShadow`).
    public static let shadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)
    /// Tailwind `shadow-sm` — `0 1px 2px rgb(0 0 0 / 0.05)`.
    public static let shadowSm = CSSShadow(y: 1, blur: 2, opacity: 0.05)

    /* -- the reward pills --------------------------------------------------- */

    /// Both pills: `gap-0.5`, `py-0.5`, `leading-none`.
    public static let pillGap: CGFloat = 2
    public static let pillPaddingY: CGFloat = 2
    /// Jackpot: `-right-2 -top-2 … px-2 text-sm … ring-2 ring-white`.
    public static let jackpotPaddingX: CGFloat = 8
    public static let jackpotOffset: CGFloat = 8
    public static let jackpotRing: CGFloat = 2
    public static let jackpotFontSize: CGFloat = Typography.Size.sm
    /// Coin: `-right-1 -top-1 … px-1.5 text-[11px] … ring-1 ring-[#FFE08A]`.
    public static let coinPaddingX: CGFloat = 6
    public static let coinOffset: CGFloat = 4
    public static let coinRing: CGFloat = 1
    public static let coinFontSize: CGFloat = Typography.Size.xxs

    /* -- audio -------------------------------------------------------------- */

    /// `audio.say(String(profile.balance), { rate: 0.85 })` — the ONE call site
    /// in the app that overrides the 0.94 default rate.
    public static let listenRate: Double = 0.85

    /* -- derived ------------------------------------------------------------ */

    /// The header row's real width: `max-w-md` inside the `min(480, vw − 32)`
    /// card minus the stage's `px-5`. Needed because `max-w-[55%]` is 55 % of
    /// THIS, not of the viewport.
    public static func headerWidth(viewport: CGFloat) -> CGFloat {
        let card = Swift.min(Shell.cardMaxWidth, viewport - 2 * Shell.minimumInset)
        return Swift.min(rowMaxWidth, card - 2 * stagePaddingX)
    }

    /// `max-w-[55%]` on the player chip, resolved.
    public static func playerChipMaxWidth(viewport: CGFloat) -> CGFloat {
        headerWidth(viewport: viewport) * playerChipFraction
    }
}

// MARK: - The pure rules (host-tested in HubTests)

/// One level button, resolved. `reward == nil` ⇒ no pill at all (a training
/// exercise, difficulty 0 — invariant 8: it never pays, so nothing is promised).
public struct HubLevelCell: Equatable, Sendable {
    public let level: Int
    public let reward: HubLevelReward?
    /// The button's accessibility label — « Niveau 3, gagne 10 étoiles » /
    /// « Niveau 3, pour s'entraîner ».
    public let label: String

    public init(level: Int, reward: HubLevelReward?, label: String) {
        self.level = level
        self.reward = reward
        self.label = label
    }
}

/// « First clear is the jackpot (10) — a big gold star pill; repeats decay to a
/// small coin pill » (`App.tsx:226-231`). `jackpot == (pts == 10)`, nothing
/// smarter.
public struct HubLevelReward: Equatable, Sendable {
    public let points: Int
    public let jackpot: Bool

    public init(points: Int, jackpot: Bool) {
        self.points = points
        self.jackpot = jackpot
    }
}

/**
 * The whole level row for one catalog entry: `1...levelCount`, unconditionally
 * (invariant 5). The only inputs are the catalog row and the preview function —
 * there is no profile, no entitlement and no "unlocked" anything to consult.
 */
public func hubLevelCells(
    for meta: ExerciseMeta,
    preview: (ExerciseId, Int) -> Int
) -> [HubLevelCell] {
    guard meta.levelCount > 0 else { return [] }
    return (1...meta.levelCount).map { level in
        let points = preview(meta.id, level)
        return HubLevelCell(
            level: level,
            reward: points > 0
                ? HubLevelReward(points: points, jackpot: points == 10)
                : nil,
            label: points > 0
                ? Copy.Hub.levelPaying(level, points: points)
                : Copy.Hub.levelTraining(level)
        )
    }
}

/**
 * The hint chips after an exercise's name, in the TSX's order — `mode`, then
 * `spell` (where `mixed` swaps in `MIXED_HINT`), then `match`, then the free
 * `hint`. No row sets more than one today, but the markup allows all four and
 * the port keeps all four conditionals (shell.md §4.8). Each is rendered with
 * the « · » prefix from `Copy.Hub.hintSeparator`.
 */
public func hubHintChips(for meta: ExerciseMeta) -> [String] {
    var chips: [String] = []
    if let mode = meta.mode, let hint = Levels.modeHint[mode] {
        chips.append(hint)
    }
    if let spell = meta.spell {
        if meta.mixed {
            chips.append(Levels.mixedHint)
        } else if let hint = Levels.spellHint[spell] {
            chips.append(hint)
        }
    }
    if let match = meta.match, let hint = Levels.matchHint[match] {
        chips.append(hint)
    }
    if let hint = meta.hint {
        chips.append(hint)
    }
    return chips
}

/// `children.find((c) => c.id === activeId)?.name ?? ""` — the player chip's
/// text. Displayed on-device only; the name never reaches a transport from
/// here (invariant 10).
public func hubPlayerName(children: [ChildProfile], activeId: String?) -> String {
    children.first(where: { $0.id == activeId })?.name ?? ""
}

/**
 * « Écouter » the score (`App.tsx:42-45`): fr-FR TTS reads the bare digits as
 * the whole number ("42" → « quarante-deux »), so a child ties the shape to the
 * word. No baked clip exists for arbitrary numbers; `say()` falls back to
 * speech synthesis. Fires on the tap gesture — `unlock()` first, synchronously,
 * then the line. Rate 0.85, pitch left at the 1.1 default.
 */
@discardableResult
public func hubListenBalance(audio: any AudioEngine, balance: Int) -> Task<Void, Never> {
    audio.unlock()
    return Task { _ = await audio.say(String(balance), rate: HubMetrics.listenRate, pitch: 1.1) }
}

// MARK: - The view

/// `<App>`'s hub return. Reads `ProfileStore` and `EntitlementModel` from the
/// environment (the React-context ports); the audio engine and the three route
/// actions come from `RootView`, which owns the route state.
@MainActor
public struct HubView: View {

    @Environment(ProfileStore.self) private var store
    @Environment(EntitlementModel.self) private var entitlement
    @Environment(\.alViewportWidth) private var viewport

    private let audio: any AudioEngine
    /// A level tap. The `canPlay` check and its telemetry live in `RootView`'s
    /// `open` — this view only reports which button was tapped.
    private let onOpen: (ExerciseId, Int) -> Void
    /// The ⭐ chip and the mascot door.
    private let onDashboard: () -> Void
    /// The trial pill. Lands on the child-safe « ask a grown-up » step, so the
    /// purchase still sits behind the parental gate.
    private let onPaywall: () -> Void

    public init(
        audio: any AudioEngine,
        onOpen: @escaping (ExerciseId, Int) -> Void,
        onDashboard: @escaping () -> Void,
        onPaywall: @escaping () -> Void
    ) {
        self.audio = audio
        self.onOpen = onOpen
        self.onDashboard = onDashboard
        self.onPaywall = onPaywall
    }

    public var body: some View {
        // The web page scrolls as a whole (17 sections always overflow 620 pt);
        // the stage grows with its content inside the scroll. The stage is a
        // separate view because `ImageRenderer` cannot lay out a `ScrollView`'s
        // content on the host — `HubStage` is what the raster tier sees.
        ScrollView {
            HubStage(
                audio: audio,
                onOpen: onOpen,
                onDashboard: onDashboard,
                onPaywall: onPaywall
            )
        }
        // OUTSIDE the scroll view (D51). A wash applied to `HubStage` is part of
        // the SCROLL CONTENT: it is pinned to the content, not the window, so
        // the top of the screen stayed page-cream and the wash slid away under
        // the finger. `HubStage` therefore does not paint itself, and the raster
        // tier applies this same modifier to see the same pixels.
        .stageWash(Palette.stage)
    }
}

/// Everything inside the hub's scroll — the stage `<div>` of `App.tsx:156-262`.
/// Internal so `HubTests` can rasterise it directly (a `ScrollView` renders
/// empty under `ImageRenderer`); the app only ever reaches it through
/// `HubView`.
@MainActor
struct HubStage: View {

    @Environment(ProfileStore.self) private var store
    @Environment(EntitlementModel.self) private var entitlement
    @Environment(\.alViewportWidth) private var viewport

    let audio: any AudioEngine
    let onOpen: (ExerciseId, Int) -> Void
    let onDashboard: () -> Void
    let onPaywall: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.bottom, HubMetrics.headerBottomMargin)
            mascotDoor
                .padding(.bottom, HubMetrics.mascotBottomMargin)
            title
            subtitle
                .padding(.top, HubMetrics.subtitleTopMargin)
                .padding(.bottom, HubMetrics.subtitleBottomMargin)
            trialSlot
            sections
        }
        .frame(maxWidth: .infinity, minHeight: Shell.minimumScreenHeight, alignment: .top)
        .padding(.horizontal, HubMetrics.stagePaddingX)
        .padding(.top, HubMetrics.stagePaddingTop)
        .padding(.bottom, HubMetrics.stagePaddingBottom)
        .clipShape(RoundedRectangle(cornerRadius: HubMetrics.cornerRadius))
        .fontDesign(.rounded)  // fontFamily: ui-rounded,'SF Pro Rounded',…
    }

    // MARK: header

    private var header: some View {
        HStack {
            playerChip
            Spacer(minLength: 0)
            HStack(spacing: HubMetrics.headerActionGap) {
                listenChip
                balanceChip
            }
        }
        .frame(maxWidth: HubMetrics.rowMaxWidth)
    }

    private var playerChip: some View {
        Button(action: { store.switchChild() }) {
            Text(verbatim: Copy.Hub.playerChipPrefix + hubPlayerName(
                children: store.children, activeId: store.activeId))
                .lineLimit(1)
                .truncationMode(.tail)  // truncate
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)
                .padding(.horizontal, HubMetrics.chipPaddingX)
                .padding(.vertical, HubMetrics.chipPaddingY)
                .background(Color.white.opacity(Palette.White.o80), in: Capsule())
                .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                .shadow(
                    color: .black.opacity(HubMetrics.shadow.opacity),
                    radius: HubMetrics.shadow.swiftUIRadius,
                    y: HubMetrics.shadow.y
                )
                .contentShape(Capsule())
        }
        .buttonStyle(PickerActiveScaleStyle(scale: HubMetrics.activeScale))
        .frame(
            maxWidth: HubMetrics.playerChipMaxWidth(viewport: viewport),
            alignment: .leading
        )
        .accessibilityLabel(Text(verbatim: Copy.Hub.switchPlayer))
    }

    private var listenChip: some View {
        Button(action: { hubListenBalance(audio: audio, balance: store.profile.balance) }) {
            Text(verbatim: Copy.Hub.listenIcon)
                .font(Typography.rounded(Typography.Size.lg))
                .padding(.horizontal, HubMetrics.listenPaddingX)
                .padding(.vertical, HubMetrics.chipPaddingY)
                .background(Color.white.opacity(Palette.White.o80), in: Capsule())
                .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                .shadow(
                    color: .black.opacity(HubMetrics.shadow.opacity),
                    radius: HubMetrics.shadow.swiftUIRadius,
                    y: HubMetrics.shadow.y
                )
                .contentShape(Capsule())
        }
        .buttonStyle(PickerActiveScaleStyle(scale: HubMetrics.activeScale))
        .accessibilityLabel(Text(verbatim: Copy.Hub.listenBalance))
    }

    private var balanceChip: some View {
        Button(action: onDashboard) {
            Text(verbatim: Copy.Hub.balanceChipPrefix + "\(store.profile.balance)")
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)
                .padding(.horizontal, HubMetrics.chipPaddingX)
                .padding(.vertical, HubMetrics.chipPaddingY)
                .background(Color.white.opacity(Palette.White.o80), in: Capsule())
                .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                .shadow(
                    color: .black.opacity(HubMetrics.shadow.opacity),
                    radius: HubMetrics.shadow.swiftUIRadius,
                    y: HubMetrics.shadow.y
                )
                .contentShape(Capsule())
        }
        .buttonStyle(PickerActiveScaleStyle(scale: HubMetrics.activeScale))
        .accessibilityLabel(Text(verbatim: Copy.Hub.openDashboard))
    }

    // MARK: mascot + title

    private var mascotDoor: some View {
        Button(action: onDashboard) {
            MascotRigView(
                config: store.profile.config,
                mood: .idle,
                size: HubMetrics.mascotSize
            )
        }
        .buttonStyle(PickerActiveScaleStyle(scale: HubMetrics.activeScale))
        .accessibilityLabel(Text(verbatim: Copy.Hub.seeCompanion))
    }

    private var title: some View {
        Text(verbatim: Copy.Hub.title)
            .font(
                Typography.rounded(
                    HubMetrics.titleSize.resolve(viewport: viewport),
                    Typography.Weight.black)
            )
            .foregroundStyle(Palette.ink.color)
    }

    private var subtitle: some View {
        Text(verbatim: Copy.Hub.subtitle)
            .font(Typography.rounded(Typography.Size.base))
            .foregroundStyle(Palette.inkSoft.color)
    }

    // MARK: the trial pill

    /// The trial countdown — parent-facing, and the only route to the paywall
    /// that isn't a blocked exercise tap. No notice ⇒ the bare `mb-6` spacer.
    @ViewBuilder
    private var trialSlot: some View {
        if let notice = trialNotice(entitlement.entitlement) {
            Button(action: onPaywall) {
                Text(verbatim: notice)
                    .font(Typography.rounded(Typography.Size.sm, Typography.Weight.semibold))
                    .foregroundStyle(Palette.inkQuiet.color)
                    .padding(.horizontal, HubMetrics.trialPaddingX)
                    .padding(.vertical, HubMetrics.trialPaddingY)
                    .background(Color.white.opacity(Palette.White.o70), in: Capsule())
                    .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                    .shadow(
                        color: .black.opacity(HubMetrics.shadowSm.opacity),
                        radius: HubMetrics.shadowSm.swiftUIRadius,
                        y: HubMetrics.shadowSm.y
                    )
                    .contentShape(Capsule())
            }
            .buttonStyle(PickerActiveScaleStyle(scale: HubMetrics.activeScale))
            .padding(.bottom, HubMetrics.trialBottomMargin)
        } else {
            Color.clear
                .frame(height: 0)
                .padding(.bottom, HubMetrics.trialBottomMargin)
                .accessibilityHidden(true)
        }
    }

    // MARK: sections

    private var sections: some View {
        // `Levels.exercises` in catalog order — the array IS the hub order.
        ForEach(Levels.exercises, id: \.id) { meta in
            section(meta)
                .padding(.bottom, HubMetrics.sectionBottomMargin)
        }
    }

    private func section(_ meta: ExerciseMeta) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            chipRow(meta)
                .padding(.bottom, HubMetrics.chipRowBottomMargin)
            levelGrid(meta)
        }
        .frame(maxWidth: HubMetrics.rowMaxWidth)
    }

    /// `flex flex-wrap items-center gap-2` — icon, name, then the hint chips.
    private func chipRow(_ meta: ExerciseMeta) -> some View {
        HubWrapRow(spacing: HubMetrics.chipRowGap, lineSpacing: HubMetrics.chipRowGap) {
            ExerciseIcon(id: meta.id, size: 30)
            Text(verbatim: meta.name)
                .font(Typography.rounded(Typography.Size.xl, Typography.Weight.extrabold))
                .foregroundStyle(Palette.ink.color)
            ForEach(hubHintChips(for: meta), id: \.self) { hint in
                Text(verbatim: Copy.Hub.hintSeparator + hint)
                    .font(Typography.rounded(Typography.Size.sm))
                    .foregroundStyle(Palette.inkFaint.color)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func levelGrid(_ meta: ExerciseMeta) -> some View {
        LazyVGrid(
            columns: Array(
                repeating: GridItem(.flexible(), spacing: HubMetrics.gridGap),
                count: HubMetrics.gridColumns),
            spacing: HubMetrics.gridGap
        ) {
            ForEach(
                hubLevelCells(for: meta, preview: { store.preview(exercise: $0, level: $1) }),
                id: \.level
            ) { cell in
                levelButton(meta, cell)
            }
        }
    }

    private func levelButton(_ meta: ExerciseMeta, _ cell: HubLevelCell) -> some View {
        Button(action: { onOpen(meta.id, cell.level) }) {
            AspectSquare {
                Text(verbatim: "\(cell.level)")
                    .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)
            }
                .background(
                    Color.white.opacity(Palette.White.o80),
                    in: RoundedRectangle(cornerRadius: HubMetrics.levelCornerRadius)
                )
                .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                .shadow(
                    color: .black.opacity(HubMetrics.shadow.opacity),
                    radius: HubMetrics.shadow.swiftUIRadius,
                    y: HubMetrics.shadow.y
                )
                .overlay(alignment: .topTrailing) {
                    // `.fixedSize()`: the TSX pill is `position: absolute`, which
                    // is OUT of flow and sizes to its content. A SwiftUI overlay
                    // is proposed its parent's size, so without this the pill is
                    // squeezed into the button and the reward is misreported.
                    if let reward = cell.reward { pill(reward).fixedSize() }
                }
                .contentShape(RoundedRectangle(cornerRadius: HubMetrics.levelCornerRadius))
        }
        .buttonStyle(PickerActiveScaleStyle(scale: HubMetrics.activeScale))
        .accessibilityLabel(Text(verbatim: cell.label))
    }

    /// The corner pill: `+N ⭐` (jackpot, gold) or `+N 🪙` (repeat, white).
    /// `aria-hidden` — the level label already speaks the reward.
    private func pill(_ reward: HubLevelReward) -> some View {
        HStack(spacing: HubMetrics.pillGap) {
            Text(verbatim: Copy.Hub.rewardBadge(reward.points))
            Text(verbatim: reward.jackpot ? Copy.Hub.jackpotGlyph : Copy.Hub.coinGlyph)
        }
        .font(
            Typography.rounded(
                reward.jackpot ? HubMetrics.jackpotFontSize : HubMetrics.coinFontSize,
                Typography.Weight.black)
        )
        .foregroundStyle(reward.jackpot ? Palette.goldInk.color : Palette.coinInk.color)
        .padding(
            .horizontal,
            reward.jackpot ? HubMetrics.jackpotPaddingX : HubMetrics.coinPaddingX
        )
        .padding(.vertical, HubMetrics.pillPaddingY)
        .background(
            reward.jackpot ? Palette.jackpot.color : .white,
            in: Capsule()
        )
        .overlay(
            Capsule().strokeBorder(
                reward.jackpot ? Color.white : Palette.coinRing.color,
                lineWidth: reward.jackpot ? HubMetrics.jackpotRing : HubMetrics.coinRing)
        )
        .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
        .shadow(
            color: .black.opacity(HubMetrics.shadow.opacity),
            radius: HubMetrics.shadow.swiftUIRadius,
            y: HubMetrics.shadow.y
        )
        .offset(
            x: reward.jackpot ? HubMetrics.jackpotOffset : HubMetrics.coinOffset,
            y: reward.jackpot ? -HubMetrics.jackpotOffset : -HubMetrics.coinOffset
        )
        .accessibilityHidden(true)
    }
}

// MARK: - The wrapping chip row

/// `flex flex-wrap items-center gap-2`, left-aligned (CSS default
/// `justify-start`). `ComponentsWrapRow` (EndButtons) justify-centres its
/// lines, which is right for the end buttons and wrong here, so the wrapping
/// arithmetic is shared (`ComponentsWrapRow.lines`) and only the placement
/// differs.
struct HubWrapRow: SwiftUI.Layout {
    var spacing: CGFloat
    var lineSpacing: CGFloat

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: LayoutSubviews,
        cache: inout ()
    ) -> CGSize {
        let limit: CGFloat = proposal.width ?? .infinity
        let sizes = subviews.indices.map { subviews[$0].sizeThatFits(ProposedViewSize.unspecified) }
        let rows = ComponentsWrapRow.lines(sizes: sizes, maxWidth: limit, spacing: spacing)
        var width: CGFloat = 0
        var height: CGFloat = 0
        for row in rows {
            width = Swift.max(width, row.width)
            height += row.height
        }
        let gaps = CGFloat(Swift.max(0, rows.count - 1)) * lineSpacing
        return CGSize(width: width, height: height + gaps)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: LayoutSubviews,
        cache: inout ()
    ) {
        let sizes = subviews.indices.map { subviews[$0].sizeThatFits(ProposedViewSize.unspecified) }
        let rows = ComponentsWrapRow.lines(sizes: sizes, maxWidth: bounds.width, spacing: spacing)
        var y = bounds.minY
        for row in rows {
            var x = bounds.minX  // justify-start
            for (slot, index) in row.indices.enumerated() {
                let size = row.sizes[slot]
                subviews[index].place(
                    at: CGPoint(x: x, y: y + row.height / 2),  // items-center
                    anchor: UnitPoint.leading,
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }
}
