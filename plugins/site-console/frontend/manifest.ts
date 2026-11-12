import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const siteConsoleManifest = {
  id: "site-console", version: "1.0.0", required: false, dependencies: ["files", "settings"],
  routes: [
    { id: "branding", label: "siteSettings", tabLabel: "branding", permission: "platform:branding:access", accessScope: "global", operations: { update: "platform:branding:update" }, group: "administration", order: 20 },
    { id: "watermark", label: "watermark", permission: "platform:watermark:access", accessScope: "global", operations: { update: "platform:watermark:update" }, group: "administration", order: 30 },
    { id: "agreements", label: "agreements", permission: "platform:agreements:access", accessScope: "global", operations: { update: "platform:agreements:update" }, group: "administration", order: 40 },
    { id: "maintenance", label: "maintenance", permission: "platform:maintenance:access", accessScope: "global", operations: { update: "platform:maintenance:update" }, group: "administration", order: 50 },
  ],
  tabGroups: [
    { id: "personalization", routes: ["branding", "watermark", "agreements", "maintenance"], anchor: "branding", collapse: "when-all-visible" },
  ],
} as const satisfies FrontendModuleManifest;
