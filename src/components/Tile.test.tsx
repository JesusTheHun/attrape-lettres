import { render, screen, fireEvent } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Tile } from "./Tile";

// happy-dom ships no WAAPI; stub a no-op so the press/shake animations don't
// throw when the handlers fire.
beforeEach(() => {
  Element.prototype.animate = (() => ({})) as unknown as typeof Element.prototype.animate;
});

describe("Tile — hearing a tile is not picking it", () => {
  it("Écouter previews without committing the pick", () => {
    const onPick = vi.fn(() => "accept" as const);
    const onPreview = vi.fn();
    render(
      <Tile
        bg="#fff"
        ink="#000"
        onPick={onPick}
        onPreview={onPreview}
        previewLabel="Écouter ma"
        ariaLabel="Syllabe ma"
      >
        ma
      </Tile>
    );

    fireEvent.pointerDown(screen.getByRole("button", { name: "Écouter ma" }));
    expect(onPreview).toHaveBeenCalledTimes(1);
    expect(onPick).not.toHaveBeenCalled();

    fireEvent.pointerDown(screen.getByRole("button", { name: "Syllabe ma" }));
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPreview).toHaveBeenCalledTimes(1); // picking didn't re-fire preview
  });

  it("renders no Écouter button when onPreview is absent", () => {
    render(
      <Tile bg="#fff" ink="#000" onPick={() => "accept"} ariaLabel="Lettre A">
        A
      </Tile>
    );
    expect(screen.queryByRole("button", { name: /Écouter/ })).toBeNull();
  });

  it("disables the Écouter button when the tile is disabled", () => {
    const onPreview = vi.fn();
    render(
      <Tile
        bg="#fff"
        ink="#000"
        disabled
        onPick={() => "accept"}
        onPreview={onPreview}
        previewLabel="Écouter A"
        ariaLabel="Lettre A"
      >
        A
      </Tile>
    );
    const listen = screen.getByRole("button", { name: "Écouter A" });
    expect(listen).toBeDisabled();
    fireEvent.pointerDown(listen);
    expect(onPreview).not.toHaveBeenCalled();
  });
});
