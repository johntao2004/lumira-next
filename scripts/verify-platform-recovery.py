"""验证一次性平台库、原始密钥和 legacy 文件的停机恢复，不连接生产。
Verify offline recovery of a disposable platform database, original keys and legacy files, never production.
"""

import base64
import hashlib
import http.cookiejar
import json
import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.parse
import urllib.error
import uuid
import zipfile
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
os.chdir(ROOT)
ENV = os.environ.copy()
ENV.setdefault("PGCONNECT_TIMEOUT", "10")
ENV.setdefault("PGOPTIONS", "-c statement_timeout=90000 -c lock_timeout=15000")
if sys.flags.optimize:
    raise RuntimeError("Assertions must remain enabled")
assert ENV["PGHOST"] == "127.0.0.1"
if ENV.get("LUMIRA_RECOVERY_QA") != "1":
    raise RuntimeError("Set LUMIRA_RECOVERY_QA=1 only for a disposable QA cluster")
RUN = uuid.uuid4().hex
SOURCE = "lumira_qa_restore_" + RUN + "_source"
TARGET = "lumira_qa_restore_" + RUN + "_target"
RUNTIME = "qa_restore_run_" + RUN
MIGRATOR = "qa_restore_mig_" + RUN
JAR = ROOT / "backend/target/platform-server-0.1.0-SNAPSHOT.jar"
assert JAR.is_file()
KEY = "primary=" + base64.b64encode(secrets.token_bytes(32)).decode()
NEXT_KEY = "rotated=" + base64.b64encode(secrets.token_bytes(32)).decode()
PASSWORD = "Qa9!" + secrets.token_urlsafe(24)
BOOT = "Qa9!" + secrets.token_urlsafe(24)
PORT = int(ENV.get("LUMIRA_RECOVERY_QA_PORT", "18092"))
BASE = f"http://127.0.0.1:{PORT}/api"
PROCESS = None
REPORT = {
    "checks": [],
    "release": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "jarSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
    "verificationSha256": hashlib.sha256(
        pathlib.Path(__file__).read_bytes()
    ).hexdigest(),
    "worktreeDirty": bool(
        subprocess.check_output(["git", "status", "--porcelain"], text=True).strip()
    ),
}


def check(name):
    REPORT["checks"].append(name)
    print("PASS: " + name, flush=True)


def run(args, env=ENV, input=None):
    return subprocess.run(
        args,
        env=env,
        input=input,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
        timeout=120,
        text=True,
    ).stdout.strip()


def sql(db, text, env=ENV):
    return run(["psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-d", db, "-c", text], env)


def ident(s):
    return '"' + s.replace('"', '""') + '"'


def stop():
    global PROCESS
    if PROCESS:
        PROCESS.terminate()
        try:
            PROCESS.wait(timeout=30)
        except subprocess.TimeoutExpired:
            PROCESS.kill()
            PROCESS.wait(timeout=10)
        PROCESS = None


def client():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
    )


def request(c, path, method="GET", data=None, form=False):
    headers = {}
    if method != "GET":
        csrf = json.load(c.open(BASE + "/auth/csrf", timeout=15))
        headers[csrf["headerName"]] = csrf["token"]
    try:
        me = json.load(c.open(BASE + "/auth/me", timeout=15))
        revision = me.get("authorizationContext", {}).get("revision")
        if revision:
            headers["X-Lumira-Authorization-Context-Revision"] = revision
    except urllib.error.HTTPError as e:
        assert e.code == 401
    if isinstance(data, bytes):
        headers["Content-Type"] = "application/octet-stream"
    elif data is not None:
        headers["Content-Type"] = (
            "application/x-www-form-urlencoded" if form else "application/json"
        )
        data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
    try:
        with c.open(
            urllib.request.Request(
                BASE + path, data=data, headers=headers, method=method
            ),
            timeout=20,
        ) as response:
            body = response.read()
            return response.status, (
                json.loads(body)
                if "json" in response.headers.get("Content-Type", "")
                else body
            )
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def login(c, password):
    return request(
        c, "/auth/login", "POST", {"username": "admin", "password": password}, True
    )


def start(db, files, key=KEY, active="primary"):
    global PROCESS
    probe = socket.socket()
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    probe.bind(("127.0.0.1", PORT))
    probe.close()
    env = {
        **APP,
        "LUMIRA_DB_URL": f'jdbc:postgresql://127.0.0.1:{ENV["PGPORT"]}/{db}',
        "LUMIRA_FILES_LOCAL_ROOT": str(files),
        "LUMIRA_PLUGINS_PATH": str(
            WORK / ("plugins-source" if db == SOURCE else "plugins-target")
        ),
        "LUMIRA_PORT": str(PORT),
        "LUMIRA_BIND": "127.0.0.1",
        "LUMIRA_BOOTSTRAP_PASSWORD": BOOT,
        "LUMIRA_SECRET_KEYS": key,
        "LUMIRA_SECRET_ACTIVE_KEY": active,
        "LUMIRA_COOKIE_SECURE": "false",
        "LUMIRA_DB_REQUIRE_UNPRIVILEGED": "true",
    }
    log = open(WORK / (db + ".log"), "w")
    PROCESS = subprocess.Popen(
        [
            "java",
            "-jar",
            str(JAR),
            "--lumira.events.poll-initial-delay-ms="
            + ("600000" if db == SOURCE else "1000"),
        ],
        cwd=ROOT,
        env=env,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    for _ in range(120):
        if PROCESS.poll() is not None:
            raise RuntimeError("Application exited; private log retained")
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{PORT}/actuator/health/readiness", timeout=1
            ) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.5)
    raise RuntimeError("Application readiness timeout")


def normalize(value):
    if isinstance(value, list):
        return sorted(
            [normalize(x) for x in value], key=lambda x: json.dumps(x, sort_keys=True)
        )
    if isinstance(value, dict):
        value = dict(value)
        if value.get("conname") == "platform_print_template_version_source_check":
            # PG 恢复会展平这一已知纯 AND 分组，仅归一化精确子表达式。
            # PostgreSQL restore flattens this known AND group; normalize only this exact subexpression.
            value["definition"] = value["definition"].replace(
                "((length(TRIM(BOTH FROM source_filename)) >= 1) AND (length(TRIM(BOTH FROM source_filename)) <= 255))",
                "(length(TRIM(BOTH FROM source_filename)) >= 1) AND (length(TRIM(BOTH FROM source_filename)) <= 255)",
            )
        return {k: normalize(v) for k, v in value.items()}
    return value


def base_catalog(db):
    return sql(
        db,
        """SELECT json_build_object('class',(SELECT json_agg(x ORDER BY x.relname) FROM (SELECT c.relname,c.relkind,pg_get_userbyid(c.relowner) owner,c.relacl FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN('r','S','v','m')) x),'constraints',(SELECT json_agg(x ORDER BY x.conname,x.definition) FROM (SELECT conname,conrelid::regclass::text relation,convalidated,pg_get_constraintdef(c.oid) definition FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public') x),'defaults',(SELECT json_agg(x ORDER BY x.owner,x.defaclobjtype) FROM (SELECT pg_get_userbyid(d.defaclrole) owner,d.defaclobjtype,d.defaclacl FROM pg_default_acl d JOIN pg_namespace n ON n.oid=d.defaclnamespace WHERE n.nspname='public') x))""",
    )


def catalog(db):
    # 比较恢复后的权限和可执行数据库结构，而不只比较行数。
    # Compare recovered privileges and executable schema, not merely row counts.
    result = json.loads(base_catalog(db))
    queries = {
        "schema": "SELECT nspname,pg_get_userbyid(nspowner) owner,nspacl FROM pg_namespace WHERE nspname='public'",
        "indexes": "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'",
        "functions": "SELECT p.oid::regprocedure::text identity,pg_get_userbyid(p.proowner) owner,p.proacl,pg_get_functiondef(p.oid) definition FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prokind IN ('f','p')",
        "triggers": "SELECT tgname,tgrelid::regclass::text relation,tgenabled,pg_get_triggerdef(oid) definition FROM pg_trigger WHERE NOT tgisinternal AND tgrelid IN (SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public')",
        "sequences": "SELECT sequencename,sequenceowner,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size FROM pg_sequences WHERE schemaname='public'",
    }
    for name, query in queries.items():
        result[name] = json.loads(
            sql(db, "SELECT coalesce(json_agg(t),'[]'::json) FROM (" + query + ") t")
        )
    return json.dumps(result)


def contents(db):
    result = {}
    for table in sql(
        db,
        "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
    ).splitlines():
        text = sql(
            db,
            "COPY (SELECT row_to_json(t)::text FROM public."
            + ident(table)
            + ' t ORDER BY row_to_json(t)::text COLLATE "C") TO STDOUT',
        )
        result[table] = hashlib.sha256(text.encode()).hexdigest()
    for name in sql(
        db,
        "SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename",
    ).splitlines():
        result["sequence:" + name] = sql(
            db, "SELECT last_value,is_called FROM public." + ident(name)
        )
    return result


RESULT = ROOT / ".runtime/platform-restore-results.json"
RESULT.parent.mkdir(exist_ok=True)
REPORT["status"] = "running"
RESULT.write_text(json.dumps(REPORT, indent=2))
WORK = pathlib.Path(tempfile.mkdtemp(prefix="lumira-platform-restore-", dir="/tmp"))
os.chmod(WORK, 0o700)
APP = {
    **ENV,
    "LUMIRA_DB_USER": RUNTIME,
    "LUMIRA_DB_PASSWORD": secrets.token_urlsafe(32),
    "LUMIRA_DB_MIGRATION_USER": MIGRATOR,
    "LUMIRA_DB_MIGRATION_PASSWORD": secrets.token_urlsafe(32),
}
APP_SQL = {**ENV, "PGUSER": RUNTIME, "PGPASSWORD": APP["LUMIRA_DB_PASSWORD"]}
MIG_SQL = {**ENV, "PGUSER": MIGRATOR, "PGPASSWORD": APP["LUMIRA_DB_MIGRATION_PASSWORD"]}
try:
    sql("postgres", "CREATE DATABASE " + ident(SOURCE))
    run(
        ["bash", "deploy/database/10-application-role.sh"],
        {
            **APP,
            "POSTGRES_USER": ENV["PGUSER"],
            "POSTGRES_DB": SOURCE,
            "LUMIRA_DB_APP_USER": RUNTIME,
        },
    )
    sourceFiles = WORK / "source-files"
    targetFiles = WORK / "target-files"
    start(SOURCE, sourceFiles)
    admin = client()
    status, flow = login(admin, BOOT)
    assert status == 202
    assert (
        request(
            admin,
            "/auth/login/password-change",
            "POST",
            {
                "challengeId": flow["challengeId"],
                "newPassword": PASSWORD,
                "confirmPassword": PASSWORD,
            },
        )[0]
        == 200
    )
    data = ("restore synthetic file " + RUN).encode()
    status, f = request(
        admin, "/account/files?filename=restore-fixture.txt", "POST", data
    )
    assert status == 201, (status, f)
    fid = f["id"]
    assert request(admin, "/account/files/" + fid + "/content")[1] == data
    check("source authenticates and persists file through real HTTP")
    memberName = "qa_restore_" + RUN
    memberPassword = "Qa9!" + secrets.token_urlsafe(24)
    status, memberRecord = request(
        admin,
        "/platform/users",
        "POST",
        {
            "username": memberName,
            "displayName": "Restore member",
            "password": memberPassword,
        },
    )
    assert status == 201
    member = client()
    assert (
        request(
            member,
            "/auth/login",
            "POST",
            {"username": memberName, "password": memberPassword},
            True,
        )[0]
        == 200
    )
    memberData = b"postgres restore fixture"
    status, mf = request(
        member, "/account/files?filename=member-fixture.txt", "POST", memberData
    )
    assert status == 201
    memberFile = mf["id"]
    status, role = request(
        admin,
        "/platform/roles",
        "POST",
        {
            "code": "qa_restore",
            "name": "Recovery member",
            "enabled": True,
            "dataScope": "SELF",
            "authorities": [
                "platform:feedback:access",
                "platform:feedback:create",
                "platform:feedback:read",
            ],
        },
    )
    assert status == 201
    status, detail = request(admin, "/platform/users/" + memberRecord["id"])
    assert status == 200
    assert (
        request(
            admin,
            "/platform/users/" + memberRecord["id"] + "/roles",
            "PUT",
            {"version": detail["version"], "roleIds": [role["id"]]},
        )[0]
        == 200
    )
    status, options = request(member, "/feedback/options")
    assert status == 200
    status, feedback = request(
        member,
        "/feedback",
        "POST",
        {
            "title": "Restore fixture",
            "detailHtml": "<p>synthetic recovery record</p>",
            "priority": options["priorities"][0]["value"],
            "category": options["categories"][0]["value"],
        },
    )
    assert status == 201
    feedbackId = feedback["id"]
    eventId = sql(
        SOURCE,
        "SELECT id FROM platform_event_outbox WHERE payload->>'feedbackId'='"
        + feedbackId
        + "' AND status='PENDING'",
    )
    assert eventId and len(eventId) == 36
    # 从冻结发行包抽取实际类与依赖，使用真正 SecretStore 加密与解密。
    # Extract the frozen release classes/dependencies to exercise the actual SecretStore crypto.
    with zipfile.ZipFile(JAR) as archive:
        for name in archive.namelist():
            if (
                name.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/"))
                and ".." not in pathlib.PurePosixPath(name).parts
            ):
                archive.extract(name, WORK / "jar")
    cp = (
        str(WORK / "jar/BOOT-INF/classes")
        + os.pathsep
        + str(WORK / "jar/BOOT-INF/lib/*")
    )
    helper = WORK / "RestoreSecretProbe.java"
    helper.write_text(
        """import com.lumira.platform.common.SecretKeys; import com.lumira.platform.settings.SecretStore; import java.util.Arrays; import org.springframework.jdbc.core.simple.JdbcClient; import org.springframework.jdbc.datasource.DriverManagerDataSource; import org.springframework.jdbc.datasource.DataSourceTransactionManager; import org.springframework.transaction.support.TransactionTemplate;
class RestoreSecretProbe {public static void main(String[] args){var e=System.getenv();var ds=new DriverManagerDataSource(e.get("QA_URL"),e.get("LUMIRA_DB_USER"),e.get("LUMIRA_DB_PASSWORD"));var store=new SecretStore(JdbcClient.create(ds),new SecretKeys(e.get("LUMIRA_SECRET_KEYS"),e.getOrDefault("LUMIRA_SECRET_ACTIVE_KEY","primary")));byte[] expected="synthetic recovery evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);if(args[0].equals("seed"))new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s->store.save("qa:restore",0,expected));byte[] actual;try{actual=store.reveal("qa:restore").orElseThrow();}catch(IllegalStateException failure){if(args[0].equals("wrong-key") && "Secret cannot be decrypted".equals(failure.getMessage())){System.exit(42);return;}throw failure;}if(!Arrays.equals(actual,expected))throw new IllegalStateException();if(args[0].equals("rotate"))new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s->store.save("qa:restore",store.state("qa:restore").version(),actual));Arrays.fill(actual,(byte)0);Arrays.fill(expected,(byte)0);}}
"""
    )

    def secret(db, mode, key=KEY, active="primary"):
        run(
            ["java", "--class-path", cp, str(helper), mode],
            {
                **APP,
                "QA_URL": f'jdbc:postgresql://127.0.0.1:{ENV["PGPORT"]}/{db}',
                "LUMIRA_SECRET_KEYS": key,
                "LUMIRA_SECRET_ACTIVE_KEY": active,
            },
        )

    secret(SOURCE, "seed")
    stop()
    assert (
        sql(
            SOURCE,
            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename IN ('"
            + RUNTIME
            + "','"
            + MIGRATOR
            + "')",
        )
        == "0"
    )
    # 仅将本次合成文件转为 legacy fixture；源已停止，随后复制独立备份目录。
    # Convert only this synthetic file into a legacy fixture after stopping the source, then copy it.
    path = sourceFiles / fid[:2] / (fid + ".blob")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    sql(
        SOURCE,
        "BEGIN; UPDATE platform_file SET storage_backend='local' WHERE id='"
        + fid
        + "'; DELETE FROM platform_file_blob_chunk WHERE file_id='"
        + fid
        + "'; COMMIT;",
    )
    sql(
        SOURCE,
        "CREATE TABLE qa_restore_parent(id bigserial PRIMARY KEY,value text NOT NULL CHECK(length(value)>0)); CREATE TABLE qa_restore_child(parent_id bigint REFERENCES qa_restore_parent(id));",
        MIG_SQL,
    )
    assert (
        sql(
            SOURCE,
            "INSERT INTO qa_restore_parent(value) VALUES('source') RETURNING id",
            APP_SQL,
        ).splitlines()[0]
        == "1"
    )
    baseline = contents(SOURCE)
    structure = catalog(SOURCE)
    run(["pg_dump", "-Fc", "-f", str(WORK / "platform.dump"), SOURCE])
    sql("postgres", "CREATE DATABASE " + ident(TARGET))
    run(
        [
            "pg_restore",
            "--exit-on-error",
            "--single-transaction",
            "--dbname",
            TARGET,
            str(WORK / "platform.dump"),
        ]
    )
    sql(
        "postgres",
        "GRANT CONNECT,TEMPORARY ON DATABASE "
        + ident(TARGET)
        + " TO "
        + ident(RUNTIME)
        + ","
        + ident(MIGRATOR),
    )
    assert contents(TARGET) == baseline
    actualStructure = catalog(TARGET)
    (WORK / "source-structure.json").write_text(structure)
    (WORK / "target-structure.json").write_text(actualStructure)
    assert normalize(json.loads(actualStructure)) == normalize(
        json.loads(structure)
    ), "Catalog mismatch; private comparison retained"
    check(
        "offline dump restores all table contents, sequence states, owners, ACLs and constraint definitions"
    )
    secret(TARGET, "verify")
    try:
        secret(
            TARGET,
            "wrong-key",
            "primary=" + base64.b64encode(secrets.token_bytes(32)).decode(),
        )
    except subprocess.CalledProcessError as error:
        assert error.returncode == 42, "Wrong-key probe failed for an unrelated reason"
    else:
        raise AssertionError("Wrong key unexpectedly decrypted")
    check("actual SecretStore decrypts only with the original keyring")
    assert (
        sql(
            TARGET,
            "INSERT INTO qa_restore_parent(value) VALUES('restored') RETURNING id",
            APP_SQL,
        ).splitlines()[0]
        == "2"
    )

    def denied(statement, code):
        try:
            run(
                [
                    "psql",
                    "-XAt",
                    "-v",
                    "ON_ERROR_STOP=1",
                    "-v",
                    "VERBOSITY=sqlstate",
                    "-d",
                    TARGET,
                    "-c",
                    statement,
                ],
                APP_SQL,
            )
        except subprocess.CalledProcessError as e:
            assert code in e.stderr
        else:
            raise AssertionError("Expected SQLSTATE " + code)

    denied("INSERT INTO qa_restore_parent(id,value) VALUES(1,'duplicate')", "23505")
    denied("INSERT INTO qa_restore_parent(value) VALUES('')", "23514")
    denied("INSERT INTO qa_restore_child VALUES(999999)", "23503")
    denied("ALTER TABLE qa_restore_parent ADD COLUMN forbidden int", "42501")
    denied("TRUNCATE qa_restore_parent", "42501")
    denied("SET ROLE " + ident(MIGRATOR), "42501")
    assert (
        sql(
            TARGET,
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename LIKE '%schema_history' AND has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
            APP_SQL,
        )
        == "0"
    )
    sql(
        TARGET,
        "CREATE TABLE qa_restore_future(id bigserial PRIMARY KEY,value text)",
        MIG_SQL,
    )
    assert (
        sql(
            TARGET,
            "INSERT INTO qa_restore_future(value) VALUES('future plugin') RETURNING id",
            APP_SQL,
        ).splitlines()[0]
        == "1"
    )
    check(
        "restored sequences, unique/check/foreign-key constraints, DML defaults and runtime DDL rejection work"
    )
    shutil.copytree(sourceFiles, targetFiles)
    shutil.copytree(WORK / "plugins-source", WORK / "plugins-target")

    def packageHashes(root):
        return {
            str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob("*")
            if p.is_file()
        }

    sourcePackages = packageHashes(WORK / "plugins-source")
    assert sourcePackages, "No trusted plugin artifacts captured"
    assert sourcePackages == packageHashes(WORK / "plugins-target")
    check("plugin artifacts are restored into a separate matching directory")
    # 轮换先保留旧钥读旧密文，再以新活动钥重写；退休旧钥必须先证明所有保留值可读。
    # Read old envelopes with a dual ring, rewrap under the new active key, then retire the old key.
    secret(TARGET, "verify", KEY + "," + NEXT_KEY, "rotated")
    try:
        secret(TARGET, "wrong-key", NEXT_KEY, "rotated")
    except subprocess.CalledProcessError as error:
        assert error.returncode == 42
    else:
        raise AssertionError(
            "Retiring the old key prematurely must reject old envelopes"
        )
    secret(TARGET, "rotate", KEY + "," + NEXT_KEY, "rotated")
    assert (
        sql(
            TARGET,
            "SELECT split_part(ciphertext, ':', 2) FROM platform_secret WHERE secret_context='qa:restore'",
        )
        == "rotated"
    )
    secret(TARGET, "verify", NEXT_KEY, "rotated")
    for attempt in range(2):
        start(
            TARGET,
            targetFiles,
            KEY + "," + NEXT_KEY if attempt == 0 else NEXT_KEY,
            "rotated",
        )
        secret(TARGET, "verify", NEXT_KEY, "rotated")
        admin = client()
        assert login(admin, PASSWORD)[0] == 200
        assert request(admin, "/platform/users")[0] == 200
        pluginStatus, plugins = request(admin, "/platform/modules/plugins")
        assert pluginStatus == 200
        assert {p["id"] for p in plugins if p.get("installed")} >= {
            "feedback",
            "localization",
            "sensitive-words",
        }
        assert request(client(), "/platform/users")[0] == 401
        member = client()
        assert (
            request(
                member,
                "/auth/login",
                "POST",
                {"username": memberName, "password": memberPassword},
                True,
            )[0]
            == 200
        )
        assert request(member, "/platform/users")[0] == 403
        assert (
            request(member, "/account/files/" + memberFile + "/content")[1]
            == memberData
        )
        assert request(member, "/account/files/" + fid + "/content")[0] == 404
        assert request(admin, "/feedback/manage/" + feedbackId)[0] == 200
        assert request(member, "/feedback/" + feedbackId)[0] == 200
        for _ in range(60):
            if (
                sql(
                    TARGET,
                    "SELECT status FROM platform_event_outbox WHERE id='"
                    + eventId
                    + "'",
                )
                == "DELIVERED"
            ):
                break
            time.sleep(0.5)
        assert (
            sql(
                TARGET,
                "SELECT status FROM platform_event_outbox WHERE id='" + eventId + "'",
            )
            == "DELIVERED"
        )
        assert (
            sql(
                TARGET,
                "SELECT count(*) FROM platform_notification WHERE event_id='"
                + eventId
                + "'",
            )
            == "1"
        )
        status, body = request(admin, "/account/files/" + fid + "/content")
        assert status == 200 and body == data
        assert (
            sql(
                TARGET,
                "SELECT storage_backend FROM platform_file WHERE id='" + fid + "'",
                APP_SQL,
            )
            == "postgres"
        )
        if attempt == 0:
            status, newfile = request(
                admin,
                "/account/files?filename=after-restore.txt",
                "POST",
                b"after recovery",
            )
            assert status == 201
            assert (
                request(admin, "/account/files/" + newfile["id"] + "/content")[1]
                == b"after recovery"
            )
        stop()
    check(
        "restored application and second restart preserve admin/member permissions, PostgreSQL and legacy files, feedback and exactly one recovered event inbox effect; new writes work"
    )
    check(
        "dual key rotation rewraps stored secret; premature retirement fails and new-key-only restarts remain readable"
    )
    REPORT["tableAndSequenceCount"] = len(baseline)
except BaseException as error:
    REPORT["status"] = "failed"
    REPORT["errorType"] = type(error).__name__
    raise
finally:
    # 每个清理阶段独立尝试，清理失败也不能留下成功报告。
    # Attempt every cleanup stage independently; cleanup failures must not leave a passed report.
    errors = []
    for cleanup in [
        stop,
        lambda: sql(
            "postgres", "DROP DATABASE IF EXISTS " + ident(TARGET) + " WITH (FORCE)"
        ),
        lambda: sql(
            "postgres", "DROP DATABASE IF EXISTS " + ident(SOURCE) + " WITH (FORCE)"
        ),
        lambda: sql(
            "postgres", "DROP ROLE IF EXISTS " + ident(RUNTIME) + "," + ident(MIGRATOR)
        ),
    ]:
        try:
            cleanup()
        except Exception as error:
            errors.append(type(error).__name__)
    if errors:
        REPORT["status"] = "failed"
        REPORT["cleanupErrors"] = errors
    elif REPORT["status"] == "running":
        REPORT["status"] = "passed"
    RESULT.write_text(json.dumps(REPORT, indent=2))
    if errors:
        raise RuntimeError("QA cleanup failed: " + ",".join(errors))
print("PASS: platform restore checks complete; isolated sample only", flush=True)
