interface Props {
  onMenu: () => void;
  onNext: () => void;
}

/**
 * End-of-run choices, shared by every exercise's finished screen: leave to the
 * hub, or keep going to the next level. "Suivant" is the primary (green) button.
 */
export function EndButtons({ onMenu, onNext }: Props) {
  return (
    <div className="mt-1 flex flex-wrap items-center justify-center gap-3">
      <button
        onPointerDown={onMenu}
        className="rounded-full bg-white/80 px-7 py-4 text-xl font-extrabold text-[#5A3A1E] shadow [touch-action:none]"
      >
        🏠 Menu
      </button>
      <button
        onPointerDown={onNext}
        className="rounded-full bg-[#66BB6A] px-9 py-4 text-2xl font-extrabold text-white [touch-action:none]"
        style={{ boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)" }}
      >
        🎉 Suivant
      </button>
    </div>
  );
}
