/**
 * The picture for a word: a dedicated illustration when the word carries one
 * (`img`), otherwise the emoji glyph. We reach for `img` only where the closest
 * emoji misrepresents the word (a cupcake for « macaron », a hut for « igloo »…);
 * `emoji` stays as the text fallback so nothing ever renders blank.
 *
 * `size` drives both the emoji font-size and the image box, so a caller swaps a
 * bare emoji for <WordIcon> without touching its layout. Decorative by default
 * (aria-hidden / empty alt) — every call site already labels the word in text or
 * an aria-label on the surrounding tile/button.
 */
export function WordIcon({
  emoji,
  img,
  size,
  alt = "",
}: {
  emoji: string;
  img?: string;
  size: string | number;
  alt?: string;
}) {
  if (img)
    return (
      <img
        src={img}
        alt={alt}
        draggable={false}
        className="select-none"
        style={{ width: size, height: size, objectFit: "contain", display: "block" }}
      />
    );
  return (
    <span aria-hidden style={{ fontSize: size, lineHeight: 1.1, display: "block" }}>
      {emoji}
    </span>
  );
}
