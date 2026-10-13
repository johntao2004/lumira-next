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

## Migration and role editing

V50 remains an immutable historical migration; V51 converts its grants to normal page and action
authorities and removes the opaque source grants from authorization storage. Existing navigation
rows keep their placement and presentation settings while page permission keys move to the
page-owned access codes. Simple former read/write grants map independently to page access and
actions, so a write-only grant does not synthesize page access.

For a historical compound grant whose source components all existed on one role, V51 stores the
logical action as an ordinary grant on that role at its existing scope when the old global-source
requirements were met. Where components were combined across direct and role grants, V51
materializes the effective action as an ordinary scoped grant for that user. These are migration
results: runtime authorization, role editing, visible-user checks, and role previews read ordinary
grants and current scopes without consulting historical projection metadata. Plugin permission
catalog version 2 remains unchanged.

Role responses expose independent grants only in `authorities`. For request compatibility, a role
write may omit `legacyProjectionPermissions` or send it as null/empty; a non-empty value returns
HTTP 400 before the role is written. The field is never used for authorization or persistence and is
not included in responses. Existing unrelated unknown or retired ordinary grants remain stored and
can be preserved when editing a role, but cannot be newly assigned; retired grants do not become
effective. V51 archives the known former shared grants and all `platform:__v50:*` sources for
audit, then removes them from role and direct-user grant tables so they cannot be restored through
this compatibility field.

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
