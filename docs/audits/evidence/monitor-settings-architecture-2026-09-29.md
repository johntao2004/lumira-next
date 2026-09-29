# Monitoring, settings, and module-boundary evidence — 2026-09-29

## Module-boundary repair

The original command failed on a real undeclared dependency:

```text
$ python3 scripts/module-boundaries-smoke.py
module boundary check failed: undeclared backend module dependency platform -> settings: backend/src/main/java/com/lumira/platform/ai/AiProviderService.java
```

`settings` already depends on `platform`, so declaring `platform -> settings` would create a cycle. The repair introduces `common.SecretStorePort`; the settings-owned `SecretStore` repository implements it, and AI/model/localization consumers depend on that port. The follow-up smoke exposed a second real `platform -> directory` edge in `AiAssistantService`. Since the required directory module also depends on platform, the repair introduces `common.AiUserDirectoryPort` and a users-module adapter delegating to the existing `UserService` authorization, validation, auditing, and persistence paths.

Final smoke:

```text
$ python3 scripts/module-boundaries-smoke.py
module boundary check passed: 15 backend descriptors, 52 owned controllers, frontend registry contract
exit 0
```

No module dependency descriptors, package ownership rules, allowlists, or guard behavior were weakened or changed. The smoke script was strengthened only by requiring `SecretStorePort` to be implemented by `settings/SecretStore.java` and `AiUserDirectoryPort` by `users/AiUserDirectoryAdapter.java`.

Changed files:

- `backend/src/main/java/com/lumira/platform/common/SecretStorePort.java` — shared credential-store contract and state projection.
- `backend/src/main/java/com/lumira/platform/settings/SecretStore.java` — implements the shared port; database/encryption behavior remains in settings.
- `backend/src/main/java/com/lumira/platform/ai/AiProviderService.java`, `backend/src/main/java/com/lumira/platform/ai/ModelConnectionService.java`, and `backend/src/main/java/com/lumira/platform/localization/LocalizationAiService.java` — inject the shared secret-store port.
- `backend/src/main/java/com/lumira/platform/common/AiUserDirectoryPort.java` — assistant search/create/update/delete contract and safe account projection.
- `backend/src/main/java/com/lumira/platform/users/AiUserDirectoryAdapter.java` — maps the port to existing `UserService` operations.
- `backend/src/main/java/com/lumira/platform/ai/AiAssistantService.java` — uses the common contract instead of importing users-module implementation types.
- `backend/src/main/java/com/lumira/platform/settings/SmtpSettingsService.java`, `backend/src/main/java/com/lumira/platform/settings/SmsSettingsService.java`, and `backend/src/test/java/com/lumira/platform/settings/SecretStoreIntegrationTest.java` — use the shared secret-store state type.
- `backend/src/test/java/com/lumira/platform/users/AiUserDirectoryAdapterTest.java` — verifies safe search projection and delegation of assistant writes to `UserService`.
- `scripts/module-boundaries-smoke.py` — asserts the two new port adapters exist.

## Checks

- `python3 scripts/module-boundaries-smoke.py` — passed after both repairs: 15 descriptors, 52 owned controllers, frontend registry contract.
- `./mvnw -q -DskipTests compile` from `backend/` — passed after the final Java source changes.
- `./mvnw -q -Dtest=AiUserDirectoryAdapterTest test` from `backend/` — 2 passed, 0 failed/errors/skipped.
- `./mvnw -q -Dtest=SecretStoreIntegrationTest test` from `backend/` — 4 skipped because `LUMIRA_TEST_DB_URL` is unavailable. This establishes test-source compilation only, not secret-store integration behavior.
- `pnpm --dir apps/web typecheck` — passed. No monitoring frontend source changed in this run.
- Node assertions against `monitoring-refresh-interval.ts` — passed for all fixed intervals, realtime, manual, in-flight suppression, 5s exponential backoff through its 60s cap, and return to base interval at failure count zero. This is a pure helper check, not a React Query or browser request test.

## Monitoring source review and outstanding browser evidence

Current `monitoring-page.tsx` gates the query on `document.visibilityState === "visible"`, uses `refetchIntervalInBackground: false`, disables scheduled polling in manual mode, and unmounts with route navigation. `monitoring-refresh-interval.ts` maps realtime to 3 seconds and backs off from fetch-failure count, capped at 60 seconds (or the selected interval when it is longer). No source defect was found or changed during this run.

No browser was used, no server was restarted, and no request-trace instrumentation was added. Exact attributed request cadence, true hidden pause and visible recovery, visible-but-unfocused polling, route-leave cessation and return recovery, and controlled failure feedback/recovery remain unverified in this run. The helper assertions do not replace these checks.

## Settings and delivery sink

No settings UI values were changed in this run. Prior UI evidence in `03-ops-settings-locale.md` records the website name and watermark save/consumer/refresh/restore loops; the remaining current-turn low-risk settings flow was not exercised. Maintenance remained untouched.

Read-only sink discovery found no existing local SMTP/SMS sink files or listener. The SMTP settings page exposes only `STARTTLS` and `TLS`, no authentication-free plaintext option, and no test-send control. The SMTP transport requires server-identity checking; connecting the plain test sink below through product UI would require a supported trusted local TLS configuration. `SmsGateway` requires a non-empty bearer credential, so no authentication-free local SMS path is available. No app SMTP/SMS configuration was saved and no message was sent through Lumira.

A temporary SMTP capture fixture is available at `.runtime/local-delivery-sink/smtp_sink.py`. It binds only `127.0.0.1:1025`, accepts only sender addresses at `lumira.test` and the synthetic recipient `qa@lumira.test`, and writes captured messages to `.runtime/local-delivery-sink/captured-messages.jsonl`. A loopback-only `smtplib` self-test captured marker `QA_LOCAL_SINK_SELFTEST_20260929_A`; the record shows peer `127.0.0.1` and the exact recipient in that file. This proves the local receiver/capture path only, not product delivery. The listener was stopped after the self-test; a subsequent `lsof -nP -iTCP:1025 -sTCP:LISTEN` found no listener.

## Runtime and UI limits

The application was not rebuilt into its running JAR and neither service was restarted. The source compile and browser UI evidence are separate. No current-turn UI save, consumer, refresh, or restore flow was performed; monitoring page UI behavior and local app-to-sink delivery remain for a coordinator-assigned browser/runtime window. The maintenance test remains reserved for the final global-maintenance handoff.

## Isolated integration and local delivery self-tests — 2026-09-29

### SecretStore integration DB isolation

Before enabling the opt-in suite, `SecretStoreIntegrationTest` was inspected. It is gated by `LUMIRA_TEST_DB_URL`; each of its four tests creates a UUID-named `qa_secret_<uuid>` schema, runs only the archived `V23__encrypted_secrets.sql` fixture (one `platform_secret` table), and drops that schema with `CASCADE` in `@AfterEach`. It does not run Flyway or target the application's normal schema. The previously recorded run with no test URL skipped all four tests; that was not counted as integration behavior.

An isolated PostgreSQL target was prepared without reading application settings or passwords. The only initial connection was explicitly to the local maintenance database `postgres` at `127.0.0.1:5432`, with `PGPASSFILE=/dev/null`; metadata returned `postgres|127.0.0.1|5432|johntao|t` for database, server address, port, current role, and that role's `CREATEDB` capability. A fresh database named `qa_secret_it_20260929_a7ea492ffc86460cb4514a0f88b0c0d3` was confirmed absent and created there. Before testing, a connection explicitly to that new name returned `qa_secret_it_20260929_a7ea492ffc86460cb4514a0f88b0c0d3|127.0.0.1|5432|johntao|public`; its non-system table count was zero. No Lumira application or development database name or credential was used.

Exact test command (from `backend/`):

```sh
env -u PGPASSWORD -u PGHOSTADDR -u PGPORT -u PGUSER -u PGDATABASE -u PGSERVICE -u PGSERVICEFILE -u LUMIRA_TEST_DB_URL -u LUMIRA_TEST_DB_USER -u LUMIRA_TEST_DB_PASSWORD PGPASSFILE=/dev/null LUMIRA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/qa_secret_it_20260929_a7ea492ffc86460cb4514a0f88b0c0d3 LUMIRA_TEST_DB_USER=johntao LUMIRA_TEST_DB_PASSWORD= ./mvnw -Dtest=SecretStoreIntegrationTest test
```

Exit code 0; Maven reported `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. The test emitted the expected warning that `LUMIRA_SECRET_KEYS` is not configured; its fixture supplies test-only keys directly.

After the suite, the disposable database reported zero `qa_secret_%` schemas and zero non-system tables. It was dropped by name while connected only to `postgres`; a follow-up `pg_database` existence check returned `f`. No disposable DB or schema remains.

### SMTP TLS and SMS webhook local fixtures

The SMTP self-test creates a short-lived self-signed `localhost` certificate under JUnit `@TempDir` using `keytool`. Its custom trust is confined to the test's `SSLContext`; it does not change Keychain or global trust. Its SMTP server binds `127.0.0.1` on an ephemeral port and accepts only the synthetic fixture credentials/recipient. Exact command (from `backend/`):

```sh
env -u LUMIRA_TEST_DB_URL -u LUMIRA_TEST_DB_USER -u LUMIRA_TEST_DB_PASSWORD ./mvnw -Dtest='SmtpTlsTest#realProviderDeliversUtf8OverImplicitTlsAndStartTls' test
```

Exit code 0; `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. Both implicit TLS and STARTTLS flows captured the expected synthetic UTF-8 message through loopback. No real email was sent and no product settings were read or changed.

`SmsGatewayTest.webhookCarriesOnlyTheProviderContractAndAcceptsSuccess` now creates a per-run 32-byte random synthetic bearer in the test process, sends it only to an ephemeral `HttpServer` bound to `127.0.0.1`, verifies the captured Authorization header and synthetic message body, and verifies the credential byte array is cleared. The test has no dependency on app settings or credentials. Exact command (from `backend/`):

```sh
env -u LUMIRA_TEST_DB_URL -u LUMIRA_TEST_DB_USER -u LUMIRA_TEST_DB_PASSWORD ./mvnw -Dtest='SmsGatewayTest#webhookCarriesOnlyTheProviderContractAndAcceptsSuccess' test
```

Exit code 0; `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. This proves the local gateway transport/capture path only; it does not prove app configuration or delivery through an SMS provider.

### Boundaries still unverified

These were isolated source-level integration tests, not product UI/runtime checks. No browser was used, no current Lumira service or development database was contacted, and no service was restarted or refreshed. SMTP/SMS settings were not saved in the product. Local app-to-sink delivery, settings save/consumer/refresh/restore for the remaining flow, monitoring request cadence and recovery UI, and maintenance behavior remain unverified and reserved for the coordinator's scheduled acceptance windows.

## Next UI window preparation — source and prior evidence review only

No browser, application service, database, or setting was touched during this preparation. The master acceptance matrix was not edited.

### Monitoring: exact remaining proof

The current UI source offers realtime 3s, 5s, 10s, 30s, 1m, 5m, 15m, 1h, and manual; `monitoring-refresh-interval.ts` maps those values directly, suppresses a tick while fetching or in manual mode, and applies exponential failure backoff capped at `max(base interval, 60s)`. `monitoring-page.tsx` starts/stops scheduled polling from `document.visibilityState`, listens to `visibilitychange`, sets `refetchIntervalInBackground: false`, disables automatic query retry, and exposes `QueryFailure` retry.

The prior UI exercised selection of all nine options, short visible update observations, manual refresh, and route leave/return. Existing service traces are aggregate records without a browser-tab identifier; some show summary requests while the tab was reportedly in the background, but the page snapshot reported `visibility=visible`. They cannot prove which page sent them or that a hidden document paused. No controlled failure was induced. A window/focus state where the same document is visible but unfocused was not established. These are the remaining concrete UI gaps; do not interpret old aggregate traces as hidden-tab pass/fail.

For the next attributable session, keep one owned monitoring page target and record request-start/status timestamps for only `/api/platform/monitoring/summary` alongside visible selected mode, `document.visibilityState`, `document.hasFocus()`, and route. Exercise manual (wait for no scheduled request, then click Refresh once); realtime/5s and a route leave/return; true hidden for at least three base cycles then visible recovery; and visible-but-unfocused behavior. If the browser tooling can intercept only this endpoint in this tab, return one controlled 503, verify the visible error/retry affordance and backoff, remove the interception, click Retry, and observe recovery. Do not stop/restart the shared service or alter global network state. Request attribution for the 30s and 1m intervals needs at least two cycles; 5m, 15m, and 1h need proportionately long observation to prove a complete interval. A quick no-premature-request observation may be recorded for those longer modes, but must remain partial and must not be described as an exact-cycle pass.

### Settings: low-risk reversible candidate

The recorded Branding baseline has `footerCopyright` blank, website and company names both `Lumira`, and branding history versions 5/6 from the prior website-name change/restore. The visible `BrandFooter` on the public login page renders `footerCopyright` directly when nonempty, so this field is a safer consumer check than changing SMTP/SMS, file-retention, security, or external URL settings. Candidate UI scenario only, pending browser slot: inspect the current Branding snapshot/version and History first; confirm blank copyright and that a candidate baseline revision's full payload matches the current starting configuration. Save only `footerCopyright = QA_FOOTER_UI_20260929_A` at `/dashboard/branding`; reload `/user/login` and verify the footer consumer; return to Branding and restore through the visible history action only if the selected revision exactly matches the captured original payload. Refresh both consumer and settings page and confirm blank copyright plus unchanged `websiteName=Lumira` and `companyName=Lumira`. The last recorded candidate is version 6, but it must be rechecked at action time. Expect the test and rollback revisions to remain in history.

Watermark already has a complete UI loop: enabled fixed text `Lumira`, `#808080`, normal, 14px, −22°, gaps 100/100, offsets 50/50, z-index 9, opacity 0.15; only rotation was changed to −45° and restored through its UI with reload checks. Website name was changed to a unique marker and restored to `Lumira`, with header/title consumer and reload checks. Neither should be repeated absent new evidence. Existing evidence: `docs/audits/evidence/ui-acceptance-2026-09-28/03-ops-settings-locale.md`.

### Dictionary, sensitive words, audit, modules, navigation

The V15 sensitive-category UI already verified master `sensitive_word_category` is enabled/list structure and master status is locked; active-reference disable was rejected; after removing the exact active test reference, a test category was disabled, persisted after reload, omitted from enabled choices, then deleted by exact confirmation. The F-01 test exercised at least 22 matches, 20 displayed, overall block/hidden-hit hint; the feedback form blocked a marker and remained empty after refresh. A native quoted CSV import was also completed. Its batch and three imported words are still retained by instruction; no data cleanup is assigned here.

The remaining dictionary/sensitive gap is a disabled legacy value that is still referenced and then edited unchanged. Existing evidence says no such usable row was found; the feedback record category is read-only for an existing record. Source permits a sensitive-word update to keep its current category without revalidating that category, while create/import and category changes use enabled choices. However, the normal UI cannot safely manufacture a referenced disabled category: active references prevent disabling; after soft-deleting the reference it is no longer an editable active row. Do not use hidden/API/DB writes to create this state. Also inspect visible dictionary master structure/status for the specific historical protected-TREE claim if that is still an acceptance item; the recorded `sensitive_word_category` was `列表`, which does not establish the state of every protected master.

Audit filtering, default 10 rows, page-size change, pagination, ellipsis tooltip, and a 942×794 CSS-pixel narrow view already passed. Navigation search/ancestor/reset and a narrow 942×794 CSS-pixel layout also passed. Modules Core/Manageable tabs, counts 12/3, current normal statuses, and current `audit-console` row without a stop control were inspected at wide width; the read-only history included an earlier disabled revision. The current evidence does not establish module-page narrow layout/long-dependency overflow or immutable historical protection; a later UI-only narrow inspection can capture bounds/tooltips and current controls, but cannot prove an immutable guard without an allowed non-core test module.

### Maintenance: final window only

Prior read-only UI evidence at `/dashboard/maintenance` records blank title/message/end time, zero allowed roles, and disabled status. `MaintenanceSettings.defaults()` agrees with these values. `MaintenanceService.canLogin()` allows inactive maintenance and any account with global `platform:settings:write`; otherwise an active setting requires a matching enabled role ID, and an empty allowlist denies ordinary logins. The login success handler invalidates a denied session and returns 503. These are source findings, not runtime proof. The setting is not active and was not changed.

At the final coordinator-assigned global window, capture the visible current version/history and exact fields first; check narrow layout before enabling. Use a known ordinary QA identity and its existing role from normal UI, not a newly fabricated role. If the test proceeds, use a unique title/message and a short future end time as an emergency expiry; perform ordinary-role denial/allow checks only through logout/login, verify a global-settings administrator can still sign in and reach the maintenance editor, then restore disabled status, blank title/message, null end time, and empty role list through the supported UI. Reload the maintenance page and verify normal administrator and ordinary-user login before release. If the baseline has no restorable history row/version, use only the exact captured defaults through the normal maintenance form and label that restoration method; do not claim history rollback. No enable action is authorized before the coordinator opens this final window and confirms ordinary-user/feedback work is complete.

Outstanding user/browser prerequisites in the current acceptance evidence include the ordinary QA credential handoff and a single-owner browser slot. Until those are confirmed, ordinary-user/maintenance login checks remain blocked; no browser or maintenance action was attempted here.
