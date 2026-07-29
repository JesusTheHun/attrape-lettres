import { describe, it, expect } from "vitest";
import { versionAtLeast } from "./updates";

/* The native-version guard is the one piece of the update path that can brick a
 * tablet: a JS bundle calling a plugin the installed binary lacks is a white
 * screen with no way back. It is also the only piece testable without a device. */

describe("versionAtLeast — the native compatibility guard", () => {
  it("accepts an exact match", () => {
    expect(versionAtLeast("1.3.0", "1.3.0")).toBe(true);
  });

  it("accepts a newer shell", () => {
    expect(versionAtLeast("1.4.0", "1.3.0")).toBe(true);
    expect(versionAtLeast("2.0.0", "1.9.9")).toBe(true);
    expect(versionAtLeast("1.3.1", "1.3.0")).toBe(true);
  });

  it("refuses an older shell", () => {
    expect(versionAtLeast("1.2.9", "1.3.0")).toBe(false);
    expect(versionAtLeast("0.9.0", "1.0.0")).toBe(false);
  });

  it("compares numerically, not as strings", () => {
    // The classic bug: "1.10.0" < "1.9.0" under lexical comparison.
    expect(versionAtLeast("1.10.0", "1.9.0")).toBe(true);
    expect(versionAtLeast("1.9.0", "1.10.0")).toBe(false);
  });

  it("treats missing segments as zero", () => {
    expect(versionAtLeast("1.3", "1.3.0")).toBe(true);
    expect(versionAtLeast("2", "1.9.9")).toBe(true);
    expect(versionAtLeast("1.3", "1.3.1")).toBe(false);
  });
});
