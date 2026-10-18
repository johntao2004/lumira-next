import type { MessageKey } from "@/platform/i18n/messages";

export const deleteErrorKeys = [
  "staleRecord",
  "fileMutationConflict",
  "dictionaryInUse",
  "dictionaryReferenced",
  "dictionaryItemReferenced",
  "dictionaryItemInUse",
  "roleInUse",
  "registrationRoleInUse",
  "rolePermissionDelegationDenied",
  "rolePermissionRevocationDenied",
  "organizationInUse",
  "sessionNotFound",
  "forbidden",
  "protectedAccount",
] as const satisfies readonly MessageKey[];
