"""仅使用新建 QA 数据库验证部署角色及原始初始化 SQL，不接触现有业务库。
Validate deployment roles and original initialization SQL in a new QA database only.
"""
import os
from pathlib import Path
import secrets
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
SUFFIX = uuid.uuid4().hex
DATABASE = "lumira_deploy_qa_" + SUFFIX
ROLE = "lumira_app_qa_" + SUFFIX
MIGRATION_ROLE = "lumira_mig_qa_" + SUFFIX
ADMIN = os.environ.copy()
APP = {**ADMIN, "PGUSER": ROLE, "PGPASSWORD": secrets.token_urlsafe(32)}
MIGRATION = {**ADMIN, "PGUSER": MIGRATION_ROLE, "PGPASSWORD": secrets.token_urlsafe(32)}


def psql(sql, env=ADMIN, database="postgres", file=None):
    command = ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-d", database]
    command += ["-f", "-"] if file else ["-c", sql]
    return subprocess.run(command, env=env, check=True, text=True, capture_output=True, input=file.read_text() if file else None).stdout.strip()


psql('CREATE DATABASE "' + DATABASE + '"')
try:
    env = {**ADMIN, "POSTGRES_USER": ADMIN["PGUSER"], "POSTGRES_DB": DATABASE,
           "LUMIRA_DB_APP_USER": ROLE, "LUMIRA_DB_PASSWORD": APP["PGPASSWORD"],
           "LUMIRA_DB_MIGRATION_USER": MIGRATION_ROLE, "LUMIRA_DB_MIGRATION_PASSWORD": MIGRATION["PGPASSWORD"]}
    subprocess.run(["bash", str(ROOT / "deploy/database/10-application-role.sh")],
                   env=env, check=True, capture_output=True, text=True)
    flags = psql("SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user", APP, DATABASE)
    assert flags == "f|f|f|f|f", flags
    psql(None, MIGRATION, DATABASE, ROOT / "backend/src/main/resources/db/migration/B52__platform_initialization.sql")
    migrations = sorted((ROOT / "plugins").glob("*/migrations/*__plugin_initialization.sql"))
    # 支付提供方依赖支付核心；保持声明的数据库依赖顺序。
    # Payment providers depend on payment-core; preserve database dependency ordering.
    expected = {"payment-core", "feedback", "wechat-pay", "sensitive-words", "localization", "alipay-pay", "mfa"}
    assert len(migrations) == 7 and {path.parent.parent.name for path in migrations} == expected, "Initialization coverage changed; review the expected set"
    migrations.sort(key=lambda path: (path.parent.parent.name != "payment-core", str(path)))
    for migration in migrations:
        psql(None, MIGRATION, DATABASE, migration)
    for sql in ("CREATE ROLE forbidden_qa", "CREATE DATABASE forbidden_qa",
                'ALTER ROLE "' + ROLE + '" SUPERUSER', "SELECT * FROM pg_authid", "CREATE TABLE public.forbidden_qa(id int)",
                "SET ROLE " + MIGRATION_ROLE, "TRUNCATE TABLE public.platform_user"):
        try:
            psql(sql, APP, DATABASE)
        except subprocess.CalledProcessError as failure:
            assert "permission denied" in failure.stderr.lower() or "must be superuser" in failure.stderr.lower(), failure.stderr
        else:
            raise AssertionError("Restricted operation unexpectedly succeeded")
    psql("CREATE TABLE public.qa_grants(id bigserial PRIMARY KEY, value text)", MIGRATION, DATABASE)
    psql("INSERT INTO public.qa_grants(value) VALUES ('before'); UPDATE public.qa_grants SET value='after'", APP, DATABASE)
    assert psql("SELECT value FROM public.qa_grants", APP, DATABASE) == "after"
    psql("DELETE FROM public.qa_grants", APP, DATABASE)
    psql("ALTER TABLE public.qa_grants ADD COLUMN extra text; DROP TABLE public.qa_grants", MIGRATION, DATABASE)
    psql("CREATE TEMP TABLE qa_temp(id int); INSERT INTO qa_temp VALUES (1)", APP, DATABASE)
    print(f"PASS: new-volume initializer, five restricted role flags, B52, {len(migrations)} plugin initializations, seven restricted operations, runtime CRUD/default sequence grants, migrator DDL, temporary tables")
finally:
    psql('DROP DATABASE "' + DATABASE + '" WITH (FORCE)')
    psql('DROP ROLE IF EXISTS "' + ROLE + '"')
    psql('DROP ROLE IF EXISTS "' + MIGRATION_ROLE + '"')
