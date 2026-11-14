"""为破坏性初始化测试创建专用空库，并在所有结果后清理。
Create dedicated empty databases for destructive initialization tests and clean up after every outcome.
"""

import os
from pathlib import Path
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
env = os.environ.copy()
if env.get("LUMIRA_RECOVERY_QA") != "1" or env.get("PGHOST") != "127.0.0.1":
    raise RuntimeError("Explicit disposable loopback QA cluster required")
suffix = uuid.uuid4().hex
env["LUMIRA_QA_RUN_TOKEN"] = str(uuid.uuid4())
names = {
    "LUMIRA_MIGRATION_TEST_DB": "lumira_api_docs_qa_",
    "LUMIRA_FEEDBACK_PERMISSION_MIGRATION_TEST_DB": "lumira_feedback_permission_qa_",
    "LUMIRA_PLUGIN_PERMISSION_FINALIZER_TEST_DB": "lumira_plugin_permissions_qa_",
    "LUMIRA_PLUGIN_INITIALIZATION_TEST_DB": "lumira_plugin_init_partial_qa_",
    "LUMIRA_DATABASE_BASELINE_TEST_DB": "lumira_qa_database_baseline_",
}
databases = [prefix + suffix for prefix in names.values()]


def execute(args, **options):
    return subprocess.run(args, env=env, check=True, timeout=600, **options)


try:
    for key, prefix in names.items():
        database = prefix + suffix
        execute(["createdb", database], capture_output=True)
        env[key + "_URL"] = f'jdbc:postgresql://127.0.0.1:{env["PGPORT"]}/{database}'
        env[key + "_USER"] = env["PGUSER"]
        env[key + "_PASSWORD"] = env["PGPASSWORD"]
    execute(
        [
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-d",
            databases[-1],
            "-c",
            "CREATE SCHEMA qa_guard; CREATE TABLE qa_guard.lumira_qa_fixture_guard(id boolean PRIMARY KEY CHECK(id),run_token uuid NOT NULL,purpose text NOT NULL); INSERT INTO qa_guard.lumira_qa_fixture_guard VALUES(true,'"
            + env["LUMIRA_QA_RUN_TOKEN"]
            + "','isolated-security-qa');",
        ],
        capture_output=True,
    )
    maven = env.get("LUMIRA_QA_MAVEN", str(root / "backend/mvnw"))
    execute(
        [
            maven,
            "-f",
            str(root / "backend/pom.xml"),
            "-B",
            "-Dtest=ApiDocsNavigationMigrationTest,FeedbackPermissionMigrationDatabaseTest,MfaPluginPermissionFinalizerDatabaseTest,PluginInitializationDatabaseTest,DatabaseBaselineMigrationTest",
            "test",
        ]
    )
finally:
    # 任一清理失败不阻止其他专用库清理，汇总错误后失败退出。
    # A cleanup failure must not prevent other dedicated databases from being removed.
    failures = []
    for database in databases:
        try:
            execute(["dropdb", "--if-exists", "--force", database], capture_output=True)
        except Exception as error:
            failures.append(type(error).__name__)
    if failures:
        raise RuntimeError("QA database cleanup failed: " + ",".join(failures))
print("PASS: six isolated initialization/migration tests and QA cleanup")
