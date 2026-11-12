"""Actual directory CRUD, scoped selection, template generation and preserve lifecycle acceptance."""
import io
import json
import uuid
import zipfile
from pathlib import Path
from plugin_lifecycle_support import preserve_plugin
from smoke_support import client, call, login, write, sql, config, token

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200


def api(path, method="GET", data=None, expected=200, connection=None):
    actor = connection or admin
    status, body = call(actor, path) if method == "GET" else write(actor, path, method, data or {})
    assert status == expected, (method, path, status, body.decode())
    return json.loads(body) if body else None


def retain_digest(table):
    allowed = {"platform_user", "platform_role", "platform_role_authority", "platform_user_role",
               "platform_organization", "platform_user_organization", "platform_role_authentication_policy",
               "platform_print_template", "platform_print_template_version", "platform_user_import_preview", "platform_user_import_task"}
    assert table in allowed
    return sql(f"SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM {table} t")


def create_template(name):
    document = io.BytesIO()
    with zipfile.ZipFile(document, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        archive.writestr("_rels/.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        archive.writestr("word/document.xml", '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Directory retained {{displayName}}</w:t></w:r></w:p><w:sectPr/></w:body></w:document>')
    boundary = "qa-directory-" + uuid.uuid4().hex
    body = bytearray()
    for key, value in {"name": name, "category": "QA", "bindingMode": "auto"}.items():
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="retained.docx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n'.encode())
    body.extend(document.getvalue())
    body.extend(f"\r\n--{boundary}--\r\n".encode())
    status, response = call(admin, "/platform/templates", "POST", bytes(body), {
        **token(admin), "Content-Type": "multipart/form-data; boundary=" + boundary})
    assert status == 201, (status, response.decode())
    draft = json.loads(response)
    return api(f'/platform/templates/{draft["id"]}/publish', "POST", {"version": draft["version"]})


connections = []
try:
    preserve_plugin(admin, "directory", "install")
    suffix = uuid.uuid4().hex[:12]
    role = api("/platform/roles", "POST", {
        "code": "qa_dir_" + suffix, "name": "QA retained directory role", "enabled": True,
        "authorities": ["platform:users:access", "platform:users:assign-roles"], "dataScope": "SELF",
        "fileQuotaBytesLimit": 987654321, "fileQuotaFilesLimit": 17}, 201)
    user = api("/platform/users", "POST", {
        "username": "qa_dir_" + suffix, "displayName": "QA retained directory user",
        "password": config["LUMIRA_BOOTSTRAP_PASSWORD"]}, 201)
    parent = api("/platform/organizations", "POST", {
        "code": "qa_parent_" + suffix, "name": "QA retained organization", "enabled": True, "sortNo": 3}, 201)
    child = api("/platform/organizations", "POST", {
        "code": "qa_child_" + suffix, "name": "QA retained child organization", "enabled": True,
        "parentId": parent["id"], "sortNo": 4}, 201)
    membership = api(f'/platform/users/{user["id"]}/organizations', "PUT", {
        "version": user["version"], "organizationIds": [child["id"]]})
    api(f'/platform/users/{user["id"]}/roles', "PUT", {
        "version": membership["version"], "roleIds": [role["id"]]})
    scoped = client()
    connections.append(scoped)
    assert login(scoped, user["username"], config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    assert role["id"] in api(f'/platform/users/{user["id"]}/roles', connection=scoped)["roleIds"]
    api("/platform/roles", expected=403, connection=scoped)
    admin_id = api("/auth/me")["userId"]
    api(f"/platform/users/{admin_id}/roles", expected=403, connection=scoped)
    quota_path = f'/platform/users/{user["id"]}/file-quota'
    directory_quota = api(quota_path, connection=scoped)
    api(f"/platform/users/{admin_id}/file-quota", expected=403, connection=scoped)
    directory_preview = api(quota_path + f'/preview?roleIds={role["id"]}', connection=scoped)
    api(f'/platform/users/{admin_id}/file-quota/preview?roleIds={role["id"]}', expected=403, connection=scoped)
    quota = api("/account/files/usage", connection=scoped)
    assert (quota["maximumBytes"], quota["maximumFiles"]) == (987654321, 17), quota
    assert api(f'/platform/users/{user["id"]}/file-quota/preview?roleIds={role["id"]}')
    template = create_template("QA retained directory template")
    preview_path = f'/platform/templates/{template["id"]}/preview'
    preview_input = {"templateVersion": template["publishedVersion"], "userId": user["id"], "values": {}}
    status, pdf = write(admin, preview_path, "POST", preview_input)
    assert status == 200 and pdf.startswith(b"%PDF"), (status, pdf[:200])
    tables = ["platform_user", "platform_role", "platform_role_authority", "platform_user_role", "platform_organization",
              "platform_user_organization", "platform_role_authentication_policy", "platform_print_template", "platform_print_template_version",
              "platform_user_import_preview", "platform_user_import_task"]
    installed = {entry["id"] for entry in api("/platform/modules/plugins") if entry["installed"]}
    removed_dependencies = []
    if any(consumer in installed for consumer in ("localization", "ai", "authentication-console")):
        plan = api("/platform/modules/plugins/directory/uninstall-plan", "POST", {})
        assert plan["blockers"], "An installed AI consumer must block directory unload"
        # This is an explicit reverse-order QA lifecycle, never an automatic dependency cascade.
        for dependent in ("localization", "ai", "authentication-console"):
            if dependent in installed:
                preserve_plugin(admin, dependent, "uninstall")
                removed_dependencies.append(dependent)
    before = {table: retain_digest(table) for table in tables}
    preserve_plugin(admin, "directory", "uninstall")
    for table, digest in before.items():
        assert retain_digest(table) == digest, "Uninstall changed " + table
    for path in ("/platform/users", "/platform/roles", "/platform/organizations", "/platform/templates", "/platform/online-users", quota_path, quota_path + f'/preview?roleIds={role["id"]}'):
        assert call(admin, path)[0] != 200, path
    assert api("/auth/me")["username"] == "admin"
    assert api("/account/files/usage", connection=scoped) == quota
    assert api("/platform/monitoring/summary")["database"]["status"]
    restored_session = client()
    connections.append(restored_session)
    assert login(restored_session, user["username"], config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    preserve_plugin(admin, "directory", "install")
    for table, digest in before.items():
        assert retain_digest(table) == digest, "Reinstall changed " + table
    assert api(f'/platform/users/{user["id"]}')["username"] == user["username"]
    assert api(f'/platform/organizations/{child["id"]}')["parentId"] == parent["id"]
    assert api(f'/platform/users/{user["id"]}/roles')["roleIds"] == [role["id"]]
    assert api(quota_path, connection=scoped) == directory_quota
    assert api(quota_path + f'/preview?roleIds={role["id"]}', connection=scoped) == directory_preview
    assert api(f'/platform/templates/{template["id"]}')["publishedVersion"] == template["publishedVersion"]
    status, pdf = write(admin, preview_path, "POST", preview_input)
    assert status == 200 and pdf.startswith(b"%PDF"), (status, pdf[:200])
    for dependent in reversed(removed_dependencies):
        preserve_plugin(admin, dependent, "install")
    fixture = {"runToken": config["LUMIRA_QA_RUN_TOKEN"], "username": user["username"], "userId": user["id"],
               "roleName": role["name"], "organizationName": parent["name"], "templateName": template["name"]}
    Path(".runtime/architecture-fix").mkdir(parents=True, exist_ok=True)
    Path(".runtime/architecture-fix/directory-fixture.json").write_text(json.dumps(fixture))
    print("PASS: independent directory CRUD and mixed-scope lookup; all users/roles/memberships/organization tree/template bytes+versions retained; login and stored role quotas survive unload; reinstall restores original identities and PDF rendering")
finally:
    for connection in [admin, *connections]:
        write(connection, "/auth/logout", "POST", {})
