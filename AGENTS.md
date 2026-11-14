# Lumira Next development

Read README.md and the applicable directory instructions before editing. Frontend
instructions are in apps/web/AGENTS.md. Use Node 24, pnpm 10.33.0, JDK 25 and
PostgreSQL 18. The global database uses only
`backend/src/main/resources/db/migration/B52__platform_initialization.sql`.
Do not restore historical global migrations, upgrade directories, or SQL test
archives. Derive current-schema test fixtures from the initialization SQL.
Plugin initialization remains independently managed. Each bundled plugin with
database setup keeps one final initialization SQL. Preserve published migration
identities/checksums in initialization.json and existing database history; never
rewrite applied history or use repair to conceal incompatibility.

## Cloud development

Only in a disposable Ubuntu or Debian development VM:

- Install: `LUMIRA_CLOUD_DEV=1 bash scripts/cloud-install.sh`
- Start the current checkout: `bash scripts/cloud-start.sh`
- Check frontend and database-backed backend tests: `bash scripts/cloud-check.sh`
- Before manual commands: `source scripts/cloud-env.sh`
- Health: `node scripts/status.mjs`; require HTTP 200 from both services.
- Stop app services and the user-space database: `bash scripts/cloud-stop.sh`.
- Before publishing, stop services and PostgreSQL to capture a consistent snapshot.

Do not copy local credentials, databases or uploaded files to the cloud. Generate
fresh development configuration in the VM. Never commit .runtime or secrets.
Browser interaction and persistence checks are separate from build/HTTP checks;
do not claim UI acceptance without actually performing it.

## Local files and generated output

Keep durable architecture, contracts and operational instructions in docs. Put
temporary reports, screenshots and test logs in .runtime, not docs/audits or the
project root. Do not add dated work journals to the source tree.

Preserve .runtime/local.json, database backups, uploaded files and active service
artifacts. The following existing test files and the 20-row sensitive-word data
baseline were explicitly retained by the user; do not purge them during cleanup:
preview-source.doc, preview-source.docx, preview-test.txt, preview-test.md,
preview-long.txt, lumira-quota-chunk-boundary-20260929.txt,
lumira-quota-ui-20260928-181851.txt. Do not mutate local business data for a cloud
setup check.

## Architecture and comment gates

Read docs/framework-development.md, docs/evolution-plan.md and docs/production-readiness.md before restructuring. Keep public contracts in framework and host implementations in their owning modules; plugin compilation must never use backend/target/classes. Preserve public FQNs and applied SQL checksums. Test standalone builds outside this repository.

Every core file in docs/core-file-inventory.json requires concise, matching Chinese and English responsibility comments. Every existing and new core comment block must use both languages; explain important authorization, transaction, concurrency and lifecycle constraints without line-by-line narration. Update the inventory when responsibilities move. Run pnpm check:core-comments and review accuracy manually.

Required gates include SDK/host/plugin builds, source boundaries, Java format, types, frontend tests/build, database-backed permission/transaction tests, the independent upgrade/restore example and the production-readiness matrix. Never label blocked Office or browser acceptance as passed. Remove only demonstrably superseded code/docs, update references and preserve compatibility and recovery assets.

Read docs/reference/backend-standard.md and docs/reference/frontend-standard.md for the adopted Java/React rules and explicit version adaptations. Public interface methods need concise bilingual Javadoc for meaningful parameters, results and failure/caller constraints; do not invent authors, dates or guarantees.
