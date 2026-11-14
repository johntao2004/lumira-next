import type { MessageKey } from "@/platform/i18n/messages";

export const userErrorKeys = [
  "invalidUser",
  "duplicateRecord",
  "staleRecord",
  "protectedAccount",
  "forbidden",
  "invalidRole",
  "invalidOrganization",
  "rolePermissionUnavailable",
  "rolePermissionDelegationDenied",
  "rolePermissionRevocationDenied",
] as const satisfies readonly MessageKey[];
