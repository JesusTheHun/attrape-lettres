import { defineConfig } from "vitest/config";

// Explicit, so this package never picks up the web app's config by proximity —
// which it did, and which showed up as vitest looking for a happy-dom setup
// file that has no business existing here. These tests are Node, no DOM, and
// they build requests by hand. The one setup file only silences the request
// log; it installs no globals and no doubles.
export default defineConfig({
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
    setupFiles: ["test/setup.ts"],
  },
});
