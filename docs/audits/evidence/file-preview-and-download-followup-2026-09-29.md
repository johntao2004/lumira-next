# File preview and download follow-up — 2026-09-29

This note records the additional runtime and browser evidence collected on 2026-09-29. It does not replace the broader UI acceptance matrix in `../ui-acceptance-2026-09-28.md`.

## Runtime boundaries

| Surface | State during this check | Evidence |
|---|---|---|
| `8010` frontend | Existing PID `76301` kept running; no navigation or restart was performed. | Read-only async-module check: file chunk HTTP 200, 24,068 bytes, SHA-256 `92693f4a…aba5`, but it lacks `file-preview-link`, `formatFileSizeMb`, and `fileDownloadConfirm`. AI chunk HTTP 200, 38,569 bytes, SHA-256 `3b0dac37…9414`, still contains `providerCapabilities`/`capabilityCodes` and lacks `modelSelectionIsValid`. These are old feature bundles despite health HTTP 200. |
| `8020` frontend | Isolated QA copy at `/tmp/lumira-next-qa-20260929/apps/web`; used for the actual preview interactions. | Read-only async-module check: file chunk HTTP 200, 40,444 bytes, SHA-256 `b26b9c86…b69c`, with the preview/MB/download-confirmation markers. AI chunk HTTP 200, 46,971 bytes, SHA-256 `88523bec…3487`, with the patched discovery validator and without the manual capability selector. |
| `8090` backend | PID `24256`, running `.runtime/platform-server-20260929-ai-preview-auth-patch.jar`; readiness `UP`. | Readiness endpoint returned HTTP 200 after the controlled backend restart. The frontend PID was unchanged. |

The browser results below apply to the isolated `8020` frontend and the patched `8090` backend. The current checks specifically confirm that `8010` still serves the old file and AI feature bundles; the original user-facing entry has not been updated.

## Preview authorization repair

`GET /api/account/files/{id}/preview` was previously missing from the Spring Security matchers and reached `.anyRequest().denyAll()`, producing HTTP 403 for an authenticated file owner. The security configuration now has an exact authenticated matcher for `GET /api/account/files/*/preview`; the existing file-owner and `READY` checks remain in `FileService`.

After packaging and deploying the patched backend, these targeted tests passed: `ModelConnectionServiceTest`, `AiProviderCapabilityGateTest`, `FilePreviewReaderTest`, `FileServiceTest`, and `FileCleanupTest` — 26 tests, 0 failures, 0 errors.

## Actual browser preview results

The operator used the visible file list and clicked file-name links in the isolated `8020` browser session. No preview request was substituted with a direct API call.

- `preview-source.doc`, `preview-source.docx`, `preview-test.txt`, and `preview-test.md` each displayed readable content including `SAMPLE-CONTENT-42`. Closing and reopening each preview showed its content again. The Markdown sample visibly rendered its heading, bold marker, and code block.
- The short samples were too short to establish long-document scrolling.
- `preview-long.txt` was uploaded once through the page's file chooser. It contains 1,500 synthetic Chinese lines, 124,364 bytes, and 46,490 Unicode code points. Its unique ID is `71a15c44-786e-4ead-b87b-14bfc2c7c182` and it was `READY` at the time of the read-only lookup.
- In the visible preview, the operator saw the head marker and first lines, scrolled to the middle marker around lines 727–752, then to the tail marker around lines 1,475–1,499. Returning to the start and closing/reopening showed the head again. This is browser-visible scrolling evidence, not only a full-text accessibility-tree check.
- The four short samples were moved to the recycle bin through the page. The operator reported the visible file count changed `6 → 5 → 6` during a trash/restore cycle and that the four short samples later appeared in the recycle bin. Trash continues to count toward quota. No permanent purge was performed.

### Synthetic preview fixtures

| File | ID | Last reported state |
|---|---|---|
| `preview-source.doc` | `d21ceabc-665e-4816-9c45-c5a942d6ab2d` | Recycle bin |
| `preview-source.docx` | `54493ee6-e8d4-4d44-b352-7dca70bf5758` | Recycle bin |
| `preview-test.txt` | `d990e793-76b7-4064-9c55-821965e1eb6a` | Recycle bin |
| `preview-test.md` | `3bda56ed-b497-49d3-a3b7-085e2632c0b0` | Recycle bin |
| `preview-long.txt` | `71a15c44-786e-4ead-b87b-14bfc2c7c182` | `READY` at the last exact lookup |

These IDs identify only the synthetic files created for this acceptance run. Permanent deletion still requires the user's confirmation at the UI confirmation step.

## Browser download acceptance

At 2026-09-29 16:52 Asia/Shanghai, an isolated Chromium context on `8020` used the visible login form, restored the exact synthetic TXT through the recycle-bin UI, and opened its row download confirmation. Cancel produced no matching page-scoped request and no download event. One subsequent confirmation emitted a browser-context `download` event; the browser saved `/tmp/lumira-preview-smoke-20260929/download-proof-preview-test.txt` as `preview-test.txt`.

The saved file is 144 bytes and its SHA-256 is `b87ee942d7e783d135610ebfa51b503e868e87dcca617b8a43e0656a0e262a0f`, matching the local synthetic source byte-for-byte. `Download.failure()` returned null. Playwright's page-scoped request/response observer did not expose the attachment URL or HTTP status in this run, so exact request count and status code remain unverified; the browser download event and matching saved content do verify that the UI flow delivered the file. Playwright documents attachment downloads through its page/context `download` event and temporary-file APIs.

After capture, the exact file was moved back to the recycle bin through the page and verified absent from the active file list. No permanent purge was performed. The file's source audit has no download action, so its audit rows do not add request evidence.

Source inspection shows `FileDownloadAction` creates a same-origin anchor with `download=""` only from the confirmation callback (`apps/web/src/features/files/file-download-action.tsx:16-24`). The backend content handler reads the current user's file and returns `application/octet-stream`, `Content-Length`, UTF-8 `Content-Disposition: attachment`, `nosniff`, and `no-store` (`backend/src/main/java/com/lumira/platform/files/FileController.java:39-44`); Spring Security requires authentication for `/api/account/files/*/content` (`backend/src/main/java/com/lumira/platform/identity/SecurityConfiguration.java:80`). The exact `FileDownloadAction` source in the isolated copy matches the main source; no product code change was retained.

## AI model form integration boundary

The isolated `8020` source now has the AI provider model-discovery patch, with one custom validator for an empty or unselected model rather than duplicate `required` and custom errors. Node 24 `tsc --noEmit` passed. The served AI async module returned HTTP 200 (46,971 bytes) and contained the new model-discovery validator without the manual provider-capability selector.

The final 11-file patch, including this validator fix, is at `/tmp/lumira-ai-form-isolated-20260929/ai-model-form-final.patch`; a `patch --dry-run -p1` against the main workspace succeeded for all 11 targets. It has **not** been applied to the main source or `8010`, to preserve the user's model configuration page state. A read-only query of public provider fields (name, kind, model, capabilities, enabled; no URL or secret fields) at 16:53 Asia/Shanghai, before the latest user reply, found one enabled `TYPESAFE_JEV / jev-latest` provider with `CHOICE`, `SCORE`, and `NOUL`; enabled CHAT provider count was 0. The user has since replied that they will configure a chat model through the page. The save state has not yet been observed; recheck public model metadata only after the user reports that configuration is saved. The visible model form and real conversation/translation remain pending that UI handoff.

## Remaining acceptance items

- User-created synthetic QA account: the computer-use confirmation policy requires the user to enter and submit any new password, and to confirm the exact permission changes. The user form is prepared with synthetic username `qa_user_20260929` and display name `QA普通用户20260929`; its password remains blank and the account has not been created. The planned `qa_storage_20260929` and `qa_count_20260929` roles do not exist yet.
- Ordinary-user role/quota upload boundaries, feedback scope/recovery, and ordinary profile-value persistence.
- Monitoring: current `8020` UI confirms nine options, realtime polling at about three seconds, manual mode with no scheduled request over 10.3 seconds, manual refresh 200, and a controlled 503 recovery click returning 200. The visible Notifications link stopped summary requests; browser Back returned to Monitoring with the displayed `每 5 秒` setting and a 200 summary request within 8.5 seconds. Headful isolated Chrome testing with Playwright focus emulation disabled observed `visibilityState=visible, hasFocus=false` for a full 5.7-second sample; one summary request returned 200, and a focused return produced another 200 within 5.7 seconds. However, switching pages and minimizing the test window never produced `visibilityState=hidden`: `Browser.getWindowBounds` confirmed `windowState=minimized`, while the page still reported `visible` and continued polling at the five-second cadence. The true hidden-pause behavior therefore remains unverified in this browser setup; no document visibility property was injected or overridden.
- Apply the AI patch to the main source/`8010` after a safe page handoff, then verify the correct runtime bundle.
- CHAT-model discovery, conversation, and AI translation remain blocked until a chat-capable model has been saved through the UI and verified without opening or exposing its credential.
- The user explicitly deferred permanent deletion for this run. Retain `preview-source.doc` (`d21ceabc-665e-4816-9c45-c5a942d6ab2d`), `preview-source.docx` (`54493ee6-e8d4-4d44-b352-7dca70bf5758`), `preview-test.txt` (`d990e793-76b7-4064-9c55-821965e1eb6a`), `preview-test.md` (`3bda56ed-b497-49d3-a3b7-085e2632c0b0`), and `preview-long.txt` (`71a15c44-786e-4ead-b87b-14bfc2c7c182`), together with the two original baseline files. Do not permanently delete/purge these objects, delete storage rows directly, clear the recycle bin, or shorten retention. The async-purge/usage-refresh page scenario is **user-deferred this run**. Other synthetic cleanup is still separate and must not be inferred from this decision.

## Runtime bundle state at 2026-09-29 16:53 Asia/Shanghai

- `8010` remains the original frontend process and returns HTTP 200. Its file page chunk is 24,068 bytes (SHA-256 `92693f4aea7d9039d41bda2d8ef371ab37a5d7f8147322636556aa1393dcaba5`) and lacks the preview/size/download-confirmation markers. Its AI chunk is 38,569 bytes (SHA-256 `3b0dac37e27d0ec2badaddc0ada8a8167fd2dc46e8df8920580fccb95e349414`) and still has the old capability selector logic. No navigation, reload, or hot update was made in that window.
- `8020` isolated QA frontend returns HTTP 200 and serves the updated file chunk (40,444 bytes, SHA-256 `b26b9c86d756ad1622150789fd29030dbdcd417cacda22cfe5b984d7ccf6b69c`) and AI discovery chunk (46,971 bytes, SHA-256 `88523bec6adcee6b97fc4c62a4baf4c22407b4298b0de2d55aa1457e33413487`). The monitoring chunk served 81,543 bytes (SHA-256 `95cb2dc4987c47d4d4b592d2e422c160500219f68c560c2a6678694102b9c9a4`).
- `8090` is running the patched backend jar `.runtime/platform-server-20260929-ai-preview-auth-patch.jar`; readiness is HTTP 200 / `UP`. The frontend and backend health result does not mean the changes are present on `8010`.

## Monitoring native-window visibility follow-up — 2026-09-29

Evidence files: `/tmp/lumira-monitor-route-return-20260929.json`, `/tmp/lumira-monitor-headful-visibility-20260929.json`, `/tmp/lumira-monitor-passive-visibility-20260929.json`, and `/tmp/lumira-monitor-minimize-resume-20260929.json`.

- In headful Chrome, the test signed in through the visible login form. Monitoring displayed `每 5 秒`. The Notifications menu stopped scheduled summary requests for 6.5 seconds; browser Back returned to Monitoring, and one 200 summary request arrived within the next 8.5 seconds.
- Passive CDP state reads (with `Emulation.setFocusEmulationEnabled=false`) recorded the Monitoring page changing from visible/focused to visible/unfocused when the second test page was brought forward. It remained visible/unfocused over the next 5.7 seconds, during which one summary request returned 200. Bringing Monitoring forward restored visible/focused state, followed by one 200 request within 5.7 seconds.
- A headful-window check confirmed there was no `--headless` launch flag. On the Monitoring run, after issuing the minimize command, the page continued to report `visibilityState=visible, hasFocus=false` and summary requests continued at roughly five-second intervals. A separate blank-page check confirmed the same CDP command changes a native test window from `normal` to `minimized` and back, while its document still reports `visible` after both transitions.
- This environment therefore verifies off-route stopping, browser-Back recovery, and polling while visible but unfocused. It does **not** verify pause/resume for a document reporting `visibilityState=hidden`, because neither native tab switching nor native window minimization produced that state. No visibility property was changed, and this limitation is not evidence that all browsers behave the same way.

## Latest configuration and QA identity check — 2026-09-29 17:23 Asia/Shanghai

- User said they will configure a chat model through the page. A fresh local PostgreSQL `BEGIN READ ONLY` transaction selected only `provider_kind`, `capability_codes`, and `enabled` from `platform_ai_provider`; it found one enabled `TYPESAFE_JEV` provider with `CHOICE/SCORE/NOUL`, and zero enabled providers whose capabilities contain `CHAT`. No provider URL, model credential, ciphertext, authorization data, or hash was selected or printed. The transaction ended with `ROLLBACK`.
- The same read-only transaction queried only `username`, `display_name`, `enabled`, and whether any row exists in `platform_user_role` for the exact synthetic username `qa_user_20260929`; no row was returned. No account or role was created or changed.
- `node scripts/status.mjs` reported frontend PID 76301 and backend PID 24256 running, with HTTP 200 from both `/user/login` on 8010 and backend readiness on 8090. The current Edge/8010 model form was not read, navigated, refreshed, or edited. These checks are metadata/runtime evidence only, not browser acceptance for chat or ordinary-user access.
