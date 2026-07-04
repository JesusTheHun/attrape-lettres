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

beforeEach(() => {
  localStorage.clear();
});

describe("useProfile", () => {
  it("starts empty and unchosen", () => {
    const { result } = render();
    expect(result.current.profile.balance).toBe(0);
    expect(result.current.profile.chosen).toBe(false);
  });

  it("award grants the curve value and decays on repeat", () => {
    const { result } = render();
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
    const option: CustomizationOption = {
      id: "unicorn.horn.rainbow",
      species: "unicorn",
      category: "color",
      slot: "hornColor",
      value: "#F0A",
      name: "Corne arc-en-ciel",
      cost: 4,
    };
    const { result } = render();
    act(() => {
      result.current.award("first-letter", 1); // +10
    });
    let bought = false;
    act(() => {
      bought = result.current.buy(option);
    });
    expect(bought).toBe(true);
    expect(result.current.profile.owned).toContain(option.id);
    expect(result.current.profile.config.colors.hornColor).toBe("#F0A");
    const afterFirst = result.current.profile.balance;

    act(() => {
      result.current.buy(option); // owned -> free re-equip
    });
    expect(result.current.profile.balance).toBe(afterFirst);
  });

  it("persists to localStorage and rehydrates a fresh provider", () => {
    const { result, unmount } = render();
    act(() => {
      result.current.award("first-letter", 1);
      result.current.chooseSpecies("fox");
    });
    unmount();

    const { result: reloaded } = render();
    expect(reloaded.current.profile.chosen).toBe(true);
    expect(reloaded.current.profile.config.species).toBe("fox");
    expect(reloaded.current.profile.balance).toBe(REWARD_CURVE[0]);
  });
});
