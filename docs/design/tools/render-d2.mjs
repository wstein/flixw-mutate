// Renders every docs/design/diagrams/*.d2 to docs/design/figures/<name>.svg.
// Uses the pinned @terrastruct/d2 WebAssembly build, so local and CI output are identical.
// Usage: node render-d2.mjs <diagrams dir> <figures dir>
import { D2 } from "@terrastruct/d2";
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";

const [srcDir, outDir] = process.argv.slice(2);
if (!srcDir || !outDir) {
  console.error("usage: node render-d2.mjs <diagrams dir> <figures dir>");
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

const d2 = new D2();
let failed = false;
for (const file of readdirSync(srcDir).filter((f) => f.endsWith(".d2")).sort()) {
  const source = readFileSync(join(srcDir, file), "utf8");
  try {
    const result = await d2.compile(source, { layout: "dagre" });
    const svg = await d2.render(result.diagram, { ...result.renderOptions, pad: 24, center: true });
    const out = join(outDir, basename(file, ".d2") + ".svg");
    writeFileSync(out, svg);
    console.log(`rendered ${out}`);
  } catch (error) {
    failed = true;
    console.error(`${file}: ${error?.message ?? error}`);
  }
}
// The WASM worker keeps the event loop alive; exit explicitly.
process.exit(failed ? 1 : 0);
