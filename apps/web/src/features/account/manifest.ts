import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

/** Identity owns account drawers and auth capabilities, even without a dashboard page. */
export const identityManifest = {
  id: "identity",
  version: "1.0.0",
  required: true,
  dependencies: [],
  routes: [
    { id: "account-security", label: "accountSecurity", permission: null, group: "overview", order: 25 },
    { id: "account-role-preview", label: "rolePreview", permission: null, hidden: true, group: "overview", order: 26 },
    { id: "account-wechat", label: "wechatBinding", permission: null, hidden: true, group: "overview", order: 27 },
  ],
} as const satisfies FrontendModuleManifest;
