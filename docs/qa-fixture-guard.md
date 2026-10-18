# Isolated browser QA fixture guard

The scripts listed below require an explicitly selected disposable QA target. Each calls the shared `scripts/qa-run-guard.mjs` before launching a browser, authenticating against the live application, or creating/changing fixtures. They do not fall back to `.runtime/local.json`.

Set `LUMIRA_SMOKE_CONFIG` to a private config and `BASE_URL` to the loopback web origin. The config must be a regular non-symlink file with owner-only permissions and contain `LUMIRA_QA_RUN_TOKEN` as a UUID. Its PostgreSQL database must be reachable on loopback and named `lumira_security_*`, `lumira_qa_*`, `lumira_test_*`, or `lumira_smoke_*`. `BASE_URL` must also resolve to a loopback origin.

The database must already contain the single QA marker row in `public.lumira_qa_fixture_guard`:

```sql
CREATE TABLE public.lumira_qa_fixture_guard (
    id boolean PRIMARY KEY CHECK (id),
    run_token uuid NOT NULL,
    purpose text NOT NULL CHECK (purpose = 'isolated-security-qa')
);

INSERT INTO public.lumira_qa_fixture_guard (id, run_token, purpose)
VALUES (true, '<same-random-UUID-as-LUMIRA_QA_RUN_TOKEN>', 'isolated-security-qa');
```

The marker belongs to the disposable QA workspace; it is not an application migration. The Node guard reads it and fails closed if it is absent, duplicated, or mismatched. It then calls the shared Python helper `smoke_support.ensure_qa_target_binding()` synchronously with a bounded timeout. That helper requests anonymous CSRF state from the configured loopback app origin (and the optional `LUMIRA_BACKEND_BASE_URL` when configured), reads the resulting anonymous session cookie, and verifies that its `spring_session` row belongs to the same marked database. The CSRF request may create anonymous session state in `spring_session`; it does not log in or create/change business fixtures. The Java web app/backend must already be running against the marked QA database before these live browser scripts start.

The Python functions in `scripts/smoke_support.py` also enforce the private-config, loopback, database-namespace, and marker checks before their SQL/HTTP operations. This applies to scripts that use the shared helper; it does not imply that every historical smoke script is guarded. Keep the config owner-only and do not copy the token into reports or source control.

These ten browser entry points in the current candidate use the shared guard:

- `scripts/admin-files-browser-smoke.mjs`
- `scripts/admin-notifications-browser-smoke.mjs`
- `scripts/file-retention-browser-smoke.mjs`
- `scripts/files-browser-smoke.mjs`
- `scripts/files-identity-boundary-browser-smoke.mjs`
- `scripts/localization-ai-browser-smoke.mjs`
- `scripts/provider-form-defaults-browser-smoke.mjs` (authenticated, read-only UI check)
- `scripts/session-security-browser-smoke.mjs`
- `scripts/shell-responsive-browser-smoke.mjs`
- `scripts/users-browser-smoke.mjs`

This list describes only the candidate scripts covered by the shared Node guard. Other historical smoke scripts are outside this guard and must not be assumed safe to run against a business database.

For example, from the repository root, select the disposable QA config explicitly:

```sh
LUMIRA_SMOKE_CONFIG=.runtime/qa/qa-config.json \
BASE_URL=http://127.0.0.1:8010 \
node scripts/admin-files-browser-smoke.mjs
```

The guard unit tests run without opening a browser or changing application data:

```sh
node --test tests/qa-run-guard.test.mjs
```
