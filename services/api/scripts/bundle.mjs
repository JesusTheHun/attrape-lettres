import { rm } from "node:fs/promises";

import { build } from "esbuild";

/* -------------------------------------------------------------------------- */
/* One file for Lambda.                                                        */
/*                                                                             */
/* WHY BUNDLE AT ALL. This is a pnpm workspace, so `node_modules` is a forest  */
/* of symlinks into a content-addressed store — zip it and the archive is      */
/* either broken links or three copies of the AWS SDK. Bundling sidesteps that */
/* entirely and produces an artefact that does not depend on how the package   */
/* manager laid the disk out.                                                  */
/*                                                                             */
/* WHY THE AWS SDK IS BUNDLED TOO, rather than left to the runtime. Managed    */
/* runtimes have included and then stopped including the SDK before, and which */
/* minor version you get is not yours to choose. Bundling it costs about a     */
/* megabyte of a 250 MB budget and makes the deployed artefact the same code   */
/* the tests ran against, which is worth more than the megabyte.               */
/*                                                                             */
/* NOT MINIFIED, on purpose. This service reports its own failures by name in  */
/* a log line nobody is watching in real time; a mangled stack in the one       */
/* record of an outage is a bad trade for a smaller zip.                       */
/* -------------------------------------------------------------------------- */

const out = new URL("../dist-lambda/", import.meta.url);

await rm(out, { recursive: true, force: true });

const result = await build({
  entryPoints: [new URL("../src/lambda.ts", import.meta.url).pathname],
  // `.mjs` is what tells the Node runtime to load this as an ES module without
  // a package.json riding along in the zip.
  outfile: new URL("index.mjs", out).pathname,
  bundle: true,
  platform: "node",
  // Matches the runtime in `infra/template.yaml`. The two have to agree, and
  // this is the cheaper half of the pair to get wrong.
  target: "node22",
  format: "esm",
  sourcemap: true,
  minify: false,
  metafile: true,
  // esbuild's ESM output uses these shims for CommonJS interop, which several
  // AWS SDK transitive dependencies still need.
  banner: {
    js: [
      "import { createRequire as __createRequire } from 'node:module';",
      "const require = __createRequire(import.meta.url);",
    ].join("\n"),
  },
});

const bytes = Object.values(result.metafile.outputs).reduce((sum, o) => sum + o.bytes, 0);
console.log(`bundled ${(bytes / 1024 / 1024).toFixed(2)} MB into dist-lambda/`);
