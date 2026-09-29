"""Real event-outbox metadata/permission regression; fixtures contain no business payloads."""
from smoke_support import *
from uuid import uuid4

prefix = 'qa' + uuid4().hex[:6]
name = 'qa_outbox_' + uuid4().hex
user, role, ids = str(uuid4()), str(uuid4()), []
replay_id = str(uuid4())
actor = client()
admin = client()
path = '/platform/event-outbox'
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{user}','{name}','Outbox QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor, name, config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
    assert login(admin, 'admin', config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
    assert call(client(), path)[0] in (401, 403) and call(actor, path)[0] == 403
    sql(f"INSERT INTO platform_role(id,code,name,data_scope) VALUES('{role}','{name}','Scoped outbox','SELF'); INSERT INTO platform_role_authority(role_id,authority) VALUES('{role}','platform:audit:read'); INSERT INTO platform_user_role(user_id,role_id) VALUES('{user}','{role}')")
    assert call(actor, path)[0] == 403
    sql(f"UPDATE platform_role SET data_scope='ALL' WHERE id='{role}'")
    for i in range(105):
        event_id = str(uuid4()); ids.append(event_id)
        state = 'DEAD_LETTER' if i < 5 else ('FAILED' if i < 15 else ('DELIVERED' if i < 55 else 'PENDING'))
        attempts = 8 if state == 'DEAD_LETTER' else 1
        last_error = "'fixture-error'" if state in ('FAILED', 'DEAD_LETTER') else 'NULL'
        delivered_at = 'now()' if state == 'DELIVERED' else 'NULL'
        sql(f"INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload,status,attempts,last_error,delivered_at) VALUES('{event_id}','{prefix}','fixture.event','{prefix}-{i}','{{\"fixture\":true}}','{state}',{attempts},{last_error},{delivered_at})")
    sql(f"INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload,status,attempts,last_error) VALUES('{replay_id}','replay-fixture','fixture.replay','replay-{replay_id}','{{\"fixture\":true}}','DEAD_LETTER',8,'fixture-error')")
    def page(query):
        status, body = call(actor, path + '?' + query)
        assert status == 200, (status, body)
        return json.loads(body)
    one, two = page('pageSize=100&search=' + prefix), page('page=2&pageSize=100&search=' + prefix)
    assert one['total'] == 105 and len(one['items']) == 100 and len(two['items']) == 5
    assert not ({row['id'] for row in one['items']} & {row['id'] for row in two['items']})
    expected = {'id','sourceType','eventType','eventKey','status','attempts','lastError','availableAt','deliveredAt','createdAt','updatedAt'}
    assert all(set(row) == expected for row in one['items'] + two['items'])
    assert page('search=' + prefix + '&status=DEAD_LETTER')['total'] == 5
    assert page('search=' + prefix + '&status=FAILED')['total'] == 10
    for query in ['page=0', 'pageSize=101', 'status=unknown']:
        assert call(actor, path + '?' + query)[0] == 400
    assert json.loads(call(actor, path + '?search=%25')[1])['total'] == 0
    assert write(actor, path + '/' + replay_id + '/replay', 'POST', {'reason':'operator approval'})[0] == 403
    assert write(admin, path + '/' + replay_id + '/replay', 'POST', {})[0] == 400
    status, body = write(admin, path + '/' + replay_id + '/replay', 'POST', {'reason':'provider recovered; approved for retry'})
    assert status == 200, (status, body)
    replay = json.loads(body)
    assert replay == {'id': replay_id, 'status':'PENDING', 'attempts':0}, replay
    assert sql(f"SELECT count(*) FROM platform_event_replay_approval WHERE event_id='{replay_id}'") == '1'
    assert sql(f"SELECT reason FROM platform_event_replay_approval WHERE event_id='{replay_id}'") == 'provider recovered; approved for retry'
    assert sql(f"SELECT count(*) FROM platform_audit WHERE target_id='{replay_id}' AND action='event.replay.approved'") == '1'
    assert write(admin, path + '/' + replay_id + '/replay', 'POST', {'reason':'second approval'})[0] == 409
    sql(f"UPDATE platform_role SET data_scope='SELF' WHERE id='{role}'")
    assert call(actor, path)[0] == 403
    print('PASS: event outbox metadata permission/global scope, immediate revocation, 105-row pagination, status filtering, safe fields and bounded queries')
finally:
    sql(f"DELETE FROM platform_event_replay_approval WHERE event_id='{replay_id}'")
    sql(f"DELETE FROM platform_audit WHERE target_id='{replay_id}'")
    if ids:
        sql('DELETE FROM platform_event_outbox WHERE id IN (' + ','.join("'" + i + "'" for i in ids) + ')')
    sql(f"DELETE FROM platform_event_outbox WHERE id='{replay_id}'")
    sql(f"DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{user}'; DELETE FROM platform_role WHERE id='{role}'")
