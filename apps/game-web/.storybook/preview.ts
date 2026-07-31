import type { Preview } from "@storybook/react";
import "../src/index.css";

const preview: Preview = {
  parameters: {
    layout: "centered",
    controls: { expanded: true },
    backgrounds: {
      default: "jeu",
      values: [
        { name: "jeu", value: "linear-gradient(180deg,#FFF3E0 0%,#EAF4FF 100%)" },
        { name: "blanc", value: "#ffffff" },
      ],
    },
  },
};

export default preview;
