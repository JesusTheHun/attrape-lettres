import { describe, it, expect, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { ProfileProvider, useProfile } from "./useProfile";
import { REWARD_CURVE } from "../rewards";
import type { CustomizationOption } from "../types";

const wrapper = ({ children }: { children: ReactNode }) => (
  <ProfileProvider>{children}</ProfileProvider>
);

const render = () => renderHook(() => useProfile(), { wrapper });

const HORN: CustomizationOption = {
  id: "unicorn.horn.rainbow",
  species: "unicorn",
  category: "color",
  slot: "hornColor",
  value: "#F0A",
  name: "Corne arc-en-ciel",
  cost: 4,
};

beforeEach(() => {
  localStorage.clear();
});

describe("useProfile — roster gate", () => {
  it("starts with no children and no active player", () => {
    const { result } = render();
    expect(result.current.children).toHaveLength(0);
    expect(result.current.activeId).toBeNull();
    // The exposed profile is a safe default until someone is playing.
    expect(result.current.profile.chosen).toBe(false);
    expect(result.current.profile.balance).toBe(0);
  });

  it("createChild adds a player and makes them active (species unchosen)", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    expect(result.current.children).toHaveLength(1);
    expect(result.current.activeId).not.toBeNull();
    expect(result.current.profile.chosen).toBe(false);
  });

  it("switchChild returns to the welcome screen; selectChild resumes", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    const id = result.current.activeId!;
    act(() => result.current.switchChild());
    expect(result.current.activeId).toBeNull();
    act(() => result.current.selectChild(id));
    expect(result.current.activeId).toBe(id);
  });
});

describe("useProfile — points (per active child)", () => {
  it("award grants the curve value and decays on repeat", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    let first = 0;
    let second = 0;
    act(() => {
      first = result.current.award("first-letter", 1);
    });
    act(() => {
      second = result.current.award("first-letter", 1);
    });
    expect(first).toBe(REWARD_CURVE[0]);
    expect(second).toBe(REWARD_CURVE[1]);
    expect(result.current.profile.balance).toBe(REWARD_CURVE[0] + REWARD_CURVE[1]);
  });

  it("spend fails when unaffordable and succeeds otherwise", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    let ok = true;
    act(() => {
      ok = result.current.spend(5);
    });
    expect(ok).toBe(false);
    act(() => {
      result.current.award("first-letter", 1); // +10
    });
    act(() => {
      ok = result.current.spend(5);
    });
    expect(ok).toBe(true);
    expect(result.current.profile.balance).toBe(REWARD_CURVE[0] - 5);
  });

  it("buy debits once, then re-equips free when already owned", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    act(() => {
      result.current.award("first-letter", 1); // +10
    });
    let bought = false;
    act(() => {
      bought = result.current.buy(HORN);
    });
    expect(bought).toBe(true);
    expect(result.current.profile.owned).toContain(HORN.id);
    expect(result.current.profile.config.colors.hornColor).toBe("#F0A");
    const afterFirst = result.current.profile.balance;
    act(() => {
      result.current.buy(HORN); // owned -> free re-equip
    });
    expect(result.current.profile.balance).toBe(afterFirst);
  });
});

describe("useProfile — non-destructive mascot switch", () => {
  it("keeps each species' growth/look; stars stay global", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    act(() => {
      result.current.award("first-letter", 1); // +10, global to the child
      result.current.chooseSpecies("unicorn");
    });
    act(() => {
      result.current.buy(HORN); // unicorn owns a horn colour
      result.current.setConfig((c) => ({ ...c, stage: 3 }));
    });
    const starsAfterBuy = result.current.profile.balance;

    // Switch to the fox: it's a fresh baby, but stars are unchanged.
    act(() => result.current.chooseSpecies("fox"));
    expect(result.current.profile.config.species).toBe("fox");
    expect(result.current.profile.config.stage).toBe(0);
    expect(result.current.profile.owned).not.toContain(HORN.id);
    expect(result.current.profile.balance).toBe(starsAfterBuy);

    // Switch back to the unicorn: everything is exactly as we left it.
    act(() => result.current.chooseSpecies("unicorn"));
    expect(result.current.profile.config.stage).toBe(3);
    expect(result.current.profile.config.colors.hornColor).toBe("#F0A");
    expect(result.current.profile.owned).toContain(HORN.id);
  });
});

describe("useProfile — siblings are isolated", () => {
  it("each child keeps their own stars and progress", () => {
    const { result } = render();
    act(() => result.current.createChild("Léa"));
    const lea = result.current.activeId!;
    act(() => {
      result.current.award("first-letter", 1); // Léa: +10
    });

    act(() => result.current.createChild("Tom")); // Tom becomes active, fresh
    expect(result.current.profile.balance).toBe(0);

    act(() => result.current.selectChild(lea));
    expect(result.current.profile.balance).toBe(REWARD_CURVE[0]);
  });
});

describe("useProfile — persistence", () => {
  it("persists the roster and rehydrates a fresh provider", () => {
    const { result, unmount } = render();
    act(() => result.current.createChild("Léa"));
    act(() => {
      result.current.award("first-letter", 1);
      result.current.chooseSpecies("fox");
    });
    unmount();

    const { result: reloaded } = render();
    expect(reloaded.current.children).toHaveLength(1);
    expect(reloaded.current.activeId).not.toBeNull();
    expect(reloaded.current.profile.chosen).toBe(true);
    expect(reloaded.current.profile.config.species).toBe("fox");
    expect(reloaded.current.profile.balance).toBe(REWARD_CURVE[0]);
  });
});
