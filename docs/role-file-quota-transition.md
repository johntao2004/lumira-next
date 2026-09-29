# Role based file quota transition

V12 adds nullable byte and file-count limits to `platform_role`. The two V11 columns on
`platform_user` are deliberately retained and are not rewritten by the migration. New code
does not read those user-level values when calculating upload or usage limits. A previous
application version can therefore restore the old behavior without reconstructing data.
V12 does not create quota roles, copy overrides, or change user-role assignments. After the
new code is deployed, a retained V11 override no longer affects runtime limits: a user with
no active role limit receives the system default. This can raise that user's effective limit
until the override is deliberately mapped to role policy; there is no per-user fallback.

## Review and migrate legacy values

Before rollout, export users with a V11 override and keep the export with the release
backup:

```sql
SELECT id, username, file_quota_bytes_override, file_quota_files_override
FROM public.platform_user
WHERE deleted_at IS NULL
  AND (file_quota_bytes_override IS NOT NULL OR file_quota_files_override IS NOT NULL)
ORDER BY username, id;
```

Before enabling the new runtime for users with non-null overrides, map those values
deliberately:

1. Group exported users by their `(file_quota_bytes_override, file_quota_files_override)`
   pair. Create one enabled quota-only role for each distinct pair, with no authorities.
   Copy each non-null dimension to the corresponding role limit and leave null dimensions
   unset. Users with the same pair can reuse that role.
2. For each target user, read the current role IDs and version from
   `GET /api/platform/users/{userId}/roles`. Keep that complete role set and append the
   matching quota role ID.
3. Preview the complete proposed role set before saving:

   ```text
   GET /api/platform/users/{userId}/file-quota/preview?roleIds={existingRoleId}&roleIds={quotaRoleId}
   ```

   The directory user editor shows the retained legacy values and the current/proposed
   effective limits. Review the difference; another stricter active role can keep either
   dimension below the old per-user value. The system default applies only when no enabled
   assigned role sets that dimension.
4. Only after reviewing the preview, save the complete role set and the version returned by
   the roles read using `PUT /api/platform/users/{userId}/roles` with
   `{"version": ..., "roleIds": [...]}`. Do not replace the user's existing roles with
   only the quota role.

Role reads/writes continue to enforce the existing role permission and user-scope checks.
The active-role minimum is applied independently to bytes and file count. The configured
system value is a fallback for a dimension only when no enabled assigned role sets that
dimension; role limits may exceed it. The legacy `LUMIRA_FILES_MAXIMUM_BYTES` and
`LUMIRA_FILES_MAXIMUM_COUNT` environment names remain accepted as aliases for the system
fallback values. New deployments should use `LUMIRA_FILES_DEFAULT_BYTES` and
`LUMIRA_FILES_DEFAULT_COUNT`. Quota values must remain non-negative safe integers so the
browser can display and submit them without precision loss; this supported numeric range is
not a product hard cap.

Use the read-only preview endpoint before assignment:

```text
GET /api/platform/users/{userId}/file-quota/preview?roleIds={roleId}&roleIds={existingRoleId}
```

The normal role assignment endpoint continues to replace the full role list. Read the
current role IDs first, add the quota role, then submit the complete set with the returned
user version. Role assignment remains subject to the existing role read/write and user
scope checks. Do not copy a legacy value into a role without reviewing the before/after
preview: an explicit role limit is shared by every member of that role and multiple roles
combine by the lower limit per dimension.

## Runtime behavior and recovery

An unset role dimension adds no capacity. For each dimension, effective quota is the
minimum of all non-null limits on assigned, enabled roles; if none of those roles sets that
dimension, the system default is used. A role limit can therefore be higher than the system
default. Disabled or removed roles are ignored. Existing files remain readable and are not
deleted when a limit is lowered. Usage can remain above the limit; uploads are rejected
until usage falls within both limits. Usage reads and upload reservations use the same
resolver.

The V11 user columns remain the recovery source. To restore the previous application,
roll back the code version; its existing user-level values are still present. If a database
rollback also needs to remove quota roles, first export their role IDs and assignments,
then remove only the migration-created role links and roles after confirming that no new
users or policy edits depend on them. Keep the pre-rollout export and database backup as
the authoritative record of the original per-user values.
