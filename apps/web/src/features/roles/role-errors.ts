import type { MessageKey } from "@/platform/i18n/messages";

export const roleErrorKeys = [
  "staleRecord", "protectedAccount", "forbidden", "invalidRole", "invalidFileQuota",
  "rolePermissionDelegationDenied", "rolePermissionUnavailable", "registrationRoleInUse",
] as const satisfies readonly MessageKey[];
