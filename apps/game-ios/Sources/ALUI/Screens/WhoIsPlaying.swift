import QuartzCore
import SwiftUI

import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/WhoIsPlaying.tsx` — « Qui joue ? ».                          */
/*                                                                             */
/* Siblings share the device: pick who you are, or make a new profile. A child's */
/* avatar IS their current mascot (or the owl until they have chosen a species). */
/* Selecting sets the active player; creating drops the new child straight into  */
/* the species picker (`chosen == false`, gate 4 in the router).                */
/*                                                                             */
/* ── INVARIANT 10 LIVES ON THIS SCREEN ─────────────────────────────────────── */
/* `ChildProfile.name` is a six-year-old's first name. This screen DISPLAYS it  */
/* in four places (the card, the two accessibility labels, and both alert       */
/* messages) and must never do anything else with it. It may not reach a        */
/* transport, a log, a telemetry property, an analytics identifier or a crash   */
/* report. The structural guards, which this file relies on rather than         */
/* re-implementing:                                                            */
/*                                                                             */
/*   • `WireChild` (ALCore/Sync/Wire.swift) has NO name field, so the sync      */
/*     payload cannot carry one — stripping is a property of the type.          */
/*   • `TelemetryProps` is eight optional numbers plus a closed `ExerciseId`    */
/*     enum, with no `String` escape hatch (D12), so `track(…)` has no          */
/*     parameter a name would fit in.                                          */
/*   • `Telemetry.reportError` truncates its message and attaches NOTHING from  */
/*     app state, deliberately.                                                */
/*                                                                             */
/* This file therefore emits no telemetry at all. If a property is ever needed  */
/* here, that is a report, not an edit. `WhoIsPlayingTests` drives the whole    */
/* roster flow with a recording transport and asserts the name appears in the   */
/* display labels and in NO sent byte.                                         */
/*                                                                             */
/* ── The two browser dialogs ───────────────────────────────────────────────── */
/* Rename is `window.prompt(…, c.name)`, delete is `window.confirm(…)`. Native  */
/* equivalents are an `.alert` with a `TextField` and an `.alert` with a        */
/* `.destructive` button. Same strings (`Copy.WhoIsPlaying.renamePrompt` /      */
/* `deleteConfirm`), native presentation. The browser dialogs are synchronous   */
/* and blocking and the SwiftUI ones are not; nothing observable differs        */
/* because nothing runs between the call and the answer on the web either.      */
/* `RosterAction` below is the whole decision, extracted so `swift test` can    */
/* prove that CANCELLING calls nothing — `prompt` returning `null` is the one   */
/* branch that is easy to get wrong and impossible to see.                     */
/*                                                                             */
/* The alert BUTTON TITLES do not exist in the PWA (they are browser chrome).   */
/* « OK » / « Annuler » / « Supprimer » are chosen here and flagged for review; */
/* they are not in `Copy.swift` because they are not ported copy.               */
/* -------------------------------------------------------------------------- */

// MARK: - The rename / delete decision (pure, host-tested)

/// Which dialog is open, and for whom.
public struct RosterPrompt: Equatable, Sendable, Identifiable {
    public enum Kind: Equatable, Sendable {
        /// `window.prompt("Nouveau prénom pour {name} ?", name)`.
        case rename
        /// `window.confirm("Supprimer le profil de {name} ? Tout sera perdu.")`.
        case delete
    }

    public let kind: Kind
    public let childId: String
    public let childName: String

    public init(kind: Kind, childId: String, childName: String) {
        self.kind = kind
        self.childId = childId
        self.childName = childName
    }

    public var id: String { "\(kind)-\(childId)" }

    /// The exact string the browser dialog showed.
    public var message: String {
        switch kind {
        case .rename: return Copy.WhoIsPlaying.renamePrompt(childName)
        case .delete: return Copy.WhoIsPlaying.deleteConfirm(childName)
        }
    }
}

/// What a dismissed dialog asks the store to do.
public enum RosterAction: Equatable, Sendable {
    /// Cancelled. **The store is not touched** — `prompt` returning `null` means
    /// `renameChild` is never called, and a declined `confirm` deletes nothing.
    case none
    case rename(id: String, name: String)
    case delete(id: String)
}

/// The dialog outcome → the action. Pure.
///
/// Note what is NOT here: emptiness. `window.prompt` returning `""` still calls
/// `renameChild(c.id, "")`, and the hook ignores it (`if (!trimmed) return`).
/// `ProfileStore.renameChild` does the same, so the empty case is deliberately
/// left to the store rather than second-guessed here.
public func rosterAction(for prompt: RosterPrompt?, confirmed: Bool, text: String) -> RosterAction {
    guard let prompt, confirmed else { return .none }
    switch prompt.kind {
    case .rename: return .rename(id: prompt.childId, name: text)
    case .delete: return .delete(id: prompt.childId)
    }
}

/// Runs an action through the single mutation choke point. Synchronous, like
/// every `ProfileStore` method.
@MainActor
public func applyRosterAction(_ action: RosterAction, to store: ProfileStore) {
    switch action {
    case .none:
        break
    case .rename(let id, let name):
        store.renameChild(id: id, name: name)
    case .delete(let id):
        store.deleteChild(id: id)
    }
}

// MARK: - The card's press animation

/// `src/shop/anim.ts`'s `press`, which `ChildCard` uses — and which is NOT
/// `Tile`'s.
///
/// Two differences, both measured rather than assumed:
///   • the scale bottoms out at **0.94**, not `Tile`'s 0.9;
///   • it **is** reduced-motion gated (`anim.ts:32` returns early on
///     `matchMedia("(prefers-reduced-motion: reduce)")`), where `Tile.tsx` has
///     no `matchMedia` call at all. D29's table lists "shop press/pop" under
///     gated for exactly this reason.
///
/// `Anim` deliberately carries no shop press (it was written for the exercise
/// tiles), so it lives here. If the shop package lands one, these two should be
/// merged — reported rather than pre-empted.
enum RosterPress {
    static let scaleValues: [Double] = [1, 0.94, 1]
    static let duration: CFTimeInterval = 0.13
    static let key = "ALRosterPress"

    @MainActor
    static func press(_ layer: CALayer?, reduceMotion: ReduceMotionSource) {
        guard let layer, !reduceMotion.isReduced else { return }
        let animation = CAKeyframeAnimation(keyPath: "transform.scale")
        animation.values = scaleValues
        animation.keyTimes = [0, 0.5, 1]
        animation.timingFunctions = [Anim.cssEaseOut(), Anim.cssEaseOut()]
        animation.duration = duration
        layer.add(animation, forKey: key)
    }
}

// MARK: - Authored metrics

public enum WhoIsPlayingMetrics {
    /// `min-h-[620px] … px-6 pb-10 pt-10`, `gap-6`, `rounded-3xl`.
    public static let stagePaddingX: CGFloat = 24
    public static let stagePaddingTop: CGFloat = 40
    public static let stagePaddingBottom: CGFloat = 40
    public static let stageGap: CGFloat = 24
    public static let cornerRadius: CGFloat = 24

    /// `max-w-md` on the grid, `max-w-sm` on the name form.
    public static let gridMaxWidth: CGFloat = 448
    public static let formMaxWidth: CGFloat = 384
    /// `grid-cols-2 gap-4`.
    public static let gridColumns = 2
    public static let gridGap: CGFloat = 16

    /// `rounded-3xl p-4` + `gap-2` inside a ChildCard; `h-24` avatar row;
    /// `0 8px 18px rgba(0,0,0,0.10)`.
    public static let cardPadding: CGFloat = 16
    public static let cardGap: CGFloat = 8
    public static let avatarRowHeight: CGFloat = 96
    public static let avatarSize: CGFloat = 84
    /// The owl stand-in before a species is chosen: `fontSize: size * 0.72`.
    public static let owlRatio: CGFloat = 0.72
    public static let cardShadow = CSSShadow(y: 8, blur: 18, opacity: 0.10)

    /// `h-9 w-9` corner buttons at `-left-2 -top-2` / `-right-2 -top-2`.
    public static let cornerButtonSide: CGFloat = 36
    public static let cornerButtonOffset: CGFloat = 8
    public static let cornerButtonBorder: CGFloat = 2

    /// The dashed "Nouveau" card: `3px dashed #E4A15E`, `minHeight: 150`.
    public static let newCardBorder: CGFloat = 3
    public static let newCardMinHeight: CGFloat = 150
    public static let newCardGlyphSize: CGFloat = 46

    /// `clamp(48px,15vw,76px)` 👋 and `clamp(24px,7vw,36px)` headline on the form.
    public static let waveSize = FluidSpec(min: 48, vw: 15, max: 76)
    public static let askNameSize = FluidSpec(min: 24, vw: 7, max: 36)
    /// The name field: `rounded-3xl px-6 py-4 text-2xl`, `bg-white/95`.
    public static let fieldPaddingX: CGFloat = 24
    public static let fieldPaddingY: CGFloat = 16
    /// « C'est parti ! 🎉 »: `px-8 py-4 text-2xl`, `0 8px 0 #43A047`,
    /// `0 14px 24px rgba(0,0,0,0.2)`, `disabled:opacity-40`.
    public static let goPaddingX: CGFloat = 32
    public static let goPaddingY: CGFloat = 16
    public static let goLipDrop: CGFloat = 8
    public static let goSoftShadow = CSSShadow(y: 14, blur: 24, opacity: 0.2)
    public static let disabledOpacity: Double = 0.4

    /// Tailwind `shadow` on the header toggle, the name field and the corner
    /// buttons.
    public static let plainShadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)
}

// MARK: - The screen

@MainActor
public struct WhoIsPlayingView: View {

    @Environment(ProfileStore.self) private var store
    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    /// `useState(children.length === 0)` — resolved from the roster on the FIRST
    /// render and sticky from then on. Nil means "not resolved yet", so the very
    /// first body evaluation already reads the roster (no empty-device flash);
    /// `onAppear` pins it.
    ///
    /// Sticky matters: deleting the last child in edit mode leaves the TSX's
    /// `creating` at `false` and shows the grid with only the "Nouveau" card. A
    /// plain `store.children.isEmpty` would instead jump into the name form.
    /// Ported as-is.
    @State private var creating: Bool?
    @State private var editing = false
    @State private var prompt: RosterPrompt?
    @State private var renameText = ""

    public init() {}

    private var isCreating: Bool { creating ?? store.children.isEmpty }

    public var body: some View {
        VStack(spacing: WhoIsPlayingMetrics.stageGap) {
            if isCreating {
                NewProfileForm(
                    // `onCancel = children.length ? … : null` — an empty device
                    // has nowhere to go back to, so there is no « Retour ».
                    onCancel: store.children.isEmpty ? nil : { creating = false },
                    onSubmit: { name in store.createChild(name: name) }
                )
            } else {
                header
                grid
            }
        }
        .frame(maxWidth: .infinity, minHeight: Shell.minimumScreenHeight, alignment: .top)
        .padding(.horizontal, WhoIsPlayingMetrics.stagePaddingX)
        .padding(.top, WhoIsPlayingMetrics.stagePaddingTop)
        .padding(.bottom, WhoIsPlayingMetrics.stagePaddingBottom)
        .clipShape(RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius))
        .fontDesign(.rounded)
        .onAppear { if creating == nil { creating = store.children.isEmpty } }
        .alert(
            Text(verbatim: prompt?.message ?? ""),
            isPresented: Binding(
                get: { prompt?.kind == .rename },
                set: { if !$0 { prompt = nil } }
            )
        ) {
            TextField(Copy.WhoIsPlaying.namePlaceholder, text: $renameText)
            Button(Self.alertConfirm) { finish(confirmed: true) }
            Button(Self.alertCancel, role: .cancel) { finish(confirmed: false) }
        }
        .alert(
            Text(verbatim: prompt?.message ?? ""),
            isPresented: Binding(
                get: { prompt?.kind == .delete },
                set: { if !$0 { prompt = nil } }
            )
        ) {
            Button(Self.alertDelete, role: .destructive) { finish(confirmed: true) }
            Button(Self.alertCancel, role: .cancel) { finish(confirmed: false) }
        }
        // D42. The form branch autofocuses its name field, so a keyboard is up
        // from the moment this screen appears on an empty device — and the stage
        // cannot compress below 620, so SwiftUI's avoidance slid the 👋 under the
        // notch. Scrolling absorbs the inset the way mobile Safari does.
        //
        // Gated on `isCreating` and NOT hoisted to the whole screen: the grid
        // branch's `ChildCard` is a `LayerHost`, and a `UIScrollView` over it
        // would delay touch-down feedback (invariant 1). The two branches are
        // mutually exclusive, so the wrapper never sees a tile.
        .alPageScroll(enabled: isCreating)
        // OUTSIDE the scroll view (D51) — see Picker's note.
        .stageWash(Palette.stageAdult)
    }

    /// Alert button titles. NOT ported copy — the PWA never had them (browser
    /// chrome). Flagged for review; deliberately not in `Copy.swift`.
    static let alertConfirm = "OK"
    static let alertCancel = "Annuler"
    static let alertDelete = "Supprimer"

    private func finish(confirmed: Bool) {
        applyRosterAction(
            rosterAction(for: prompt, confirmed: confirmed, text: renameText),
            to: store
        )
        prompt = nil
        renameText = ""
    }

    private var header: some View {
        HStack {
            Text(verbatim: Copy.WhoIsPlaying.heading)
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                .foregroundStyle(Palette.inkSoft.color)

            Spacer(minLength: 0)

            Button { editing.toggle() } label: {
                Text(verbatim: editing ? Copy.WhoIsPlaying.editDone : Copy.WhoIsPlaying.edit)
                    .font(Typography.rounded(Typography.Size.base, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)
                    .padding(.horizontal, 16)  // px-4
                    .padding(.vertical, 8)  // py-2
                    .background(Color.white.opacity(Palette.White.o80), in: Capsule())
                    .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                    .shadow(
                        color: .black.opacity(WhoIsPlayingMetrics.plainShadow.opacity),
                        radius: WhoIsPlayingMetrics.plainShadow.swiftUIRadius,
                        y: WhoIsPlayingMetrics.plainShadow.y
                    )
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
    }

    private var grid: some View {
        LazyVGrid(
            columns: Array(
                repeating: GridItem(.flexible(), spacing: WhoIsPlayingMetrics.gridGap),
                count: WhoIsPlayingMetrics.gridColumns
            ),
            spacing: WhoIsPlayingMetrics.gridGap
        ) {
            // Invariant 5's sibling: the roster is never gated either. Every
            // child on the device is listed, unconditionally, in roster order.
            ForEach(store.children, id: \.id) { child in
                ChildCard(
                    child: child,
                    editing: editing,
                    reduceMotion: reduceMotion,
                    onPick: { store.selectChild(id: child.id) },
                    onRename: { openRename(child) },
                    onDelete: { openDelete(child) }
                )
            }

            newProfileCard
        }
        .frame(maxWidth: WhoIsPlayingMetrics.gridMaxWidth)
    }

    private func openRename(_ child: ChildProfile) {
        // `window.prompt(msg, c.name)` — the field is SEEDED with the current
        // name, which is why a parent fixing a typo does not retype it.
        renameText = child.name
        prompt = RosterPrompt(kind: .rename, childId: child.id, childName: child.name)
    }

    private func openDelete(_ child: ChildProfile) {
        prompt = RosterPrompt(kind: .delete, childId: child.id, childName: child.name)
    }

    private var newProfileCard: some View {
        Button { creating = true } label: {
            VStack(spacing: WhoIsPlayingMetrics.cardGap) {
                Text(verbatim: Copy.WhoIsPlaying.newProfileGlyph)
                    .font(.system(size: WhoIsPlayingMetrics.newCardGlyphSize))
                    .accessibilityHidden(true)
                Text(verbatim: Copy.WhoIsPlaying.newProfileLabel)
                    .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)
            }
            .padding(WhoIsPlayingMetrics.cardPadding)
            .frame(maxWidth: .infinity, minHeight: WhoIsPlayingMetrics.newCardMinHeight)
            .background {
                RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius)
                    .fill(Color.white.opacity(Palette.White.o55))
                    .overlay {
                        RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius)
                            .strokeBorder(
                                Palette.slotDashed.color,
                                style: StrokeStyle(
                                    lineWidth: WhoIsPlayingMetrics.newCardBorder,
                                    dash: [8, 6]
                                )
                            )
                    }
            }
            .contentShape(RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: Copy.WhoIsPlaying.newProfile))
    }
}

// MARK: - The avatar

/// `!chosen` → 🦉 at `size * 0.72`; otherwise the child's CURRENT mascot.
///
/// Reads `p.species[p.current].config`, not the flattened `ProfileView.config`:
/// the roster shows every child, and only the active one has a `ProfileView`.
struct RosterAvatar: View {
    let profile: PersistedProfile
    let size: CGFloat

    var body: some View {
        if profile.chosen {
            MascotRigView(config: profile.species[profile.current].config, mood: .idle, size: size)
        } else {
            Text(verbatim: Copy.WhoIsPlaying.owlAvatar)
                .font(.system(size: size * WhoIsPlayingMetrics.owlRatio))
                .accessibilityHidden(true)
        }
    }
}

// MARK: - One child

@MainActor
struct ChildCard: View {
    let child: ChildProfile
    let editing: Bool
    let reduceMotion: ReduceMotionSource
    let onPick: () -> Void
    let onRename: () -> Void
    let onDelete: () -> Void

    @State private var handle = LayerHandle()

    /// `aria-label={editing ? \`Renommer ${name}\` : \`Jouer avec ${name}\`}`.
    /// The child's name is IN this label, and that is correct — a screen reader
    /// is on the device. It never leaves it (invariant 10).
    var label: String {
        editing ? Copy.WhoIsPlaying.rename(child.name) : Copy.WhoIsPlaying.play(with: child.name)
    }

    var body: some View {
        LayerHost(handle: handle) {
            VStack(spacing: WhoIsPlayingMetrics.cardGap) {
                RosterAvatar(profile: child.profile, size: WhoIsPlayingMetrics.avatarSize)
                    .frame(height: WhoIsPlayingMetrics.avatarRowHeight)

                Text(verbatim: child.name)
                    .font(Typography.rounded(Typography.Size.xl, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(WhoIsPlayingMetrics.cardPadding)
            .frame(maxWidth: .infinity)
            .background {
                RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius)
                    .fill(Color.white.opacity(Palette.White.o92))
                    .shadow(
                        color: .black.opacity(WhoIsPlayingMetrics.cardShadow.opacity),
                        radius: WhoIsPlayingMetrics.cardShadow.swiftUIRadius,
                        y: WhoIsPlayingMetrics.cardShadow.y
                    )
            }
        }
        .contentShape(RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius))
        // `onPointerDown` fires the squish; `onClick` dispatches. `touchDown`'s
        // `onUp(inside:)` IS the web's click test (pointer lifted on the
        // element), so the two halves land in the right order without the tap
        // being deferred to touch-up.
        .touchDown(
            { RosterPress.press(handle.layer, reduceMotion: reduceMotion) },
            onUp: { inside in
                guard inside else { return }
                if editing { onRename() } else { onPick() }
            }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(.isButton)
        .overlay(alignment: .topLeading) {
            if editing {
                cornerButton(
                    glyph: Copy.WhoIsPlaying.renameGlyph,
                    label: Copy.WhoIsPlaying.rename(child.name),
                    fontSize: Typography.Size.base,
                    fill: Color.white,
                    ink: Palette.ink.color,
                    border: Palette.slotDashed.color,
                    action: onRename
                )
                .offset(
                    x: -WhoIsPlayingMetrics.cornerButtonOffset,
                    y: -WhoIsPlayingMetrics.cornerButtonOffset)
            }
        }
        .overlay(alignment: .topTrailing) {
            if editing {
                cornerButton(
                    glyph: Copy.WhoIsPlaying.deleteGlyph,
                    label: Copy.WhoIsPlaying.delete(child.name),
                    fontSize: Typography.Size.lg,
                    fill: Palette.destructive.color,
                    ink: Color.white,
                    border: Color.white,
                    action: onDelete
                )
                .offset(
                    x: WhoIsPlayingMetrics.cornerButtonOffset,
                    y: -WhoIsPlayingMetrics.cornerButtonOffset)
            }
        }
    }

    private func cornerButton(
        glyph: String,
        label: String,
        fontSize: CGFloat,
        fill: Color,
        ink: Color,
        border: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(verbatim: glyph)
                .font(Typography.rounded(fontSize, Typography.Weight.black))
                .foregroundStyle(ink)
                .frame(
                    width: WhoIsPlayingMetrics.cornerButtonSide,
                    height: WhoIsPlayingMetrics.cornerButtonSide)
                .background {
                    Circle()
                        .fill(fill)
                        .overlay {
                            Circle()
                                .strokeBorder(
                                    border, lineWidth: WhoIsPlayingMetrics.cornerButtonBorder)
                        }
                        .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                        .shadow(
                            color: .black.opacity(WhoIsPlayingMetrics.plainShadow.opacity),
                            radius: WhoIsPlayingMetrics.plainShadow.swiftUIRadius,
                            y: WhoIsPlayingMetrics.plainShadow.y
                        )
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: label))
    }
}

// MARK: - « Comment tu t'appelles ? »

@MainActor
struct NewProfileForm: View {
    /// nil on an empty device — there is nothing to go back to.
    let onCancel: (() -> Void)?
    let onSubmit: (String) -> Void

    @Environment(\.alViewportWidth) private var viewport
    @State private var name = ""
    @FocusState private var focused: Bool

    /// `disabled={!ok}` where `ok = name.trim().length > 0`.
    var canSubmit: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(spacing: WhoIsPlayingMetrics.stageGap - 4) {  // gap-5
            Text(verbatim: Copy.WhoIsPlaying.newProfileWave)
                .font(.system(size: WhoIsPlayingMetrics.waveSize.resolve(viewport: viewport)))
                .accessibilityHidden(true)

            Text(verbatim: Copy.WhoIsPlaying.askName)
                .font(
                    Typography.rounded(
                        WhoIsPlayingMetrics.askNameSize.resolve(viewport: viewport),
                        Typography.Weight.black)
                )
                .foregroundStyle(Palette.ink.color)
                .multilineTextAlignment(.center)

            nameField

            submitButton

            if let onCancel {
                Button(action: onCancel) {
                    Text(verbatim: Copy.WhoIsPlaying.back)
                        .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
                        .foregroundStyle(Palette.inkSoft.color)
                        .underline()
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: WhoIsPlayingMetrics.formMaxWidth)
    }

    private var nameField: some View {
        TextField(Copy.WhoIsPlaying.namePlaceholder, text: $name)
            .textFieldStyle(.plain)
            .multilineTextAlignment(.center)
            .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.black))
            .foregroundStyle(Palette.ink.color)
            .focused($focused)
            .submitLabel(.done)
            .onSubmit(submit)
            // `maxLength={14}`. The DOM counts UTF-16 code units and `prefix`
            // counts grapheme clusters; they differ only for an emoji or a
            // combining sequence, where the Swift behaviour cannot split one in
            // half. Recorded, not fudged.
            .onChange(of: name) { _, new in
                let clamped = String(new.prefix(Copy.WhoIsPlaying.nameMaxLength))
                if clamped != new { name = clamped }
            }
            .padding(.horizontal, WhoIsPlayingMetrics.fieldPaddingX)
            .padding(.vertical, WhoIsPlayingMetrics.fieldPaddingY)
            .frame(maxWidth: .infinity)
            .background {
                RoundedRectangle(cornerRadius: WhoIsPlayingMetrics.cornerRadius)
                    .fill(Color.white.opacity(Palette.White.o95))
                    .shadow(
                        color: .black.opacity(WhoIsPlayingMetrics.plainShadow.opacity),
                        radius: WhoIsPlayingMetrics.plainShadow.swiftUIRadius,
                        y: WhoIsPlayingMetrics.plainShadow.y
                    )
            }
            .accessibilityLabel(Text(verbatim: Copy.WhoIsPlaying.namePlaceholder))
            .onAppear { focused = true }  // autoFocus
    }

    private var submitButton: some View {
        Button(action: submit) {
            Text(verbatim: Copy.WhoIsPlaying.go)
                .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.extrabold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, WhoIsPlayingMetrics.goPaddingX)
                .padding(.vertical, WhoIsPlayingMetrics.goPaddingY)
                .background {
                    liftedCapsule(
                        fill: AnyShapeStyle(Palette.green.color),
                        lip: Palette.greenLip.color,
                        drop: WhoIsPlayingMetrics.goLipDrop,
                        soft: WhoIsPlayingMetrics.goSoftShadow
                    )
                }
                .contentShape(Capsule())
                // CSS `opacity` composites the element as a GROUP; SwiftUI's fades
                // every layer separately, so the lifted capsule's lip showed
                // THROUGH its own face — one button rendered as two.
                .compositingGroup()
                .opacity(canSubmit ? 1 : WhoIsPlayingMetrics.disabledOpacity)
        }
        .buttonStyle(.plain)
        .disabled(!canSubmit)
    }

    /// `createChild(name)` — the **untrimmed** value, even though the button is
    /// gated on the trimmed one. `ProfileStore.createChild` trims and falls back
    /// to « Joueur »; the asymmetry is in the TSX and is ported as-is.
    private func submit() {
        guard canSubmit else { return }
        onSubmit(name)
    }
}

extension Copy.WhoIsPlaying {
    /// The 👋 above « Comment tu t'appelles ? ». `Copy.swift` carries the same
    /// glyph for the Onboarding screen (`Copy.Onboarding.wave`) but not for this
    /// one; spelled here rather than reaching across screens, and reported for
    /// promotion.
    public static let newProfileWave = "👋"
}
