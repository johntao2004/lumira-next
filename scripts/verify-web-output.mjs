import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceMapReference =
  /(?:\/\/[#@]\s*sourceMappingURL=|\/\*[#@]\s*sourceMappingURL=)/;

export function findWebSourceMapExposure(outputDirectory) {
  if (
    !existsSync(outputDirectory) ||
    !statSync(outputDirectory).isDirectory()
  ) {
    throw new Error(
      `Web build output directory does not exist: ${outputDirectory}`,
    );
  }

  const maps = [];
  const references = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(file);
      } else if (entry.isFile()) {
        const relative = path.relative(outputDirectory, file);
        if (entry.name.endsWith(".map")) maps.push(relative);
        if (
          /\.(?:[cm]?js|css|html)$/i.test(entry.name) &&
          sourceMapReference.test(readFileSync(file, "utf8"))
        ) {
          references.push(relative);
        }
      }
    }
  };
  visit(outputDirectory);
  return { maps, references };
}

export function assertNoWebSourceMapExposure(outputDirectory) {
  const { maps, references } = findWebSourceMapExposure(outputDirectory);
  if (maps.length > 0) {
    throw new Error(
      `Found ${maps.length} source map file(s) in ${outputDirectory}: ${maps.slice(0, 5).join(", ")}`,
    );
  }
  if (references.length > 0) {
    throw new Error(
      `Found ${references.length} sourceMappingURL reference(s) in ${outputDirectory}: ${references.slice(0, 5).join(", ")}`,
    );
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const outputDirectory = path.resolve(
    root,
    process.argv[2] ?? "apps/web/dist",
  );
  assertNoWebSourceMapExposure(outputDirectory);
  console.log(
    `No source maps or sourceMappingURL references found in ${outputDirectory}.`,
  );
}
