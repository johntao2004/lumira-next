import { defineConfig } from "@umijs/max";

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
  hash: true,
  history: { type: "browser" },
  mako: {
    optimization: { skipModules: true },
  },
  routes: [
    { path: "/", redirect: "/dashboard/home" },
    { path: "/user/login", component: "@/pages/user/login" },
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
