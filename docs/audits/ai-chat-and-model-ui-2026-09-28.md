# AI chat and model UI repair status — 2026-09-28

## Status

The AI chat and model-connection source changes are in place. The integrated runtime now renders both AI routes, and the mobile/table/session UI checks below passed where the available configuration allowed them. Chat generation, long transcript wrapping, and stop/error behavior remain **unverified** because no chat-capable model is configured; no provider request was sent.

## Scope and changes

- `apps/web/src/features/ai/ai-console-page.tsx`: makes the session list collapsible when the AI content container is narrow; uses Ant Design X `Bubble.List` content classes for long/unbroken messages; wires Sender cancellation to an `AbortController`, restoring the interrupted draft and the previous transcript state.
- `apps/web/src/features/ai/ai-models-page.tsx`: supplies explicit table scroll widths and column widths, wraps provider/model/state/action content, and bounds the edit modal to the viewport. Existing add, edit, test, delete, and save controls remain in place.
- `apps/web/src/app/globals.css`: adds AI-scoped container-query layouts and wrapping/width constraints for chat messages, composer controls, model table content, and the provider form.
- `apps/web/src/platform/api/client.ts`: allows a mutation to receive an optional abort signal, including the CSRF request, while retaining the 15-second request timeout.

No backend endpoint, database migration, dependency, or localization-key change was part of this task.

## Before-state browser evidence

The authenticated In-App Browser session was on the real `/dashboard/ai-console` and `/dashboard/ai-models` routes at **1280×720 CSS pixels**, with the main ProLayout sidebar expanded.

- AI console: the page body was 1280×804 (84 px vertical overflow, no horizontal overflow). Main content was 1024×748; the chat workspace was 944×540. The chat panel was 718×538.
- AI models: the page body and main content had no horizontal overflow. The model table container was 894 px wide and did not itself overflow before the layout changes.

The before screenshots were captured inline in the CUA session. That screenshot API did not provide a supported local-file save path, so no screenshot image is attached to this report.

## Earlier shared-preview blocker

After the source update, refreshing `/dashboard/ai-console` produced a white 1280×720 page with only the document root in the accessibility tree. Console errors included `Cannot find module 'umi'` in hot-update/runtime files and `SyntaxError: Unexpected token '<'`.

Read-only runtime inspection found that the frontend and API health endpoints still returned HTTP 200, while the Umi log contained repeated Mako `[icons] build failed` entries, transient `.umi/umi.ts` and `.umi/exports` resolution failures, and unresolved imports in other features (`users-page.tsx -> ./user-editor` and `settings/module.tsx -> ./watermark-page`). The generated `.umi/umi.ts` imported `ApplyPluginsType` from bare `umi`, but `apps/web/node_modules/umi` was absent. This indicates a shared generated hot-update graph problem; it does not establish an AI-page defect or prove the shared preview is usable.

The AI browser session was left at the blank route during that earlier pass. This task did not restart services or regenerate the preview.

**Integration follow-up (historical):** the coordinator reported that an HMR recovery attempt from another task had not improved the shared preview: `/umi.js` still returned HTML and the login page remained blank. At that time the AI QA session stayed untouched. After the integration owner later reported integration complete and services running, browser QA resumed as recorded below.

## Integrated runtime UI evidence

The integration owner reported that integration was complete, V12–V14 were applied, and services were running. This pass did not query migration history independently and did not build or restart the shared services.

Authenticated Edge UI checks ran against the current preview at `/dashboard/ai-console` and `/dashboard/ai-models`:

- **Wide / expanded-sidebar narrow content:** the AI console session list collapsed into its control and the provider/model controls stayed within the content area. The model table scrolled horizontally inside its container; scrolling exposed the row actions without visible page-level horizontal overflow.
- **400 px mobile viewport:** the model table retained its own horizontal scroll and its actions remained reachable. The edit modal fit the viewport width with side margins, scrolled vertically to expose the footer, and closed with Cancel. No field was changed or saved; the existing Jev provider record was left untouched.
- **Configured/enabled capability counts:** the model-management UI listed one configured provider/model record, Jev, with status enabled. Its read-only edit view showed `CHOICE`, `SCORE`, and `NOUL` capabilities; no `CHAT` or `EMBEDDING` capability was present. Counts are therefore **CHAT: 0; EMBEDDING: 0; other decision model: 1**. The table showed a saved-credential status, but the secret value was never revealed or read. These are configured profile counts, not a live remote model catalog; no connection test was run.
- **AI console at 400 px:** the page rendered the TypeSafe Jev capability notice. The only configured provider is a hosted structured-decision model, not a chat model. Jev was not selectable for chat; there was no usable chat provider, and the model selector and composer/send control were disabled. The page asked for a chat-capable model. No prompt was submitted and no hosted connection was tested.
- **Session controls:** expanding the session panel showed the current session and a New conversation control. Creating a new conversation changed the local heading to “新对话”. Reloading `/dashboard/ai-console` returned to the initial blank conversation, so this UI session state did not survive a page reload. No chat message or backend session was created.

The capability counts are not caused by a management-page DTO or table filter: `ai-models-page.tsx` uses the complete provider list as its table data source, and `AiProviderService.list()` maps all repository profiles. The chat selector intentionally requires an enabled, non-Jev provider with `CHAT`; Jev is shown as unavailable for chat because it is a decision model. Localization AI translation also has no compatible configured model: its provider endpoint and translate operation both require an enabled, non-decision provider with `CHAT`, a nonblank model name, and configured credentials. The sole Jev decision profile fails the capability and provider-kind checks. The localization AI page/request was not opened or invoked during this UI pass.

The current locale during this pass was Simplified Chinese (`zh-CN`). This task did not change the shared locale preference. The Edge tab’s direct click command timed out twice on the model action; native accessibility clicks worked after the AI QA tab was active, so those timeouts are not treated as product defects.

DevTools also showed the Ant Design Alert `message` deprecation warning and an `AbortError` from the installed Edge extension’s `inspector.js`; neither blocked app interaction.

No real business records were changed. There were no provider test calls, AI generation requests, uploads, email/SMS sends, or other external notifications. The existing 12 LOG_ONLY demos were not touched.

## Build, migration, and runtime evidence

- The integration coordinator reports that the unified **Node 24** frontend typecheck/build and backend verify passed after the final source window. This is shared integration evidence; this AI QA task did not run a separate build or restart.
- The shared verify record reports Maven `verify` with 287 tests, 0 failures, 0 errors, and 94 skipped. The skipped tests were database opt-in tests without their required database environment.
- Runtime evidence: both AI routes rendered and accepted the UI interactions described above in the current shared preview.
- Migration evidence: V12–V14 application was reported by the integration owner; it was not independently checked in this browser pass.
- Persistence evidence: model configuration was not edited; the newly created blank chat session reset on page reload.
- Chat generation, Sender cancellation/error recovery, long transcript rendering, and localization AI translation are blocked pending one usable chat-capable configured model. No secret was accessed and no model call was made.

## Acceptance still needed

After an enabled, credentialed, non-decision `CHAT` provider is configured, use the authenticated UI to check long CJK and unbroken transcript text, session switching, Sender stop/error behavior, conversation persistence, and localization AI translation. Until then, generation/cancel/long-transcript checks and localization AI translation remain blocked by the shared missing model configuration. The wide/narrow/mobile layout, model table scrolling/actions, and edit modal sizing were checked in the integrated runtime as described above.

## Design references

- [Ant Design X component introduction](https://ant-design-x.antgroup.com/components/introduce)
- [Ant Design X Bubble](https://ant-design-x.antgroup.com/components/bubble)
- [Ant Design X Sender](https://ant-design-x.antgroup.com/components/sender)
- [Ant Design X Conversations](https://ant-design-x.antgroup.com/components/conversations)

The installed `@ant-design/x` version is 2.7.0; the documentation pages rendered version 2.5.0 during this review. Local package types were also checked for the implementation API surface.
