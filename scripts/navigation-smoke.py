"""Verify dynamic navigation persistence, global administration grants, and runtime filtering."""
import json
import uuid
from smoke_support import call, client, config, login, sql, token, write

username = "qa_navigation_" + uuid.uuid4().hex
user_id = str(uuid.uuid4())
menu_code = "qa.navigation." + uuid.uuid4().hex
deleted_parent_code = "qa.navigation.deleted-parent." + uuid.uuid4().hex
deleted_child_code = "qa.navigation.deleted-child." + uuid.uuid4().hex
route_page_code = "qa.navigation.route-page." + uuid.uuid4().hex
route_section = "qa-route-" + uuid.uuid4().hex[:16]
admin = client()
ordinary = client()

try:
    password = config["LUMIRA_BOOTSTRAP_PASSWORD"]
    assert login(admin, "admin", password) == 200
    status, body = call(admin, "/platform/navigation/menus")
    assert status == 200, (status, body)
    menus = json.loads(body)
    assert len(menus) >= 23 and all({"id", "parentId", "menuCode", "menuType", "version", "builtIn"} <= set(row) for row in menus)
    builtin = next(row for row in menus if row["menuCode"] == "overview")
    status, body = call(admin, f"/platform/navigation/menus/{builtin['id']}", "PUT", json.dumps({
        "parentId": None, "menuCode": "overview", "labelKey": "overview", "labelDefault": "概览",
        "menuType": "CATALOG", "sortOrder": 10, "enabled": True, "version": builtin["version"],
    }).encode(), {**token(admin), "Content-Type": "application/json"})
    assert status == 409, (status, body)

    status, body = write(admin, "/platform/navigation/menus", "POST", {
        "parentId": None, "menuCode": menu_code, "labelKey": "qaNavigation", "labelDefault": "QA 导航分组",
        "menuType": "CATALOG", "sortOrder": 0, "enabled": True,
    })
    assert status == 201, (status, body)
    created = json.loads(body)

    # A live PAGE address is unique even when the second write uses a new code.
    status, body = write(admin, "/platform/navigation/menus", "POST", {
        "parentId": None, "menuCode": route_page_code, "labelKey": "qaRoutePage", "labelDefault": "QA 路由页面",
        "menuType": "PAGE", "sectionKey": route_section, "path": "/dashboard/" + route_section,
        "sortOrder": 3, "enabled": True,
    })
    assert status == 201, (status, body)
    route_page = json.loads(body)
    status, body = write(admin, "/platform/navigation/menus", "POST", {
        "parentId": None, "menuCode": route_page_code + ".duplicate", "labelKey": "qaRouteDuplicate", "labelDefault": "QA 重复路由",
        "menuType": "PAGE", "sectionKey": route_section, "path": "/dashboard/" + route_section,
        "sortOrder": 4, "enabled": True,
    })
    assert status == 409 and b"navigationRouteConflict" in body, (status, body)
    assert created["builtIn"] is False and created["version"] == 1

    status, body = write(admin, f"/platform/navigation/menus/{created['id']}", "PUT", {
        "parentId": None, "menuCode": menu_code, "labelKey": "qaNavigation", "labelDefault": "QA 导航分组（已更新）",
        "menuType": "CATALOG", "sortOrder": 1, "enabled": True, "version": created["version"],
    })
    assert status == 200 and json.loads(body)["version"] == 2, (status, body)
    created = json.loads(body)
    status, body = write(admin, f"/platform/navigation/menus/{created['id']}/status", "PATCH", {"version": created["version"], "enabled": False})
    assert status == 200 and json.loads(body)["enabled"] is False, (status, body)
    created = json.loads(body)

    # A soft-deleted parent must not remain a valid attachment point for new nodes.
    status, body = write(admin, "/platform/navigation/menus", "POST", {
        "parentId": None, "menuCode": deleted_parent_code, "labelKey": "qaDeletedParent", "labelDefault": "QA 已删除父级",
        "menuType": "CATALOG", "sortOrder": 2, "enabled": True,
    })
    assert status == 201, (status, body)
    deleted_parent = json.loads(body)
    status, body = write(admin, f"/platform/navigation/menus/{deleted_parent['id']}?version={deleted_parent['version']}", "DELETE", {})
    assert status == 204, (status, body)
    status, body = write(admin, "/platform/navigation/menus", "POST", {
        "parentId": deleted_parent["id"], "menuCode": deleted_child_code, "labelKey": "qaDeletedChild", "labelDefault": "QA 无效子菜单",
        "menuType": "PAGE", "sectionKey": "home", "sortOrder": 0, "enabled": True,
    })
    assert status == 400, (status, body)

    status, body = call(admin, "/platform/navigation")
    assert status == 200
    runtime = json.loads(body)
    flat = []
    def visit(nodes):
        for node in nodes:
            flat.append(node)
            visit(node.get("children", []))
    visit(runtime)
    assert {"home", "files", "navigation"} <= {node.get("sectionKey") for node in flat}

    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Navigation QA',password_hash FROM platform_user WHERE username='admin'" % (user_id, username))
    assert login(ordinary, username, password) == 200
    assert call(ordinary, "/platform/navigation/menus")[0] == 403
    status, body = call(ordinary, "/platform/navigation")
    assert status == 200
    flat = []
    visit(json.loads(body))
    assert "navigation" not in {node.get("sectionKey") for node in flat}
    assert "home" in {node.get("sectionKey") for node in flat}
    print("PASS: navigation tree, global admin guard, built-in protection, versioned create/update/status and ordinary-user filtering")
finally:
    try:
        status, body = call(admin, "/platform/navigation/menus")
        if status == 200:
            found = next((row for row in json.loads(body) if row["menuCode"] == menu_code and not row["deleted"]), None)
            if found:
                write(admin, f"/platform/navigation/menus/{found['id']}?version={found['version']}", "DELETE", {})
            found_route = next((row for row in json.loads(body) if row["menuCode"] == route_page_code and not row["deleted"]), None)
            if found_route:
                write(admin, f"/platform/navigation/menus/{found_route['id']}?version={found_route['version']}", "DELETE", {})
    except Exception:
        pass
    sql("DELETE FROM spring_session WHERE principal_name='%s'; DELETE FROM platform_user WHERE id='%s'" % (username, user_id))
