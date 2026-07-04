import type { StorybookConfig } from "@storybook/react-vite";

/**
 * Storybook is a DEV-ONLY visual harness for the mascot rig — it renders every
 * pet at every growth stage and every accessory placement so you can eyeball
 * that e.g. the unicorn's ribbon sits on the throat (not the face) at stade 2.
 * It ships nothing to the kid app; `pnpm build` never touches it.
 */
const config: StorybookConfig = {
  stories: ["../src/**/*.stories.@(ts|tsx)"],
  addons: ["@storybook/addon-essentials"],
  framework: { name: "@storybook/react-vite", options: {} },
};

export default config;
