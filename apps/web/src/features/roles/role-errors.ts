import type { MessageKey } from "@/platform/i18n/messages";

export const roleErrorKeys = [
  "staleRecord", "protectedAccount", "forbidden", "invalidRole", "invalidFileQuota",
  "rolePermissionDelegationDenied", "rolePermissionUnavailable", "rolePermissionRevocationDenied",
  "roleRetainsRetiredPermissions", "moduleInstallRequiresPlatformAuthority", "registrationRoleInUse",
] as const satisfies readonly MessageKey[];
