import { render, screen, fireEvent, within } from "@testing-library/react";
import { useEffect } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ProfileProvider, useProfile } from "../hooks/useProfile";
import type { ExerciseId } from "../types";
import { Shop } from "./Shop";

/* happy-dom has no WAAPI; reduced-motion makes pop()/press() early-return so the
 * Shop never calls Element.animate. */
beforeEach(() => {
  window.localStorage.clear();
  window.matchMedia = ((query: string) => ({
    matches: true,
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent() {
      return false;
    },
  })) as unknown as typeof window.matchMedia;
});

/* happy-dom has neither speechSynthesis nor a playable <audio>; the shop only
 * uses audio as fire-and-forget feedback, so a silent stub keeps tests honest. */
vi.mock("../hooks/useAudio", () => ({
  useAudio: () => ({
    unlock() {},
    pop() {},
    success() {},
    nudge() {},
    oops() {},
    say: () => Promise.resolve(true),
    stop() {},
  }),
}));

/** Boots a child with enough points, then renders the (unicorn) shop. */
function ShopHarness() {
  const { createChild, award, activeId } = useProfile();
  useEffect(() => {
    createChild("Test");
    // Six distinct first-clears of a PAYING exercise (difficulty 1) with no
    // perfect-round bonus = 6 × 10 = 60 pts, plenty for a 40-pt style.
    for (let level = 0; level < 6; level++)
      award("order-syllables" as ExerciseId, level, 0, 1);
  }, [createChild, award]);
  return activeId ? <Shop onBack={() => {}} /> : null;
}

function renderShop() {
  render(
    <ProfileProvider>
      <ShopHarness />
    </ProfileProvider>
  );
}

/* Tile names are anchored (`^Name,`) because during a try-on the buy bar's own
 * label also contains the item name ("Acheter Queue bouclée pour…"). */
const curlyTail = () => screen.getByRole("button", { name: /^Queue bouclée,/ });
const straightTail = () => screen.getByRole("button", { name: /^Queue lisse,/ }); // factory look
const pinkBody = () => screen.getByRole("button", { name: /^Corps rose,/ });
const lilacBody = () => screen.getByRole("button", { name: /^Corps lilas,/ }); // factory look
const swimRing = () => screen.getByRole("button", { name: /^Bouée,/ }); // 75 pts > 60 balance
const buyButton = () => screen.getByRole("button", { name: /^Acheter/ });

/** Tap an unowned tile (try-on), then confirm on the buy bar. */
const buyViaTryOn = (tile: () => HTMLElement) => {
  fireEvent.click(tile());
  fireEvent.click(buyButton());
};

describe("Shop — try-on before buy", () => {
  it("trying on opens the dialog but spends nothing", () => {
    renderShop();

    fireEvent.click(curlyTail());

    // The try-on dialog is up: wallet untouched, nothing equipped yet.
    expect(screen.getByRole("dialog", { name: "Essayer Queue bouclée" })).toBeInTheDocument();
    expect(curlyTail()).toHaveAccessibleName(/en train d'essayer/);
    expect(screen.getByLabelText("60 points")).toBeInTheDocument();
    expect(curlyTail()).not.toHaveAccessibleName(/équipé/);
    expect(buyButton()).toBeEnabled();
  });

  it("the buy button spends, equips, and closes the dialog", () => {
    renderShop();

    buyViaTryOn(curlyTail);

    expect(curlyTail()).toHaveAccessibleName(/équipé/);
    expect(screen.getByLabelText("20 points")).toBeInTheDocument(); // 60 - 40
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("an unaffordable item can be tried on but not bought", () => {
    renderShop();

    fireEvent.click(swimRing()); // 75 pts, balance is 60

    expect(swimRing()).toHaveAccessibleName(/en train d'essayer/);
    expect(screen.getByRole("button", { name: /^Pas encore assez/ })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /^Acheter/ })).toBeNull();
  });

  it("the ✕ button cancels a try-on", () => {
    renderShop();

    fireEvent.click(curlyTail());
    fireEvent.click(screen.getByRole("button", { name: "Ne pas acheter" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(curlyTail()).not.toHaveAccessibleName(/en train d'essayer/);
    expect(screen.getByLabelText("60 points")).toBeInTheDocument();
  });
});

describe("Shop — armoire / magasin zones", () => {
  const zone = (name: string) =>
    screen.getByRole("heading", { name }).closest("section") as HTMLElement;

  it("a bought item moves from the store to the wardrobe", () => {
    renderShop();

    // Unowned: for sale in the store, absent from the wardrobe.
    expect(within(zone("Le magasin")).getByRole("button", { name: /^Queue bouclée,/ })).toBeInTheDocument();
    expect(within(zone("Ton armoire")).queryByRole("button", { name: /^Queue bouclée,/ })).toBeNull();

    buyViaTryOn(curlyTail);

    // Bought: it now lives in the wardrobe, the store no longer sells it.
    expect(within(zone("Ton armoire")).getByRole("button", { name: /^Queue bouclée,/ })).toBeInTheDocument();
    expect(within(zone("Le magasin")).queryByRole("button", { name: /^Queue bouclée,/ })).toBeNull();
  });

  it("factory looks live in the wardrobe", () => {
    renderShop();
    expect(within(zone("Ton armoire")).getByRole("button", { name: /^Queue lisse,/ })).toBeInTheDocument();
  });
});

describe("Shop — factory look returns things to default", () => {
  it("switches a style, then the named default look reverts it", () => {
    renderShop();

    // Factory tail look is shown as owned + in place; there is no "Défaut" tile.
    expect(straightTail()).toHaveAccessibleName(/^Queue lisse, en place$/);
    expect(screen.queryByText("Défaut")).toBeNull();

    // Buy a style → it equips, factory look no longer active.
    buyViaTryOn(curlyTail);
    expect(curlyTail()).toHaveAccessibleName(/équipé/);
    expect(straightTail()).toHaveAccessibleName(/^Queue lisse, à toi$/);

    // Pick the factory look → style reverts, factory look active again.
    fireEvent.click(straightTail());
    expect(straightTail()).toHaveAccessibleName(/^Queue lisse, en place$/);
    expect(curlyTail()).not.toHaveAccessibleName(/équipé/);
  });

  it("returns the body colour to its factory look", () => {
    renderShop();

    // Factory body colour starts in place.
    expect(lilacBody()).toHaveAccessibleName(/^Corps lilas, en place$/);

    // Buy a body colour → equipped, factory look released.
    buyViaTryOn(pinkBody);
    expect(pinkBody()).toHaveAccessibleName(/équipé/);
    expect(lilacBody()).toHaveAccessibleName(/^Corps lilas, à toi$/);

    // Pick the factory look → body colour removed, back to default.
    fireEvent.click(lilacBody());
    expect(lilacBody()).toHaveAccessibleName(/^Corps lilas, en place$/);
    expect(pinkBody()).not.toHaveAccessibleName(/équipé/);
  });
});
