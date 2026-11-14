import type { MessageKey } from "@/platform/i18n/messages";

export const importDelegationErrorKeys = [
  "rolePermissionDelegationDenied",
  "rolePermissionUnavailable",
  "rolePermissionRevocationDenied",
  "moduleInstallRequiresPlatformAuthority",
] as const satisfies readonly MessageKey[];

export const importRowErrorKeys: Record<string, MessageKey> = {
  invalidUsername: "userImportRowInvalidUsername",
  duplicateUsername: "userImportRowDuplicateUsername",
  missingDisplayName: "userImportRowMissingDisplayName",
  invalidBoolean: "userImportRowInvalidBoolean",
  usernameAlreadyExists: "userImportRowUsernameExists",
  userNotFound: "userImportRowUserNotFound",
  forbidden: "userImportRowForbidden",
  noImportChanges: "userImportRowNoChanges",
  invalidRoleCodes: "userImportRowInvalidRoleCodes",
  rolePermissionDelegationDenied: "rolePermissionDelegationDenied",
  rolePermissionUnavailable: "rolePermissionUnavailable",
  rolePermissionRevocationDenied: "rolePermissionRevocationDenied",
  moduleInstallRequiresPlatformAuthority: "moduleInstallRequiresPlatformAuthority",
  batchRolledBack: "userImportRowBatchRolledBack",
  invalidOrganizationCodes: "userImportRowInvalidOrganizationCodes",
  invalidProfileValues: "userImportRowInvalidProfileValues",
};

export const importTaskErrorKeys: Record<string, MessageKey> = {
  importHasInvalidRows: "userImportTaskRowsInvalid",
  importVersionConflict: "importVersionConflict",
  staleRecord: "staleRecord",
  invalidUser: "userImportTaskInvalidUser",
  invalidProfileValues: "userImportRowInvalidProfileValues",
  rolePermissionDelegationDenied: "rolePermissionDelegationDenied",
  rolePermissionUnavailable: "rolePermissionUnavailable",
  rolePermissionRevocationDenied: "rolePermissionRevocationDenied",
  moduleInstallRequiresPlatformAuthority: "moduleInstallRequiresPlatformAuthority",
  batchRolledBack: "userImportRowBatchRolledBack",
  invalidOrganizationAssignment: "userImportTaskInvalidOrganizationAssignment",
};
