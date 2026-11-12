import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const deliverySettingsManifest = { id: "delivery-settings", version: "1.0.0", required: false, dependencies: ["settings","communication"], routes: [
    { id: "smtp", label: "messageChannels", tabLabel: "smtpSettings", permission: "platform:smtp:access", accessScope: "global", operations: { update: "platform:smtp:update", test: "platform:smtp:test" }, group: "communication", order: 20 },
    { id: "sms", label: "messageChannels", tabLabel: "smsSettings", permission: "platform:sms:access", accessScope: "global", operations: { update: "platform:sms:update", test: "platform:sms:test" }, group: "communication", order: 30 },
], tabGroups: [
    { id: "message-channels", routes: ["smtp", "sms"], anchor: "smtp", collapse: "when-all-visible" },
] } as const satisfies FrontendModuleManifest;
