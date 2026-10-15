# Role-based file quota management

The current global database initialization is `B52__platform_initialization.sql`. It defines
nullable, non-negative `file_quota_bytes_limit` and `file_quota_files_limit` columns on
`platform_role`. The nullable `file_quota_bytes_override` and
`file_quota_files_override` columns on `platform_user` remain visible as legacy account data,
but they do not affect runtime quota calculations. This document describes current policy
management; it is not an upgrade or rollback procedure for historical global migration
versions.

## Review account-level legacy values

The user list and detail pages flag accounts whose legacy override fields are non-null. To
review those values:

```sql
SELECT id, username, file_quota_bytes_override, file_quota_files_override
FROM public.platform_user
WHERE deleted_at IS NULL
  AND (file_quota_bytes_override IS NOT NULL OR file_quota_files_override IS NOT NULL)
ORDER BY username, id;
```

If the intended policy is to retain a limit for those users, represent it explicitly with
roles. Group the reviewed accounts by their `(file_quota_bytes_override,
file_quota_files_override)` pair and create one enabled quota-only role per distinct pair,
with no authorities. Copy each non-null dimension to the matching role limit and leave null
dimensions unset. Accounts with the same pair can share that role. There is no automatic
copy or fallback from the account-level fields.

For each account, read the full current role set and version from
`GET /api/platform/users/{userId}/roles`, then append the intended quota role. Preview the
complete proposed set before saving:

```text
GET /api/platform/users/{userId}/file-quota/preview?roleIds={existingRoleId}&roleIds={quotaRoleId}
```

The preview reports current and proposed effective limits. Another stricter active role can
keep either dimension below the reviewed account-level value. Save the complete role set
with the version returned by the read using
`PUT /api/platform/users/{userId}/roles` and
`{"version": ..., "roleIds": [...]}`. Do not replace existing roles with only the quota
role. Role reads and writes continue to enforce role permissions and user data scope.

## Effective quota rules

Enabled assigned role limits combine independently for bytes and file count. For bytes,
positive values combine by minimum. A `0` role limit means unlimited; it does not weaken a
positive limit on another active role. If no active role sets a positive byte limit but at
least one sets `0`, the effective byte limit is unlimited. A null value adds no role policy;
the system default applies only when every active role leaves that dimension null. File-count
limits combine by minimum, and `0` rejects new files. Role limits may exceed the system
default.

The system fallback uses `LUMIRA_FILES_DEFAULT_BYTES` and
`LUMIRA_FILES_DEFAULT_COUNT`; the existing `LUMIRA_FILES_MAXIMUM_BYTES` and
`LUMIRA_FILES_MAXIMUM_COUNT` names remain accepted as aliases. Quota values must be
non-negative safe integers so the browser can display and submit them without precision
loss; this supported numeric range is not a product hard cap.

Disabled or removed roles do not contribute limits. Existing files remain readable and are
not deleted when a limit is lowered. Usage can remain above a finite limit; uploads are
rejected until usage falls within both limits. The aggregate byte limit does not change the
separate per-file upload size limit. Usage reads, role previews, and upload reservations use
the same resolver.

## Persisted system defaults

Read the defaults with `GET /api/platform/settings/file-quota` and update them with
`PUT /api/platform/settings/file-quota`. The response is a versioned snapshot:

```json
{
  "version": 3,
  "data": { "maximumBytes": 1073741824, "maximumFiles": 1000 }
}
```

For a `PUT`, send the version currently read together with both values in `data`. A successful
save advances the version and persists the values in the `file-quota` platform setting. A stale
version returns `409 staleRecord`; malformed, negative, fractional, or unsafe integer values
return `400 invalidFileQuota`. When no setting has been saved yet, reads return version `0`
with the environment-configured fallback. The first save from version `0` creates the setting;
afterward the persisted values take precedence over environment defaults and remain in effect
across service restarts.

Reading the snapshot requires global `platform:files-all:access`. Updating requires that page
access and the global `platform:files-all:update-quota` operation. The operation grant alone
does not replace page access. Each successful update is recorded in setting history and audit.

Quota resolution reads the saved system defaults for dimensions that no enabled assigned role
limits. A system byte value of `0` removes the aggregate byte ceiling; a system file-count value
of `0` blocks new file slots. Usage reads and upload reservations use the updated defaults
immediately. Lowering a limit does not remove existing files; uploads remain blocked while
current usage exceeds either finite limit.

## Operational checks

Before assigning or changing a quota role, read the user's complete current role set and
version, review the preview, and save the complete set with that version. A role limit is
shared by every member of that role, and multiple roles combine by the lower limit per
dimension. Verify both the role configuration and the effective quota shown for affected
users after the assignment.
