import { render, screen, fireEvent, act } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { ProfileProvider } from "./hooks/useProfile";
import { EntitlementProvider } from "./licensing/useEntitlement";
import { __setPurchaseStore, type PurchaseStore } from "./licensing/store";
import { DAY_MS, TRIAL_DAYS, TRIAL_MS } from "./licensing/entitlement";
import { hasConsent } from "./telemetry";

/* -------------------------------------------------------------------------- */
/* The gates in front of the game. Two new screens stand between a fresh        */
/* install and the hub, and either could lock a child out of a game their       */
/* parents paid for — so the paths through them are asserted end to end.        */
/* -------------------------------------------------------------------------- */

/* happy-dom has no WAAPI; reduced-motion makes the animation helpers no-op. */
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
  window.location.hash = "";
  __setPurchaseStore(store(false));
});

/* happy-dom has neither speechSynthesis nor a playable <audio>. */
vi.mock("./hooks/useAudio", () => ({
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

/** A store that answers instantly, so the provider settles on first render. */
function store(paid: boolean, trialStartedAt: number | null = null): PurchaseStore {
  return {
    available: true,
    refresh: () => Promise.resolve({ paid, trialStartedAt, reachable: true }),
    beginTrial: () => Promise.resolve(null),
    purchase: () => Promise.resolve(true),
    restore: () => Promise.resolve(paid),
    priceLabel: () => Promise.resolve("9,99 €"),
  };
}

/** A device already past onboarding, with one child mid-play. Lands on the hub. */
function seedPlayingDevice(): void {
  localStorage.setItem("attrape-lettres:onboarded:v1", "1");
  localStorage.setItem(
    "attrape-lettres:roster:v4",
    JSON.stringify({
      children: [
        {
          id: "lea",
          name: "Léa",
          nameRev: { at: 1, by: "d" },
          touchedAt: 1,
          profile: {
            chosen: true,
            current: "unicorn",
            currentRev: { at: 1, by: "d" },
            species: {},
            stars: { earned: { d: 20 }, spent: {} },
            clears: {},
          },
        },
      ],
      activeId: "lea",
      removed: {},
    })
  );
}

function setTrialStartedAt(at: number): void {
  localStorage.setItem(
    "attrape-lettres:license:v1",
    JSON.stringify({ paid: false, verifiedAt: null, trialStartedAt: at, clockHighWater: 0 })
  );
}

/**
 * Render and let the entitlement provider settle. The store answers
 * asynchronously, so without the act() flush every test races a setState and
 * React rightly complains.
 */
async function view(): Promise<void> {
  await act(async () => {
    render(
      <EntitlementProvider>
        <ProfileProvider>
          <App />
        </ProfileProvider>
      </EntitlementProvider>
    );
  });
}

describe("first launch", () => {
  it("shows the parent screen before anything else", async () => {
    await view();
    expect(screen.getByText("Nous aussi, on est parents.")).toBeInTheDocument();
    // No child-facing screen exists until an adult has seen the terms.
    expect(screen.queryByText(/Comment tu t'appelles/)).not.toBeInTheDocument();
  });

  it("discloses duration, what stops working, and the price (App Review 3.1.1)", async () => {
    await view();
    expect(screen.getByText(/gratuit pendant/)).toBeInTheDocument();
    expect(screen.getByText(/se mettent en pause/)).toBeInTheDocument();
    expect(screen.getByText(/9,99/)).toBeInTheDocument();
  });

  it("does not opt the family into analytics by accident", async () => {
    await view();
    expect(screen.getByRole("checkbox")).not.toBeChecked(); // Planet49
    fireEvent.click(screen.getByRole("button", { name: new RegExp(`Commencer les ${TRIAL_DAYS} jours`) }));
    expect(hasConsent()).toBe(false);
  });

  it("records consent when the parent actually ticks the box", async () => {
    await view();
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: new RegExp(`Commencer les ${TRIAL_DAYS} jours`) }));
    expect(hasConsent()).toBe(true);
  });

  it("hands over to the child once the trial starts", async () => {
    await view();
    fireEvent.click(screen.getByRole("button", { name: new RegExp(`Commencer les ${TRIAL_DAYS} jours`) }));
    // Empty roster ⇒ the welcome screen asks for a first name.
    expect(screen.getByText(/Comment tu t'appelles/)).toBeInTheDocument();
  });
});

describe("during the trial", () => {
  beforeEach(() => {
    seedPlayingDevice();
    setTrialStartedAt(Date.now() - 3 * DAY_MS);
  });

  it("counts the days down for the parent, on the hub", async () => {
    await view();
    expect(
      screen.getByText(`Essai gratuit — ${TRIAL_DAYS - 3} jours restants`)
    ).toBeInTheDocument();
  });

  it("lets the child straight into a level", async () => {
    await view();
    fireEvent.click(screen.getAllByLabelText(/^Niveau 1,/)[0]);
    expect(screen.queryByText(/Les jeux font une pause/)).not.toBeInTheDocument();
  });
});

describe("once the trial has expired", () => {
  beforeEach(() => {
    seedPlayingDevice();
    setTrialStartedAt(Date.now() - TRIAL_MS - 1000);
  });

  it("still opens on the hub — nothing is taken away from the child", async () => {
    await view();
    expect(screen.getByText("Attrape-Lettres")).toBeInTheDocument();
    expect(screen.getByLabelText(/Mon copain et mes points/)).toBeInTheDocument();
    expect(screen.getByText(/20/)).toBeInTheDocument(); // her stars, untouched
  });

  it("pauses a level tap with a kid-safe screen that shows no price", async () => {
    await view();
    fireEvent.click(screen.getAllByLabelText(/^Niveau 1,/)[0]);

    expect(screen.getByText("Les jeux font une pause")).toBeInTheDocument();
    // Kids Category 1.3: no purchase in front of a child.
    expect(screen.queryByText(/9,99/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Débloquer/ })).not.toBeInTheDocument();
  });

  it("puts the parental gate between the child and the price", async () => {
    await view();
    fireEvent.click(screen.getAllByLabelText(/^Niveau 1,/)[0]);
    fireEvent.click(screen.getByRole("button", { name: /Je suis un adulte/ }));

    expect(screen.getByRole("dialog", { name: /Espace parents/ })).toBeInTheDocument();
    expect(screen.getByText(/Combien font/)).toBeInTheDocument();
    // Still no price — the gate has not been passed.
    expect(screen.queryByRole("button", { name: /Débloquer/ })).not.toBeInTheDocument();
  });

  it("lets the child back to their mascot instead of trapping them", async () => {
    await view();
    fireEvent.click(screen.getAllByLabelText(/^Niveau 1,/)[0]);
    fireEvent.click(screen.getByRole("button", { name: /Voir ma mascotte/ }));
    expect(screen.getByText("Attrape-Lettres")).toBeInTheDocument();
  });
});

describe("a paid family", () => {
  beforeEach(() => {
    seedPlayingDevice();
    setTrialStartedAt(Date.now() - TRIAL_MS - 1000); // trial long gone
    __setPurchaseStore(store(true));
  });

  it("sees no countdown and is never paused", async () => {
    await view();
    expect(screen.queryByText(/Essai gratuit/)).not.toBeInTheDocument();
    fireEvent.click(screen.getAllByLabelText(/^Niveau 1,/)[0]);
    expect(screen.queryByText(/Les jeux font une pause/)).not.toBeInTheDocument();
  });
});
