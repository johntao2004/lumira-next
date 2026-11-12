import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, realpathSync } from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const virtualStore = path.join(root, "node_modules/.pnpm");
const nodeRequire = createRequire(import.meta.url);
const expectedPatches = [
  {
    name: "braces",
    version: "3.0.3",
    packagePath: "braces",
    patch: "patches/braces@3.0.3.patch",
    hash: "eb1f992af77e867b47d12dd28eaf50667833744251ff93232b3d0bff8172e567",
  },
  {
    name: "sprintf-js",
    version: "1.0.3",
    packagePath: "sprintf-js",
    patch: "patches/sprintf-js@1.0.3.patch",
    hash: "4a2ae8d1e3e2526767c659f386d70d1a4ac86594547a727b4da200b3c34232ed",
  },
  {
    name: "@remix-run/router",
    version: "1.23.4",
    packagePath: "@remix-run/router",
    patch: "patches/@remix-run__router@1.23.4.patch",
    hash: "7947513fd20b43bf86ee83adf67bc502863495018a2a1769cb5a548ca96798f5",
  },
];

function installedPackageRoot(name, version) {
  const packageKey = `${name.replace("/", "+")}@${version}`;
  const installed = readdirSync(virtualStore).filter((entry) =>
    entry.startsWith(packageKey),
  );
  assert.equal(
    installed.length,
    1,
    `expected exactly one installed ${name}@${version}`,
  );
  return path.join(virtualStore, installed[0], "node_modules", name);
}

function packageDirectory(patch) {
  const packageKey = patch.name.startsWith("@")
    ? `${patch.name.replace("/", "+")}@${patch.version}`
    : `${patch.name}@${patch.version}`;
  const installed = readdirSync(virtualStore).filter((entry) =>
    entry.startsWith(`${packageKey}_patch_hash=${patch.hash}`),
  );
  assert.equal(
    installed.length,
    1,
    `expected exactly one patched install of ${patch.name}@${patch.version}`,
  );
  return path.join(
    virtualStore,
    installed[0],
    "node_modules",
    patch.packagePath,
  );
}

export function verifyDependencyPatchIntegrity() {
  const manifest = JSON.parse(
    readFileSync(path.join(root, "package.json"), "utf8"),
  );
  const lock = readFileSync(path.join(root, "pnpm-lock.yaml"), "utf8");

  for (const patch of expectedPatches) {
    assert.equal(
      manifest.pnpm.patchedDependencies[`${patch.name}@${patch.version}`],
      patch.patch,
    );
    const content = readFileSync(path.join(root, patch.patch));
    const hash = createHash("sha256").update(content).digest("hex");
    assert.equal(
      hash,
      patch.hash,
      `${patch.patch} changed without updating the reviewed patch hash`,
    );
    assert.ok(
      lock.includes(`patch_hash=${patch.hash}`),
      `${patch.name} patch hash is missing from pnpm-lock.yaml`,
    );

    const packageRoot = packageDirectory(patch);
    const installedManifest = JSON.parse(
      readFileSync(path.join(packageRoot, "package.json"), "utf8"),
    );
    assert.equal(installedManifest.version, patch.version);
  }
}

function loadPatchedPackage(patch) {
  const packageRoot = packageDirectory(patch);
  const require = createRequire(path.join(packageRoot, "package.json"));
  return require(patch.packagePath);
}

export function verifyBracesMitigation() {
  const braces = loadPatchedPackage(expectedPatches[0]);
  assert.deepEqual(braces("{a,b}"), ["(a|b)"]);
  const deeplyNested = "{".repeat(101) + "x" + "}".repeat(101);
  assert.throws(() => braces(deeplyNested), {
    name: "SyntaxError",
    message: "Input nesting exceeds maximum depth (100)",
  });
}

export function verifySprintfMitigation() {
  const sprintf = loadPatchedPackage(expectedPatches[1]).sprintf;
  assert.equal(sprintf("%8.2f", 1.25), "    1.25");
  assert.throws(() => sprintf("%.101f", 1.25), { name: "RangeError" });
  assert.throws(() => sprintf("%10001s", "x"), { name: "RangeError" });
}

export function verifyRouterMitigation() {
  const source = readFileSync(
    path.join(packageDirectory(expectedPatches[2]), "utils.ts"),
    "utf8",
  );
  const cjs = readFileSync(
    path.join(packageDirectory(expectedPatches[2]), "dist/router.cjs.js"),
    "utf8",
  );
  const esm = readFileSync(
    path.join(packageDirectory(expectedPatches[2]), "dist/router.js"),
    "utf8",
  );
  for (const installed of [source, cjs, esm]) {
    assert.ok(installed.includes("normalizeProtocolRelativeUrl"));
    assert.ok(installed.includes("PROTOCOL_RELATIVE_URL_REGEX"));
  }
}

export function verifyRouterHydrationBoundary() {
  const webConfig = readFileSync(path.join(root, "apps/web/.umirc.ts"), "utf8");
  const routeDataApis =
    /\b(?:createBrowserRouter|createHashRouter|createMemoryRouter|createStaticHandler|createStaticRouter|RouterProvider|StaticRouterProvider|HydratedRouter)\b/;
  assert.doesNotMatch(
    webConfig,
    /^\s*ssr\s*:/m,
    "GHSA-337j-9hxr-rhxg requires a React Router SSR hydration path; web SSR must remain disabled",
  );
  assert.doesNotMatch(
    webConfig,
    routeDataApis,
    "the web route configuration must not opt into React Router Data/Framework mode",
  );

  const webRequire = createRequire(path.join(root, "apps/web/package.json"));
  const maxManifest = webRequire.resolve("@umijs/max/package.json");
  const maxRequire = createRequire(maxManifest);
  const presetManifest = maxRequire.resolve("@umijs/preset-umi/package.json");
  const presetRequire = createRequire(presetManifest);
  const rendererManifests = new Set([
    maxRequire.resolve("@umijs/renderer-react/package.json"),
    presetRequire.resolve("@umijs/renderer-react/package.json"),
  ]);
  for (const rendererManifest of rendererManifests) {
    const rendererRoot = path.dirname(rendererManifest);
    const browserSource = readFileSync(
      path.join(rendererRoot, "dist/browser.js"),
      "utf8",
    );
    assert.match(
      browserSource,
      /import\s*\{[^}]*\bRouter\b[^}]*\buseRoutes\b[^}]*\}\s*from\s*['"]react-router-dom['"]/s,
      `Umi renderer ${rendererManifest} no longer uses Declarative Mode`,
    );
    assert.doesNotMatch(
      browserSource,
      routeDataApis,
      `Umi browser renderer ${rendererManifest} entered the React Router Data/Framework hydration path`,
    );
  }

  const appSource = path.join(root, "apps/web/src");
  const visitSource = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) visitSource(file);
      else if (/\.(?:[cm]?[jt]sx?)$/i.test(entry.name)) {
        assert.doesNotMatch(
          readFileSync(file, "utf8"),
          routeDataApis,
          `React Router Data/Framework API found in ${file}`,
        );
        assert.doesNotMatch(
          readFileSync(file, "utf8"),
          /(?:react-router-dom\/server|react-dom\/server|renderToString|renderToPipeableStream|hydrationData)/,
          `manual React Router SSR/hydration found in ${file}`,
        );
      }
    }
  };
  visitSource(appSource);
}

export async function verifyActiveMakoDependencyResolution() {
  const webConfig = readFileSync(path.join(root, "apps/web/.umirc.ts"), "utf8");
  assert.match(
    webConfig,
    /\bmako\s*:/,
    "the active web config must select Mako",
  );
  assert.doesNotMatch(
    webConfig,
    /^\s*ssr\s*:/m,
    "SSR must stay disabled for this static client application",
  );

  const makoRoot = installedPackageRoot("@umijs/bundler-mako", "0.11.10");
  const makoRequire = createRequire(path.join(makoRoot, "package.json"));
  const beforeLoad = new Set(Object.keys(nodeRequire.cache));
  const mako = makoRequire(makoRoot);
  assert.equal(typeof mako.build, "function");
  assert.equal(typeof mako.dev, "function");

  const bundlerUtilsEntry = makoRequire.resolve("@umijs/bundler-utils");
  const bundlerUtilsRequire = createRequire(bundlerUtilsEntry);
  const bundlerUtilsRoot = path.resolve(path.dirname(bundlerUtilsEntry), "..");
  const makoSource = readFileSync(path.join(makoRoot, "index.js"), "utf8");
  const utilitySource = readFileSync(bundlerUtilsEntry, "utf8");
  const esbuildShim = readFileSync(
    path.join(bundlerUtilsRoot, "compiled/esbuild/index.js"),
    "utf8",
  );

  assert.match(makoSource, /require\(['"]@umijs\/bundler-utils['"]\)/);
  assert.match(utilitySource, /require\(['"]\.\.\/compiled\/esbuild['"]\)/);
  assert.match(esbuildShim, /require\(['"]esbuild['"]\)/);
  const esbuildPath = bundlerUtilsRequire.resolve("esbuild");
  const esbuild = bundlerUtilsRequire("esbuild");
  assert.equal(
    esbuild.version,
    "0.28.2",
    `Mako's bundler-utils resolved ${esbuildPath}`,
  );
  assert.match(esbuildPath, /node_modules\/\.pnpm\/esbuild@0\.28\.2/);

  // Umi still ships a webpack-only vendored Babel tree. Loading the configured
  // Mako adapter must not load webpack or its Babel transform core.
  const newlyLoaded = Object.keys(nodeRequire.cache).filter(
    (modulePath) => !beforeLoad.has(modulePath),
  );
  assert.ok(newlyLoaded.some((modulePath) => modulePath.startsWith(realpathSync(makoRoot) + path.sep)));
  assert.equal(
    newlyLoaded.some((modulePath) => modulePath.includes("/bundler-webpack/")),
    false,
  );
  assert.equal(
    newlyLoaded.some((modulePath) =>
      modulePath.includes("/compiled/babel/core.js"),
    ),
    false,
  );
  assert.equal(
    newlyLoaded.some((modulePath) => modulePath.includes("/browserify-sign/")),
    false,
  );
  assert.equal(
    newlyLoaded.some((modulePath) => modulePath.includes("/elliptic/")),
    false,
  );

  verifyRouterHydrationBoundary();

  const [imports, exports] = await bundlerUtilsRequire(
    "@umijs/bundler-utils",
  ).parseModule({
    path: "dependency-resolution.tsx",
    content: 'import React from "react"; export const Widget = () => <div />;',
  });
  assert.equal(imports.length, 1);
  assert.deepEqual(exports, ["Widget"]);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  verifyDependencyPatchIntegrity();
  verifyBracesMitigation();
  verifySprintfMitigation();
  verifyRouterMitigation();
  await verifyActiveMakoDependencyResolution();
  console.log(
    "Dependency patch hashes, installed contents, and trigger/control checks passed.",
  );
}
