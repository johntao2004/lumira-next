import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const profileConsoleManifest = { id: "profile-console", version: "1.0.0", required: false, dependencies: ["identity","settings","dictionaries"], routes: [
    { id: "profile-fields", label: "profileFields", permission: "platform:profile-fields:access", accessScope: "global", operations: { update: "platform:profile-fields:update", readDictionaries: "platform:profile-fields:read-dictionaries" }, group: "user-management", order: 50 },
] } as const satisfies FrontendModuleManifest;
