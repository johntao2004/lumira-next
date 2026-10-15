# Platform page and operation authorization

The host platform uses a versioned page catalog in `PlatformPermissionCatalog` and stable IDs in
`PlatformPageId`. Each managed page owns one access grant, `platform:<page-id>:access`. Each
independent action owns one operation grant, `platform:<page-id>:<action>`. A request that performs
an operation requires both the owning page grant and that operation grant. Reading a page or its
ordinary query data requires page access alone. Grant editing never adds or removes another grant
implicitly.

`PlatformOperation` is the server-side source for action ownership and scope. The catalog response
includes `id`, `path`, `accessPermission`, `operationPermissions`, `accessScope`, and
`operationScopes`; catalog version 2 records the independent-grant semantics. Plugin pages use the
same DTO shape but their codes remain owned by their plugin. Authorization comes from typed server
context (`AdministrationPort`, a trusted actor, and a typed page/action), never from `Referer`, a
client-supplied page header, or arbitrary request metadata.

## Default roles and grant origin

The B52 baseline seeds two assignable roles: `admin` (“管理员”, ALL scope) and `user` (“普通用户”,
SELF scope). The administrator role receives the explicit platform and installed-plugin management
grants; the ordinary role receives only `platform:feedback:access`, `platform:feedback:read`, and
`platform:feedback:create`. B52 does not create accounts or seed rows in `platform_authority`.
First startup creates only the `admin` bootstrap account and binds it to the `admin` role, so its
permissions have a role-grant origin rather than system-reserved per-user grants. No ordinary
account is pre-created. Registration remains disabled by default and selects the `user` role if it
is later enabled.

`platform_role_authority` stores role grants and `platform_authority` stores exceptional
per-account grants. They both participate in effective authorization, but they remain separate
sources when editing roles or assigning a role to an account. A user-specific grant must not be
treated as a system role or copied into the ordinary role implicitly.

## Managed host catalog

Unless marked scoped below, a page access grant and its operations are global. Operation scope is
global unless noted. The `platform:` prefix is omitted in the table for readability.

| Page ID | Access grant | Independent operations |
| --- | --- | --- |
| `users` | `users:access` (scoped) | `create`; `update` (scoped); `delete` (scoped); `reset-password` (scoped); `assign-roles` (scoped); `assign-organizations` (scoped); `read-roles`; `export` (scoped) |
| `online-users` | `online-users:access` | `terminate-session` |
| `roles` | `roles:access` | `create`; `update`; `delete`; `preview`; `read-authentication-policy`; `update-authentication-policy` |
| `organizations` | `organizations:access` (scoped) | `create`; `update`; `delete` |
| `audit` | `audit:access` | — |
| `mail-deliveries` | `mail-deliveries:access` | — |
| `sms-deliveries` | `sms-deliveries:access` | — |
| `event-outbox` | `event-outbox:access` | `replay` |
| `monitoring` | `monitoring:access` | — |
| `api-docs` | `api-docs:access` | — |
| `dictionaries` | `dictionaries:access` | `create`; `update`; `delete`; `import`; `export` |
| `branding` | `branding:access` | `update` |
| `smtp` | `smtp:access` | `update`; `test` |
| `sms` | `sms:access` | `update`; `test` |
| `profile-fields` | `profile-fields:access` | `update`; `read-dictionaries` |
| `agreements` | `agreements:access` | `update` |
| `watermark` | `watermark:access` | `update` |
| `maintenance` | `maintenance:access` | `update` |
| `security` | `security:access` | `update` |
| `authentication` | `authentication:access` | `update` |
| `passkey` | `passkey:access` | `update` |
| `wechat` | `wechat:access` | `update` |
| `captcha` | `captcha:access` | `update` |
| `registration` | `registration:access` | `update`; `read-roles`; `assign-default-role` |
| `file-retention` | `file-retention:access` | `update` |
| `ai-console` | `ai-console:access` (scoped) | `read-users` (scoped); `create-user`; `update-user` (scoped); `delete-user` (scoped) |
| `ai-models` | `ai-models:access` | `create`; `update`; `delete`; `test` |
| `modules` | `modules:access` | `install`; `update`; `uninstall` |
| `navigation` | `navigation:access` | `create`; `update`; `delete` |
| `files-all` | `files-all:access` | `delete`; `purge`; `preview`; `download`; `restore` |
| `notifications` | `notifications:access` | `create` |

The frontend route IDs equal the page IDs in this catalog. A scoped page limits the ordinary data
visible to the caller; a scoped operation further intersects target visibility with the operation's
own data scope. Some limited option/value reads are part of their independent operation (for
example, role choices while assigning roles); a read-only action can also authorize that lookup
without enabling writes. Module list and plan reads use `modules:access`; install, update, and
uninstall remain separate operations.

## Feedback plugin contract

The feedback page uses the scoped `platform:feedback:access` grant and four independent scoped
operations: `platform:feedback:read`, `platform:feedback:create`, `platform:feedback:update`, and
`platform:feedback:reply`. Scopes use the existing SELF, ORGANIZATION, SUBTREE, and ALL role data
scopes. Its compatibility
`/api/feedback` owner list/detail routes remain mine-only. The managed list requires page ACCESS and
READ, filters records to owners visible through the intersection of those two target scopes, and
applies SQL filtering, counting, and pagination after that authorization predicate. Managed detail
uses the same ACCESS∩READ target scope. A status change additionally requires UPDATE visibility for
the target; a reply additionally requires REPLY visibility. Create is separately granted and always
creates a record owned by the authenticated account. Managed detail returns `canUpdateStatus` and
`canReply` capabilities based on the target's action scope; writes check those grants again.
Feedback image access follows the associated record's owner visibility, with deleted or detached
owners available only when both ACCESS and READ are global.

Feedback plugin migration V4 retires the old READ_ALL code, retains existing SELF READ grants, and
removes broad role or direct READ grants so the old owner-only behavior cannot become broader under
scope-aware READ. If an account previously had READ and ACCESS but no READ remains after preserving
safe global legacy actions, V4 assigns an ordinary SELF role containing READ only; it does not add
ACCESS. Legacy READ_ALL, UPDATE, or REPLY grants that were globally effective retain implicit READ
on the same ALL role or direct grant. Ineffective action grants from non-ALL roles are archived and
removed without activation. The generated role is independently maintained and must be assigned
explicitly to future users.

## Current permission schema and role editing

The B52 baseline defines the current page/action permission catalog, its scope-aware views and
functions, and the metadata used to interpret known legacy permission components. The former
global incremental migration files are not shipped or run, and the repository does not upgrade
databases carrying the old global migration history; initialize a dedicated database using the
documented B52 procedure instead. The current state keeps page access separate from operations,
so a write-only grant does not synthesize page access.

When the B52 permission-normalization function sees known legacy components, it preserves a
logical action as an ordinary grant at the corresponding role scope or materializes an effective
scoped grant for the user when its source components came from direct and role grants. The
function records handled source grants in `platform_permission_migration_archive` and removes
hidden `platform:__v50:*` projection grants from active authorization storage. That database table
is part of the B52 schema; it is not a retained SQL migration archive. Runtime authorization, role
editing, visible-user checks, and role previews read ordinary grants and current scopes without
consulting projection metadata. Plugin permission catalog version 2 remains unchanged.

Role responses expose independent grants only in `authorities`. For request compatibility, a role
write may omit `legacyProjectionPermissions` or send it as null/empty; a non-empty value returns
HTTP 400 before the role is written. The field is never used for authorization or persistence and is
not included in responses. Existing unrelated unknown or retired ordinary grants remain stored and
can be preserved when editing a role, but cannot be newly assigned; retired grants do not become
effective. B52's normalization function records recognized former shared grants and all
`platform:__v50:*` sources in the permission archive table before removing them from role and
direct-user grant tables, so they cannot be restored through this compatibility field.

Role delegation requires global authority for current active grants and for any new grant. Role
preview intersects the target role's ordinary grants and data scope with the actor's current
effective grants and scopes; current role assignments and organization membership are read when the
preview is checked.

## Deliberately ungranted routes

Self-service routes such as the workspace/home, personal files, and account settings do not appear
in the managed catalog. Credential recovery and forced-password-change paths also stay available
through identity policy rather than an administrator-controlled page grant; making those flows
grantable could lock a user out of the account needed to repair authorization. They still use their
normal authentication, ownership, and request-context checks.

## Verification contract

`PlatformHttpPermissionPolicy` declares each protected host endpoint's page and required operation
set. Endpoint tests compare the policy against the operation catalog. Authorization tests cover
page-only, action-only, unrelated-page, sibling-action, and complete page/action grants. Migration
regressions use a disposable database and verify archived-source conversion to normal grants,
scoped/global outcomes, preservation of unrelated stored grants, revocation without resurrection,
and explicit scoped operations.
