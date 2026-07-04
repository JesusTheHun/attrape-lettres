import { render, screen, fireEvent } from "@testing-library/react";
import { useEffect } from "react";
import { beforeEach, describe, expect, it } from "vitest";
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

/** Boots a child with enough points, then renders the (unicorn) shop. */
function ShopHarness() {
  const { createChild, award, activeId } = useProfile();
  useEffect(() => {
    createChild("Test");
    // Six distinct first-clears = 60 pts, plenty for a 40-pt style.
    for (let level = 0; level < 6; level++) award("first-letter" as ExerciseId, level);
  }, [createChild, award]);
  return activeId ? <Shop onBack={() => {}} /> : null;
}

const curlyTail = () => screen.getByRole("button", { name: /Queue bouclée/ });
const straightTail = () => screen.getByRole("button", { name: /Queue lisse/ }); // factory look
const pinkBody = () => screen.getByRole("button", { name: /Corps rose/ });
const lilacBody = () => screen.getByRole("button", { name: /Corps lilas/ }); // factory look

describe("Shop — factory look returns things to default", () => {
  it("switches a style, then the named default look reverts it", () => {
    render(
      <ProfileProvider>
        <ShopHarness />
      </ProfileProvider>
    );

    // Factory tail look is shown as owned + in place; there is no "Défaut" tile.
    expect(straightTail()).toHaveAccessibleName(/^Queue lisse, en place$/);
    expect(screen.queryByText("Défaut")).toBeNull();

    // Pick a style → it equips, factory look no longer active.
    fireEvent.click(curlyTail());
    expect(curlyTail()).toHaveAccessibleName(/équipé/);
    expect(straightTail()).toHaveAccessibleName(/^Queue lisse, à toi$/);

    // Pick the factory look → style reverts, factory look active again.
    fireEvent.click(straightTail());
    expect(straightTail()).toHaveAccessibleName(/^Queue lisse, en place$/);
    expect(curlyTail()).not.toHaveAccessibleName(/équipé/);
  });

  it("returns the body colour to its factory look", () => {
    render(
      <ProfileProvider>
        <ShopHarness />
      </ProfileProvider>
    );

    // Factory body colour starts in place.
    expect(lilacBody()).toHaveAccessibleName(/^Corps lilas, en place$/);

    // Buy a body colour → equipped, factory look released.
    fireEvent.click(pinkBody());
    expect(pinkBody()).toHaveAccessibleName(/équipé/);
    expect(lilacBody()).toHaveAccessibleName(/^Corps lilas, à toi$/);

    // Pick the factory look → body colour removed, back to default.
    fireEvent.click(lilacBody());
    expect(lilacBody()).toHaveAccessibleName(/^Corps lilas, en place$/);
    expect(pinkBody()).not.toHaveAccessibleName(/équipé/);
  });
});
