"""Preserved channel configuration and real queue delivery in explicitly bound disposable QA."""
import json
import queue
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from smoke_support import client, call, write, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
received = queue.Queue()
secret = "qa-channel-" + uuid.uuid4().hex


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        assert 0 < length <= 32768
        payload = json.loads(self.rfile.read(length))
        received.put((self.headers.get("Authorization"), payload))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *_):
        pass


server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
prefix = "/platform/settings/"


def api(path, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, body = call(actor, path) if method == "GET" else write(actor, path, method, payload or {})
    assert status == expected, (path, status, body.decode())
    assert secret.encode() not in body, "Channel credential leaked into an API response"
    return json.loads(body) if body else None


def snapshot(queue_ids):
    sections = "section IN ('smtp','sms')"
    predicates = {
        "platform_setting": sections,
        "platform_setting_revision": "setting_id IN (SELECT id FROM platform_setting WHERE " + sections + ")",
        "platform_secret": "secret_context IN (SELECT 'settings:'||id||CASE section WHEN 'smtp' THEN ':smtp-password' ELSE ':sms-provider-secret' END FROM platform_setting WHERE " + sections + ")",
        "platform_navigation_menu": "id IN ('00000000-0000-0000-0000-000000000028','00000000-0000-0000-0000-000000000047')",
        "platform_mail_delivery": "id='" + queue_ids["mail"] + "'",
        "platform_sms_delivery": "id='" + queue_ids["sms"] + "'",
    }
    return {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t WHERE " + predicate)
            for table, predicate in predicates.items()}


try:
    preserve_plugin(admin, "delivery-settings", "install")
    suffix = uuid.uuid4().hex[:12]
    username = "qa_channel_" + suffix
    password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA channel actor", "password": password}, 201)
    current = {}
    saved_data = {}
    for section in ["smtp", "sms"]:
        path = prefix + section
        original = api(path)
        grants = ["platform:" + section + ":access", "platform:" + section + ":update", "platform:" + section + ":test"]
        scope = api("/platform/roles", "POST", {"code": "qa_channel_scope_" + section + suffix, "name": "QA channel scoped", "enabled": True, "dataScope": "SELF", "authorities": grants}, 201)
        assigned = api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": user["version"], "roleIds": [scope["id"]]})
        actor = client()
        assert login(actor, username, password) == 200
        for route in [path, path + "/history"]:
            api(route, expected=403, actor=actor)
        reader = api("/platform/roles", "POST", {"code": "qa_channel_read_" + section + suffix, "name": "QA channel reader", "enabled": True, "dataScope": "ALL", "authorities": grants[:1]}, 201)
        assigned = api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": assigned["version"], "roleIds": [reader["id"]]})
        assert login(actor, username, password) == 200
        assert api(path, actor=actor) == original
        api(path + "/history", actor=actor)
        api(path, "PUT", {"version": original["version"], "data": original["data"], "credentials": {"action": "KEEP"}}, 403, actor=actor)
        api(path + "/test", "POST", expected=403, actor=actor)
        api(path + "/rollback", "POST", {"version": 1, "sourceVersion": 1, "credentials": {"action": "KEEP"}}, 403, actor=actor)
        user = {**user, "version": assigned["version"]}
        data = dict(original["data"])
        if section == "smtp":
            data.update(enabled=True, host="smtp.example.test", username="qa-channel", fromAddress="noreply@example.test", fromName="QA retained " + suffix, authentication=True)
        else:
            data.update(enabled=True, provider="webhook", endpoint="http://127.0.0.1:" + str(server.server_port) + "/send", sender="QA retained " + suffix)
        saved = api(path, "PUT", {"version": original["version"], "data": data, "credentials": {"action": "REPLACE", "value": secret}, "changeReason": "QA channel configuration"})
        api(path, "PUT", {"version": original["version"], "data": data, "credentials": {"action": "KEEP"}}, 409)
        changed = {**data, ("fromName" if section == "smtp" else "sender"): "QA second " + suffix}
        second = api(path, "PUT", {"version": saved["version"], "data": changed, "credentials": {"action": "KEEP"}})
        api(path + "/rollback", "POST", {"version": second["version"], "sourceVersion": saved["version"]}, 400)
        assert api(path)["version"] == second["version"]
        restored = api(path + "/rollback", "POST", {"version": second["version"], "sourceVersion": saved["version"], "credentials": {"action": "KEEP"}, "changeReason": "QA channel rollback"})
        assert restored["data"] == data
        assert api(path + "/history/" + str(saved["version"]))["data"] == data
        assert api(path + "/history")["total"] >= 3
        api(path + "/rollback", "POST", {"version": second["version"], "sourceVersion": saved["version"], "credentials": {"action": "KEEP"}}, 409)
        current[section] = api(path)
        saved_data[section] = data
    assert api(prefix + "sms/test", "POST")["result"] == "ACCEPTED"
    assert received.get(timeout=10)[0] == "Bearer " + secret
    api(prefix + "smtp/test", "POST", expected=409)
    # A late failure inside the real child-container transaction must roll back JSON, history and credentials together.
    trigger = "qa_channel_reject_" + suffix
    sql("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.actor='admin' AND NEW.action IN ('setting.smtpPasswordUpdated','setting.smsSecretUpdated') THEN RAISE EXCEPTION 'QA channel audit rejection'; END IF; RETURN NEW; END $$; CREATE TRIGGER " + trigger + " BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION " + trigger + "()")
    try:
        for section in ["smtp", "sms"]:
            before_setting = sql("SELECT md5(to_jsonb(t)::text) FROM platform_setting t WHERE section='" + section + "'")
            before_secrets = sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY secret_context),'[]'::jsonb)::text) FROM platform_secret t")
            before_revisions = api(prefix + section + "/history")["total"]
            api(prefix + section, "PUT", {"version": current[section]["version"], "data": saved_data[section], "credentials": {"action": "REPLACE", "value": "replacement-" + secret}}, 500)
            assert api(prefix + section) == current[section]
            assert api(prefix + section + "/history")["total"] == before_revisions
            assert sql("SELECT md5(to_jsonb(t)::text) FROM platform_setting t WHERE section='" + section + "'") == before_setting
            assert sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY secret_context),'[]'::jsonb)::text) FROM platform_secret t") == before_secrets
    finally:
        sql("DROP TRIGGER " + trigger + " ON platform_audit; DROP FUNCTION " + trigger + "()")
    # Two independent authenticated sessions submit one version. Exactly one update may commit.
    contenders = [client(), client()]
    for contender in contenders:
        assert login(contender, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    for section in ["smtp", "sms"]:
        path = prefix + section
        payload = {"version": current[section]["version"], "data": saved_data[section], "credentials": {"action": "KEEP"}}
        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(lambda contender: write(contender, path, "PUT", payload)[0], contenders))
        assert sorted(statuses) == [200, 409], (section, statuses)
        current[section] = api(path)
        assert current[section]["version"] == payload["version"] + 1
    available = api(prefix + "authentication")["availableMethods"]
    assert "EMAIL_OTP" in available and "SMS_OTP" in available
    queue_ids = {kind: str(uuid.uuid4()) for kind in ["mail", "sms"]}
    for kind, identifier in queue_ids.items():
        sql("INSERT INTO platform_" + kind + "_delivery(id,payload,state,attempts,expires_at,last_result) VALUES('" + identifier + "','v1:fixture:abcdef','ACCEPTED',1,now()+interval '1 hour','ACCEPTED')")
    before = snapshot(queue_ids)
    preserve_plugin(admin, "delivery-settings", "uninstall")
    for section in ["smtp", "sms"]:
        for route in [prefix + section, prefix + section + "/history", prefix + section + "/history/1"]:
            assert call(admin, route)[0] in {403, 404, 503}
        for route, method in [(prefix + section, "PUT"), (prefix + section + "/test", "POST"), (prefix + section + "/rollback", "POST")]:
            assert write(admin, route, method, {})[0] in {403, 404, 503}
    assert snapshot(queue_ids) == before
    assert api(prefix + "authentication")["availableMethods"] == available
    # Exercise the real identity -> encrypted queue -> core configuration -> HTTP sender chain while editors are absent.
    assert login(client(), username, password) == 200
    actor = client()
    assert login(actor, username, password) == 200
    phone = "+1555" + str(int(suffix, 16))[-7:].zfill(7)
    account = api("/account/phone", actor=actor)
    api("/account/phone/request", "POST", {"version": account["version"], "phone": phone, "currentPassword": password, "locale": "en"}, 202, actor=actor)
    authorization, message = received.get(timeout=35)
    assert authorization == "Bearer " + secret and message["to"] == phone
    assert message["message"] and message["sender"] == saved_data["sms"]["sender"]
    assert snapshot(queue_ids) == before
    preserve_plugin(admin, "delivery-settings", "install")
    assert snapshot(queue_ids) == before
    for section in ["smtp", "sms"]:
        assert api(prefix + section) == current[section]
    assert api(prefix + "sms/test", "POST")["result"] == "ACCEPTED"
    assert received.get(timeout=10)[0] == "Bearer " + secret
    # Leave stored values disabled for unrelated suites and browser fixtures; no pending send can target a closed receiver.
    for section in ["smtp", "sms"]:
        view = api(prefix + section)
        api(prefix + section, "PUT", {"version": view["version"], "data": {**view["data"], "enabled": False}, "credentials": {"action": "KEEP"}, "changeReason": "Finish isolated receiver fixture"})
    Path(".runtime/architecture-fix/delivery-settings-fixture.json").write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "data": saved_data}))
    print("PASS: optional SMTP/SMS actual configuration, explicit credential rollback, late-audit atomic rollback of settings/history/secrets, parallel version CAS and independent global page/action rights; settings/revisions/encrypted secrets/navigation/queue rows exactly retained through uninstall/reinstall; runtime provider readiness and real identity SMS queue delivery continue while management is absent")
finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
    write(admin, "/auth/logout", "POST", {})
