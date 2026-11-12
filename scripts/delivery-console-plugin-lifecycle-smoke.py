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


try:
    operate("delivery-console", "install")
    for scope,allowed in [("ALL","mail"),("ALL","sms"),("SELF","mail")]:
        user_id,role_id=str(uuid.uuid4()),str(uuid.uuid4())
        username="qa_delivery_"+uuid.uuid4().hex
        sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{user_id}','{username}','Delivery scope QA',password_hash FROM platform_user WHERE username='admin'; INSERT INTO platform_role(id,code,name,data_scope) VALUES('{role_id}','{username}','Delivery scope QA','{scope}'); INSERT INTO platform_user_role(user_id,role_id) VALUES('{user_id}','{role_id}'); INSERT INTO platform_role_authority(role_id,authority) VALUES('{role_id}','platform:{allowed}-deliveries:access')")
        session=client()
        assert login(session,username,config["LUMIRA_BOOTSTRAP_PASSWORD"])==200
        for kind in ["mail","sms"]:
            status,_=call(session,f"/platform/{kind}-deliveries")
            assert status==(200 if scope=="ALL" and kind==allowed else 403),(scope,allowed,kind,status)
        write(session,"/auth/logout","POST",{})
    rows=[]
    for kind in ("mail", "sms"):
        id=str(uuid.uuid4())
        table="platform_"+kind+"_delivery"
        sql(f"INSERT INTO {table}(id,payload,state,attempts,expires_at,last_result) VALUES('{id}','v1:fixture:abcdef','ACCEPTED',1,now()+interval '1 hour','ACCEPTED')")
        snapshot=sql(f"SELECT row_to_json(t)::text FROM {table} t WHERE id='{id}'")
        assert any(row["id"]==id for row in request(f"/platform/{kind}-deliveries")["items"])
        rows.append((kind,id,snapshot))
    operate("delivery-console", "uninstall")
    for kind,id,snapshot in rows:
        assert call(admin,f"/platform/{kind}-deliveries")[0]!=200
        assert sql(f"SELECT row_to_json(t)::text FROM platform_{kind}_delivery t WHERE id='{id}'")==snapshot
    owner=sql("SELECT id FROM platform_user WHERE username='admin'")
    published=request("/platform/notifications","POST",{"scope":"USERS","userIds":[owner],"title":"QA delivery console absent","body":"Independent queue"},201)
    event=published["eventIds"][0]
    wait_for(lambda:sql(f"SELECT status FROM platform_event_outbox WHERE id='{event}'")=="DELIVERED","Other queues stopped with management console")
    assert request("/platform/monitoring/summary")["database"]["status"]
    operate("delivery-console", "install")
    for kind,id,snapshot in rows:
        assert any(row["id"]==id for row in request(f"/platform/{kind}-deliveries")["items"])
        assert sql(f"SELECT row_to_json(t)::text FROM platform_{kind}_delivery t WHERE id='{id}'")==snapshot
    print("PASS: independent mail/SMS management preserve unload/reinstall; original delivery rows retained; unrelated queue and monitoring remain available")
finally:
    write(admin,"/auth/logout","POST",{})
