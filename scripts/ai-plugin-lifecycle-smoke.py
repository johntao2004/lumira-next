"""Real optional AI CRUD, model HTTP, dependency ordering and preserve-only lifecycle in guarded QA."""
import json
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from smoke_support import client, call, login, write, sql, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200


def api(path, method="GET", data=None, expected=200, connection=None):
    connection = connection or admin
    status, body = call(connection, path) if method == "GET" else write(connection, path, method, data or {})
    assert status == expected, (method, path, status, body.decode())
    return json.loads(body) if body else None


class ModelFixture(BaseHTTPRequestHandler):
    calls = 0

    def log_message(self, *args):
        pass

    def respond(self, payload):
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        assert self.path == "/v1/models", self.path
        self.respond({"data": [{"id": "qa-retained-model", "object": "model"}]})

    def do_POST(self):
        assert self.path == "/v1/chat/completions", self.path
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        assert data["model"] == "qa-retained-model" and data["messages"]
        ModelFixture.calls += 1
        self.respond({"choices": [{"message": {"role": "assistant", "content": "QA retained model response"}, "finish_reason": "stop"}]})


def snapshot():
    tables = ["platform_ai_provider", "platform_ai_conversation_share", "platform_ai_pending_action", "platform_secret", "platform_setting", "platform_setting_revision"]
    return {table: sql(f"SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM {table} t") for table in tables}


model = ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
thread = threading.Thread(target=model.serve_forever, daemon=True)
thread.start()
try:
    suffix = uuid.uuid4().hex[:12]
    for identifier in ("ai", "localization"):
        preserve_plugin(admin, identifier, "install")
    provider_input = {"name": "QA retained AI model " + suffix, "providerKind": "LM_STUDIO",
                      "baseUrl": f"http://127.0.0.1:{model.server_port}/v1", "modelName": "qa-retained-model",
                      "modelNames": ["qa-retained-model"], "enabled": True}
    provider = api("/platform/ai/providers", "POST", provider_input, 201)
    models = api(f'/platform/ai/providers/{provider["id"]}/models')
    assert any(item["id"] == "qa-retained-model" for item in models["models"])
    provider_input.update(version=provider["version"], name=provider["name"] + " updated")
    provider = api(f'/platform/ai/providers/{provider["id"]}', "PUT", provider_input)
    cloud = api("/platform/ai/providers", "POST", {"name": "QA retained encrypted model " + suffix,
        "providerKind": "OPENAI", "baseUrl": "https://api.openai.com/v1", "modelName": "qa-placeholder",
        "enabled": False, "credential": {"action": "REPLACE", "value": "qa-synthetic-key-" + uuid.uuid4().hex}}, 201)
    assert cloud["credentialConfigured"]
    chat_input = {"providerId": provider["id"], "modelName": "qa-retained-model", "messages": [{"role": "user", "content": "QA synthetic conversation"}]}
    assert api("/platform/ai/chat", "POST", chat_input)["message"] == "QA retained model response"
    assert any(item["id"] == provider["id"] for item in api("/platform/localization/ai/providers"))
    share_input = {"title": "QA retained shared conversation " + suffix,
                   "messages": [{"role": "user", "content": "QA synthetic question"}, {"role": "assistant", "content": "QA synthetic **answer**"}]}
    share = api("/platform/ai/shares", "POST", share_input, 201)
    public = client()
    share_path = "/public/ai/shares/" + share["token"]
    assert api(share_path, connection=public)["title"] == share_input["title"]
    assert any(item["id"] == share["id"] for item in api("/platform/ai/shares")["items"])
    pending = str(uuid.uuid4())
    sql(f"INSERT INTO platform_ai_pending_action(id,actor,kind,username,display_name,enabled,expires_at) VALUES('{pending}','admin','CREATE_USER','qa_pending_{suffix}','QA pending',true,now()+interval '1 hour')")
    directory_plan = api("/platform/modules/plugins/directory/uninstall-plan", "POST", {})
    assert any("pluginRequiredBy:" in code and "ai" in code for code in directory_plan["blockers"]), directory_plan
    ai_plan = api("/platform/modules/plugins/ai/uninstall-plan", "POST", {})
    assert any("pluginRequiredBy:" in code and "localization" in code for code in ai_plan["blockers"]), ai_plan
    # Explicit reverse order; the runtime must not cascade through consumers.
    preserve_plugin(admin, "localization", "uninstall")
    before = snapshot()
    preserve_plugin(admin, "ai", "uninstall")
    assert snapshot() == before, "AI unload changed retained rows or encrypted credential"
    for path in ("/platform/ai/providers", "/platform/ai/chat", share_path):
        status, body = call(public if path == share_path else admin, path)
        assert status != 200, path
        assert share_input["title"].encode() not in body
    assert all(item["id"] != "ai" for item in api("/platform/modules/plugins/frontend"))
    assert api("/auth/me")["username"] == "admin"
    assert api("/platform/users")["total"] >= 1
    assert api("/platform/monitoring/summary")["database"]["status"]
    preserve_plugin(admin, "ai", "install")
    assert snapshot() == before, "AI reinstall changed persisted identities, versions or credential"
    restored = api("/platform/ai/providers")
    assert next(item for item in restored if item["id"] == provider["id"]) == provider
    assert next(item for item in restored if item["id"] == cloud["id"])["credentialConfigured"]
    assert api(share_path, connection=public)["messages"] == share_input["messages"]
    assert api("/platform/ai/chat", "POST", chat_input)["message"] == "QA retained model response"
    preserve_plugin(admin, "localization", "install")
    assert any(item["id"] == provider["id"] for item in api("/platform/localization/ai/providers"))
    assert ModelFixture.calls == 2
    api(f"/platform/ai/actions/{pending}", "DELETE", {}, 204)
    output = Path(".runtime/architecture-fix/ai-fixture.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "providerName": provider["name"], "sharePath": share["path"], "shareTitle": share_input["title"]}))
    output.chmod(0o600)
    print("PASS: AI model CRUD and real local model HTTP; translation leased dependency; consumer/provider uninstall blockers; explicit reverse-order unload; all provider/share/pending-action/encrypted-secret snapshots preserved; public content unavailable during unload; IDs, versions, credentials, model chat and translation restore after reinstall")
finally:
    model.shutdown()
    model.server_close()
    thread.join(timeout=5)
    write(admin, "/auth/logout", "POST", {})
