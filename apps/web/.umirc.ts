import { defineConfig } from "@umijs/max";
import { cpSync, existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

function preparePdfjsAssets() {
  const appRoot = process.cwd();
  const packagePath = resolve(appRoot, "node_modules/pdfjs-dist");
  if (!existsSync(packagePath)) {
    throw new Error("pdfjs-dist is required; run pnpm install before starting the web app.");
  }
  const packageRoot = realpathSync(packagePath);
  const packageVersion = JSON.parse(readFileSync(resolve(packageRoot, "package.json"), "utf8")).version;
  const publicRoot = resolve(appRoot, "public/pdfjs", packageVersion);
  const assets: [string, string][] = [
    ["LICENSE", "LICENSE"],
    ["build/pdf.worker.min.mjs", "pdf.worker.min.js"],
    ["cmaps", "cmaps"],
    ["iccs", "iccs"],
    ["standard_fonts", "standard_fonts"],
    ["wasm", "wasm"],
  ];
  const markerRoot = resolve(appRoot, "node_modules/.cache/lumira-pdfjs-assets");
  const marker = resolve(markerRoot, `${packageVersion}.prepared`);
  const expectedMarker = `${packageVersion}\n`;
  if (
    existsSync(marker) &&
    readFileSync(marker, "utf8") === expectedMarker &&
    assets.every(([, outputName]) => existsSync(resolve(publicRoot, outputName)))
  ) {
    return;
  }

  mkdirSync(publicRoot, { recursive: true });
  for (const [sourcePath, outputName] of assets) {
    const source = resolve(packageRoot, sourcePath);
    if (!existsSync(source)) {
      throw new Error(`pdfjs-dist ${packageVersion} is missing required asset ${sourcePath}.`);
    }
    cpSync(source, resolve(publicRoot, outputName), { recursive: true, force: true });
  }
  mkdirSync(markerRoot, { recursive: true });
  writeFileSync(marker, expectedMarker);
}

preparePdfjsAssets();

const backend = process.env.LUMIRA_API_ORIGIN ?? "http://127.0.0.1:8090";
const preferenceBootstrap = `(() => {
  const fallback = { dark: false, locale: "zh-CN" };
  try {
    const prefix = "lumira-next.preferences=";
    const item = document.cookie.split(";").map((part) => part.trim()).find((part) => part.startsWith(prefix));
    const value = item ? JSON.parse(decodeURIComponent(item.slice(prefix.length))) : fallback;
    const locales = ["zh-CN", "zh-TW", "en", "ja", "ko", "fr", "es", "de"];
    document.documentElement.lang = locales.includes(value.locale) ? value.locale : fallback.locale;
    document.documentElement.dataset.theme = value.dark === true ? "dark" : "light";
  } catch {
    document.documentElement.lang = fallback.locale;
    document.documentElement.dataset.theme = "light";
  }
})();`;

export default defineConfig({
  title: "Lumira",
  headScripts: [{ content: preferenceBootstrap }],
  npmClient: "pnpm",
  // Mako writes development chunks to disk. A production build must not clean
  // the directory used by a running preview's lazy-loaded routes.
  outputPath:
    process.env.NODE_ENV === "development" ? "../../.runtime/web-dev" : "dist",
  hash: true,
  history: { type: "browser" },
  devtool: process.env.NODE_ENV === "production" ? false : undefined,
  mako: {
    optimization: { skipModules: true },
  },
  routes: [
    { path: "/", redirect: "/dashboard/home" },
    { path: "/user/login", component: "@/pages/user/login" },
    { path: "/user/register", component: "@/pages/user/register" },
    { path: "/user/wechat-result", component: "@/pages/user/wechat-result" },
    { path: "/share/ai/:token", component: "@/pages/share/ai" },
    { path: "/dashboard/:section", component: "@/pages/dashboard/section" },
    { path: "*", component: "@/pages/not-found" },
  ],
  proxy: {
    "/api": {
      target: backend,
      changeOrigin: true,
    },
  },
});
