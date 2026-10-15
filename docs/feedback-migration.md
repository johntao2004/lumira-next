# Feedback migration contract

Scope is the reusable work-order/feedback tool in the legacy system, not competition workflows. Source inventory: WorkOrderFeedbackController, WorkOrderFeedbackService, WorkOrderFeedbackDTO/VO, WorkOrderHtmlSanitizer, JdbcWorkOrderFeedbackRepository and upgrade-work-order-feedback-dictionary-v1.sql.

## Required retained capabilities

- Keep the compatibility owner read endpoints owner-only. The current page access grant and its READ, CREATE, UPDATE and REPLY operations are independent; all four operations are scope-aware.
- Managed list/detail uses the intersection of page ACCESS and READ target scopes. Status changes and replies additionally require the target to be visible under their respective UPDATE or REPLY scope.
- Title up to 160 characters, rich HTML detail up to 200,000 characters, priority selection, status, administrator reply up to 4,000 characters, handler/time and create/update times.
- Keyword, status and priority filters with real pagination. List metadata and body detail should use separate projections.
- Status and priority dictionaries and configured initial/default values. Existing defaults: OPEN/PROCESSING/RESOLVED/CLOSED; LOW/NORMAL/HIGH/URGENT. Terminal states have explicit semantics. Preserve configurability; do not silently hardcode away the dictionary feature.
- Rich text and images. Uploaded images require the shared file authorization/reference lifecycle, including administrator access only through an authorized feedback record.
- Optional module enable/disable, navigation and API enforcement through the new module framework.
- Processing concurrency protection, transactional audit and stable account identity. Add explicit versions rather than matching only the old status, which cannot distinguish concurrent reply edits.
- Reuse ResourceTable, FormDrawer/PlatformDrawer, unified API errors and eight-language messages.

## Implemented input boundary

`content/RichText` reuses the legacy limited OWASP HTML policy as a shared utility. `feedback/FeedbackContent` applies title/detail/reply constraints before future persistence. Rich formatting, links and valid images survive; scripts, CSS, handlers, frames and unsafe URL schemes do not. Decoded text is inspected through parser callbacks, so whitespace-only entities and images without a valid source cannot pass the required-content check. Validate both source and sanitized output bounds, because escaping can expand content.

The sanitizer dependency is pinned to 20260313.1, matching the existing repository and the official release page checked during this adaptation: https://github.com/OWASP/java-html-sanitizer/releases . No additional broad HTML rendering policy is introduced.

## Current adaptation

FeedbackStore uses the feedback schema and host-side seeds provided by the B52 initialization baseline; the optional `feedback` plugin retains its own V2–V4 permission and data migrations. The permission-enforcing `/api/feedback` adapter is wired to an independent `platform:feedback:access` page grant and separate `platform:feedback:read`, `platform:feedback:create`, `platform:feedback:update` and `platform:feedback:reply` operations; the page grant and every operation use scoped semantics. The compatibility `/api/feedback` list/detail routes remain mine-only. The `/api/feedback/manage` list filters owners through the intersection of the actor's ACCESS and READ scopes before applying SQL count, filters and pagination. Management detail uses the same intersection, while status and reply capabilities are calculated from the corresponding additional action scope. The Umi page reuses ResourceTable, FormDrawer/PlatformDrawer, the shared rich-text editor and the eight-language message catalog. Lists return metadata only; detail routes return sanitized HTML and administrator replies. Processing uses the record version and records a transactional audit event.

Feedback images now use the shared file store: JPEG/PNG input is normalized to PNG, quota and upload-rate limits apply, the editor supports button/paste/drop insertion, and `platform_feedback_file` binds uploaded files to the sanitized HTML record. Owner image reads follow the mine-only page-plus-READ path. A management image is readable only when its feedback record's owner is visible under both ACCESS and READ; detached or deleted owners require both grants to be global. Versioned account-file deletion and orphan cleanup refuse to remove referenced images.

Historical behavior: an earlier implementation stored feedback creation drafts through the account-draft protocol, using the `feedback.create` key and a versioned field whitelist. Account drafts have since been removed from the runtime and are absent from the current B52 schema; the current code does not expose the account-draft API or read/write those rows. No old global migration chain is shipped to carry those rows forward. Feedback creation continues to use the ordinary form, rich-text and image flows.

## Internal storage boundary

Creation choices at `/api/feedback/options` require page ACCESS and either READ or CREATE. A CREATE-only account can load choices and submit its own feedback without gaining list/detail reads. `/api/feedback/manage/options` still requires ACCESS and READ. Reply and status fields are submitted independently; neither operation grants READ or the other operation implicitly.

FeedbackStore is not an HTTP API and does not implement authorization by itself. The API facade checks module state, page ACCESS, and the relevant independent operation before calling it. Owner-store methods take an explicit non-null owner UUID derived from the authenticated account, and public owner endpoints stay mine-only. Management queries apply the intersection of ACCESS and READ target scopes before counting and paginating; a status update or reply also checks the matching action scope for that target. Management methods have separate names. Write identity pairs must match an enabled account. Deleted accounts leave historical records detached, and a new account with the same username cannot read them.

FeedbackPolicy is an immutable validated server snapshot. It accepts configurable status codes with terminal flags and priority/default codes. Production adapters must obtain this snapshot from protected dictionary/configuration data; it must never come from request input. Invalid explicit priorities are rejected rather than silently reassigned.

The B52 baseline includes the final host schema and feedback-related dictionary seeds. The database schema smoke checks the current schema and stable dictionary contents; plugin-specific permission migrations are tested separately against a database initialized from B52.

## Permission scope migration

The feedback plugin keeps V2–V4 in its own Flyway history. On a B52 host, V3 runs the plugin permission finalizer using the normalizer supplied by the baseline, then V4 applies the feedback-specific permission rules. Current `platform:feedback:read` is scope-aware; V4 preserves the old owner-only meaning of legacy READ grants rather than copying a broad source scope. Existing SELF-role READ grants remain. READ grants on non-SELF roles and direct READ grants are removed so they cannot turn into scoped or global access under the new contract. Existing `platform:feedback:access` grants are left as they were.

When an account had effective READ and page ACCESS before V4, the migration first preserves reads that came from a surviving SELF role or from a globally effective old READ_ALL, UPDATE, or REPLY grant. If no effective READ remains, it creates and assigns one ordinary `migrated-feedback-read-<user-id>` role with SELF scope and READ only. The role adds no ACCESS grant. Page authorization still requires the account's original ACCESS grant, so revoking that grant immediately denies page use even while the migration role remains assigned. Accounts whose only former access came from a disabled role, or who had an action without READ plus ACCESS, do not receive this role.

Globally effective legacy actions keep their implicit ability to read: a READ grant is added to the same ALL role or direct-user grant that carried the old READ_ALL, UPDATE, or REPLY action. Managed targets remain limited by the intersection of page ACCESS and READ scopes, and UPDATE or REPLY further intersects with that action's target scope. UPDATE and REPLY remain independent permissions, while READ_ALL is retired. Action grants from non-ALL roles were ineffective under the old policy; V4 archives and removes them without replaying them as active permissions. Page ACCESS is never expanded to match a global action.

The generated SELF roles are normal, independently editable roles. They are not linked to the former broad READ source: future assignment of that source role does not assign READ, and changing or removing a migration role is an explicit administrator action. V4 uses the B52-provided `platform_permission_migration_archive` database table for source and normalized-grant inspection, increments affected role and user versions, and clears saved role-preview contexts for affected users. A plugin-scoped completion marker makes a manual SQL replay a no-op after successful completion.

## Verified evidence

Historical verification before scope-aware permission migration and account-draft removal: FeedbackContentTest (4 tests), FeedbackCatalogTest (4 PostgreSQL tests) and FeedbackStoreTest (6 PostgreSQL tests) passed as part of a clean Maven run. The earlier live HTTP smoke covered module gating, the then-current global management path, normalized image upload, feedback references, image authorization, deletion protection, CSRF and body bounds, dictionary options, sanitized creation, owner isolation, metadata-only lists, version conflicts, terminal handling and revocation. The former browser smoke covered feedback creation, image insertion/upload, dictionary-driven filters, owner/administrator image detail, processing and 390px drawer bounds. These records describe the implementation at that time; current authorization behavior is described above.
