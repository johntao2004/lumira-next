# Feedback migration contract

Scope is the reusable work-order/feedback tool in the legacy system, not competition workflows. Source inventory: WorkOrderFeedbackController, WorkOrderFeedbackService, WorkOrderFeedbackDTO/VO, WorkOrderHtmlSanitizer, JdbcWorkOrderFeedbackRepository and upgrade-work-order-feedback-dictionary-v1.sql.

## Required retained capabilities

- Account-owned creation, own list/detail, independent create/view permissions.
- Separate administrator list/detail and processing permission. Never infer administrator scope from a client parameter without checking the grant.
- Title up to 160 characters, rich HTML detail up to 200,000 characters, priority selection, status, administrator reply up to 4,000 characters, handler/time and create/update times.
- Keyword, status and priority filters with real pagination. List metadata and body detail should use separate projections.
- Status and priority dictionaries and configured initial/default values. Existing defaults: OPEN/PROCESSING/RESOLVED/CLOSED; LOW/NORMAL/HIGH/URGENT. Terminal states have explicit semantics. Preserve configurability; do not silently hardcode away the dictionary feature.
- Rich text and images. Uploaded images require the shared file authorization/reference lifecycle, including administrator access only through an authorized feedback record.
- Optional module enable/disable, navigation and API enforcement through the new module framework.
- Processing concurrency protection, transactional audit and stable account identity. Add explicit versions rather than matching only the old status, which cannot distinguish concurrent reply edits.
- Reuse ResourceTable, FormDrawer/PlatformDrawer, DraftActions, unified API errors and eight-language messages.

## Implemented input boundary

`content/RichText` reuses the legacy limited OWASP HTML policy as a shared utility. `feedback/FeedbackContent` applies title/detail/reply constraints before future persistence. Rich formatting, links and valid images survive; scripts, CSS, handlers, frames and unsafe URL schemes do not. Decoded text is inspected through parser callbacks, so whitespace-only entities and images without a valid source cannot pass the required-content check. Validate both source and sanitized output bounds, because escaping can expand content.

The sanitizer dependency is pinned to 20260313.1, matching the existing repository and the official release page checked during this adaptation: https://github.com/OWASP/java-html-sanitizer/releases . No additional broad HTML rendering policy is introduced.

## Current adaptation

FeedbackStore, V34–V38 migrations, dictionary policy resolution and the permission-enforcing `/api/feedback` adapter are now wired. The route is registered as an optional `feedback` module, is disabled through the same module state gate as the other optional modules, and exposes independent owner read/create permissions plus global management scope. The Umi page reuses ResourceTable, FormDrawer/PlatformDrawer, the shared rich-text editor and the eight-language message catalog. Owner and administrator lists intentionally return metadata only; detail routes return sanitized HTML and administrator replies. Processing uses the record version and records a transactional audit event.

Feedback images now use the shared file store: JPEG/PNG input is normalized to PNG, quota and upload-rate limits apply, the editor supports button/paste/drop insertion, and `platform_feedback_file` binds uploaded files to the sanitized HTML record. Image reads require the feedback read grant for an owned file or global feedback management for a referenced file. Versioned account-file deletion and orphan cleanup refuse to remove referenced images.

V43 enables draft persistence for the feedback creation form through the account-draft protocol. The stable key is `feedback.create`; the codec stores only title, priority and bounded sanitized HTML under schema version 1, rejects malformed or oversized payloads, restores after cancel/reopen, and clears only the consumed revision after a successful submission. Attachment metadata and permissions are never serialized into the draft. The shared `DraftActions` controls, conflict state and localized cleanup errors are reused from the account-draft foundation.

## Internal storage boundary

FeedbackStore is not an HTTP API and does not implement administrator grants by itself. The API facade must check independent read/create/manage permissions, global scope for management, and module state before calling it. Owner APIs require an explicit non-null UUID; management methods have separate names. Write identity pairs must match an enabled account. Deleted accounts leave historical records detached, and a new account with the same username cannot read them.

FeedbackPolicy is an immutable validated server snapshot. It accepts configurable status codes with terminal flags and priority/default codes. Production adapters must obtain this snapshot from protected dictionary/configuration data; it must never come from request input. Invalid explicit priorities are rejected rather than silently reassigned.

V34–V38 have been applied to the running PostgreSQL 18.6 instance and are covered by the migration-backed HTTP smoke. Existing feedback dictionary rows remain authoritative when their reserved codes already exist; the seed migration uses conflict-safe inserts.

## Verified evidence

FeedbackContentTest (4 tests), FeedbackCatalogTest (4 PostgreSQL tests) and FeedbackStoreTest (6 PostgreSQL tests) pass as part of the 216-test clean Maven run against the local PostgreSQL fixture, with zero failures, errors or skips. The live HTTP smoke covers module gating, independent grants/global scope, normalized image upload, feedback references, image authorization, deletion protection, CSRF and body bounds, dictionary options, sanitized creation, owner isolation, metadata-only lists, version conflicts, terminal handling and revocation. The browser smoke covers the Umi route, image insertion/upload, dictionary-driven filters, rich-text creation, reusable draft save/restore/cleanup, owner/administrator image detail, processing and 390px drawer bounds. The feedback public boundary is complete; dynamic business workflows and production delivery remain outside this scaffold.
