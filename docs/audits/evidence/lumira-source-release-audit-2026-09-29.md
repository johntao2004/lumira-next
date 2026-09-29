# Lumira Next source and release audit — 2026-09-29

## Scope

Read-only comparison of the main checkout at `/Users/johntao/Documents/GitHub/lumira-next` and the isolated QA source at `/private/tmp/lumira-next-qa-20260929`. Reviewed `apps/web/AGENTS.md` and the six assigned 2026-09-29 evidence notes. No browser, HTTP endpoint, database, running process, credential, provider, or user setting was accessed. The only source edits in this audit are in the isolated candidate.

## Findings and candidate-only repairs

- The personal files table at `apps/web/src/features/files/files-page.tsx` has no status/state data column. The admin table at `apps/web/src/features/files/admin-files-page.tsx:57` intentionally retains its `state` column. Both files are byte-identical between main and candidate (SHA-256 respectively `f29b434b…e572c6b06` and `5566f0cf…57ae4969`).
- The candidate frontend contains the model-discovery/model-selection work in `apps/web/src/features/ai/ai-models-page.tsx`, `model-discovery.ts`, `model-discovery.test.ts`, `ai-console-page.tsx`, `localization-page.tsx`, and `platform/i18n/messages.ts`. Main still has the manual model/capability form. Candidate also removes maintenance `endAt` and guards the user editor form until the drawer is open. Existing 8022 evidence reports one editor-open check without the earlier disconnected-Form warning; it is a single UI observation, not a zero-console claim.
- **Candidate SQL regression repaired:** candidate `backend/src/main/java/com/lumira/platform/organizations/OrganizationRepository.java` previously appended generated column predicates after the paged list's `ORDER BY/LIMIT/OFFSET`. Main already had the correction. I ported that ordering into the candidate and added the same narrow regression assertion as `backend/src/test/java/com/lumira/platform/organizations/OrganizationRepositoryQueryTest.java`.
- **Candidate route authorization repaired:** candidate exposed `POST /api/platform/ai/providers/discover-models`, `GET /api/platform/ai/providers/{id}/models`, and `GET /api/platform/localization/ai/providers/{id}/models`, but its `SecurityConfiguration` had no matching rules before `.anyRequest().denyAll()`. I added explicit method/path rules using the controllers' settings-read, settings-write, and localization-write authorities. I also carried over the current main rule for `DELETE /api/platform/localization/translations/*`, needed by the already-recorded localization restore flow.
- The same AI provider discovery/model controller routes exist in the main source, but main `backend/src/main/java/com/lumira/platform/identity/SecurityConfiguration.java` still lacks the corresponding explicit matchers. Main was left untouched as assigned. This is a source release blocker for those discovery calls until the rules are added to the main source and the resulting backend is built/deployed.
- The candidate is not a safe whole-tree replacement for main. Main includes later backend module-boundary work (`common/SecretStorePort`, `common/AiUserDirectoryPort`, user adapter, mail/SMS delivery services and moved controllers) absent from candidate. Use reviewed changes selectively after synchronization; promoting the candidate backend wholesale could roll back those repairs.

## Source, build, and runtime boundaries

- Main and candidate are separate source roots. `apps/web/dist/umi.js` on main is dated 2026-09-29 00:01 and is 17,645,530 bytes; candidate `apps/web/dist/umi.js` is dated 07:25 and is 18,898,306 bytes. The feature chunks differ: existing evidence records the 8010 file chunk at 24,068 bytes without preview/MB/download-confirmation markers and the 8020 candidate file chunk at 40,444 bytes with them; the AI chunks were 38,569 and 46,971 bytes respectively. These are historical bundle observations, not a current service check.
- The recorded 8020/8022 UI used the candidate frontend source. The corresponding isolated preview process ran from `/private/tmp/lumira-next-qa-20260929/apps/web`, while the recorded 8090 backend was the main workspace's patched JAR. Therefore those UI observations do not prove the candidate backend's route authorization. The latest note records 8010's process cwd as the main workspace and says no refreshed 8010 bundle was captured. No process or port was checked in this audit.
- Existing evidence records Node 24.21.0 `pnpm run typecheck` passing for candidate frontend, and `LocalizationAiServiceTest` passing 1/1. The candidate source adds `model-discovery.test.ts`, but the assigned evidence did not show that test being run.

## Checks performed in this audit

- From candidate `backend/`: `./mvnw -q -Dtest=OrganizationRepositoryQueryTest test` — exit 0; 1 test passed, 0 failures/errors/skips.
- From candidate `backend/`: `./mvnw -q -DskipTests compile` — exit 0 after the SQL and security-rule repairs.
- No full suite, frontend typecheck/build, browser acceptance, service restart, database action, or provider request was performed.

## Remaining gates

- CHAT remains unconfigured in the recorded evidence. The user said they would configure it in the UI; a saved, enabled, known-safe CHAT model and a later assigned UI session are still needed before discovery, chat generation/cancel/reload, or AI translation can be accepted. No credential or model call is requested by this source audit.
- Ordinary-user role/quota and feedback scope/recovery checks still need an approved ordinary QA identity and user-controlled password/role setup. The maintenance final gate is explicitly sequenced after that work and needs its own authenticated browser window.
- Authenticated 1920/2560/3840 viewport measurements were not obtained; the prior preview was at login and the available browser path lacked the required viewport override. Monitoring's true `visibilityState=hidden` pause/recovery is likewise unverified because the recorded browser never produced a hidden document.
- Permanent file purge and asynchronous cleanup remain user-deferred. No test fixture or user data was touched.

## Follow-up — main workspace repair and isolated runtime validation

- The two AI provider matchers noted above were added to main `SecurityConfiguration.java:115-117` using the existing `platform:settings:read` and `platform:settings:write` authorities. The deny-all fallback and CSRF configuration remain intact.
- Main targeted organization-query and delivery-security tests passed (3 total). `./mvnw -q -DskipTests package` passed; built runtime artifact SHA-256 is `d7ca9dc91c8df62546c92ae297c54c81c01056ebe52ce5e452ed325888fa8aa9`.
- The package was loaded into the existing 8090 process slot (PID `81412`), readiness HTTP 200. The 8010 web PID remained `76301`; no database migration or session configuration changed in this update.
- The administrator navigated to Model Access in isolated 8022 after the update and the saved provider table loaded. Only the Jev TypeSafe decision model is configured; it cannot satisfy CHAT model listing. No provider credential was read or sent, and no upstream discovery, chat generation, or translation was run. Those UI checks remain pending a CHAT-capable service under the user's current instruction to defer CHAT setup.
