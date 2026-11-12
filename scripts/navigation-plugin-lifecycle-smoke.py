"""Dependency-aware real business plugin acceptance in a bound disposable QA database."""
import json
import time
import uuid
from smoke_support import client, call, login, write, sql, config, wait_for_readiness

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
base = "/platform/modules/plugins"


def request(path, method="GET", payload=None, expected=200, connection=None):
    connection = connection or admin
    status, body = call(connection, path) if method == "GET" else write(connection, path, method, payload or {})
    assert status == expected, (path, status, body.decode())
    return json.loads(body) if body else None


def wait_for(check, message):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.2)
    raise AssertionError(message)


def operate(plugin, kind):
    plan = request(f"{base}/{plugin}/{kind}-plan", "POST")
    assert not plan["blockers"], plan
    operation = request(f"{base}/{plugin}/{kind}", "POST", {"revision": plan["revision"], "dataMode": "PRESERVE"})
    def finished():
        nonlocal operation
        operation = request(base + "/operations/" + operation["id"])
        return operation["status"] in ("SUCCEEDED", "FAILED")
    wait_for(finished, "Plugin lifecycle timed out")
    assert operation["status"] == "SUCCEEDED", operation
    wait_for_readiness()


def contains(nodes,id):
    stack=list(nodes)
    while stack:
        node=stack.pop()
        if node["id"]==id:return True
        stack.extend(node["children"])
    return False

try:
    operate("navigation-console","install")
    code="qa.nav."+uuid.uuid4().hex
    parent=request("/platform/navigation/menus","POST",{"menuCode":code,"labelKey":"qaNavigation","labelDefault":"QA independent navigation","menuType":"CATALOG","sortOrder":79,"enabled":True},201)
    section="qa-nav-"+uuid.uuid4().hex[:16]
    child=request("/platform/navigation/menus","POST",{"parentId":parent["id"],"menuCode":code+".page","labelKey":"qaNavigationPage","labelDefault":"QA retained page","menuType":"PAGE","sectionKey":section,"path":"/dashboard/"+section,"sortOrder":3,"enabled":True,"moduleId":"platform"},201)
    request("/platform/navigation/menus","POST",{"menuCode":code+".invalid","labelKey":"qaNavigation","labelDefault":"Invalid module reference","menuType":"PAGE","sectionKey":"invalid-"+section,"path":"/dashboard/invalid-"+section,"moduleId":"unknown-module"},400)
    assert contains(request("/platform/navigation"),child["id"])
    snapshots={id:sql(f"SELECT row_to_json(t)::text FROM platform_navigation_menu t WHERE id='{id}'") for id in [parent["id"],child["id"]]}
    operate("navigation-console","uninstall")
    assert call(admin,"/platform/navigation/menus")[0]!=200
    assert contains(request("/platform/navigation"),child["id"]),"Kernel menu stopped with admin plugin"
    assert request("/platform/monitoring/summary")["database"]["status"]
    for id,snapshot in snapshots.items():assert sql(f"SELECT row_to_json(t)::text FROM platform_navigation_menu t WHERE id='{id}'")==snapshot
    operate("navigation-console","install")
    for id,snapshot in snapshots.items():
        assert request("/platform/navigation/menus/"+id)["id"]==id
        assert sql(f"SELECT row_to_json(t)::text FROM platform_navigation_menu t WHERE id='{id}'")==snapshot
    assert contains(request("/platform/navigation"),child["id"])
    print("PASS: independent navigation administration; core runtime menu survives unload; custom tree identities/labels/order/version preserved; admin endpoints restored")
finally:
    write(admin,"/auth/logout","POST",{})
