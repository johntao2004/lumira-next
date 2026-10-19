/** 生成用户编辑请求并核对快照版本，不在客户端推断额外授权。
 * Build user edit requests and check snapshot versions without inferring additional client-side authority. */
import { preserveProfileFieldValues } from "../../platform/profile-fields/values.ts";

type ProfileBaseline = { displayName: string; enabled: boolean };
type ProfileValuesSnapshot = {
  schemaVersion: number;
  fields: readonly { key: string }[];
  values: Readonly<Record<string, string>>;
};

export type UserEditorSavePlan = {
  hasChanges: boolean;
  profileChanged: boolean;
  profileValuesChanged: boolean;
  payload: {
    version: number;
    profile?: { displayName: string; enabled: boolean };
    profileSchemaVersion?: number;
    profileValues?: Record<string, string>;
    roleIds?: string[];
    organizationIds?: string[];
  };
};

/** 根据完整表单存储和显示快照构造单次请求。 Build one editor request from the full form store and the snapshots used to open it. */
export function buildUserEditorSavePlan(input: {
  version: number;
  baseline: ProfileBaseline;
  formValues: Readonly<Record<string, unknown>>;
  profileValues?: ProfileValuesSnapshot;
  canSaveProfile: boolean;
  roleIds?: string[];
  organizationIds?: string[];
}): UserEditorSavePlan {
  const displayName = typeof input.formValues.displayName === "string"
    ? input.formValues.displayName
    : input.baseline.displayName;
  const enabled = typeof input.formValues.enabled === "boolean"
    ? input.formValues.enabled
    : input.baseline.enabled;
  const profileChanged = input.canSaveProfile
    && (displayName !== input.baseline.displayName || enabled !== input.baseline.enabled);
  const nextProfileValues = input.canSaveProfile && input.profileValues
    ? preserveProfileFieldValues(input.formValues, input.profileValues.fields, input.profileValues.values)
    : undefined;
  const profileValuesChanged = !!(nextProfileValues && input.profileValues
    && input.profileValues.fields.some(field => nextProfileValues[field.key] !== (input.profileValues!.values[field.key] ?? "")));
  const hasChanges = profileChanged || profileValuesChanged
    || input.roleIds !== undefined || input.organizationIds !== undefined;

  return {
    hasChanges,
    profileChanged,
    profileValuesChanged,
    payload: {
      version: input.version,
      ...(profileChanged ? { profile: { displayName: displayName.trim(), enabled } } : {}),
      ...(profileValuesChanged && nextProfileValues && input.profileValues
        ? { profileSchemaVersion: input.profileValues.schemaVersion, profileValues: nextProfileValues }
        : {}),
      ...(input.roleIds !== undefined ? { roleIds: input.roleIds } : {}),
      ...(input.organizationIds !== undefined ? { organizationIds: input.organizationIds } : {}),
    },
  };
}

/** 表单快照必须与当前记录及关联查询同版本，后台刷新不能替旧草稿升级乐观锁。
 * The form snapshot must match the current record and related queries; background refresh must not upgrade a draft's optimistic lock.
 */
export function userEditorVersionMismatch(displayedVersion: number | undefined, currentVersion: number | undefined, relatedVersions: readonly number[]): boolean {
  return currentVersion !== undefined && (displayedVersion !== currentVersion || relatedVersions.some(version => version !== currentVersion));
}

/** 为刷新请求绑定编辑会话代际，关闭、切换或新请求后拒绝迟到写入。
 * Bind refresh requests to an editor generation and reject late writes after closure, switching or a newer request.
 */
export function createUserEditorRefreshFence() {
  let generation = 0;
  return {
    invalidate: () => { generation++; },
    begin: () => {
      const expected = ++generation;
      return () => generation === expected;
    },
  };
}
