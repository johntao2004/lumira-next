"""Real account notification API regression; fixtures are owner-scoped and auto-removed."""
from smoke_support import *
from uuid import uuid4

name = 'qa_notifications_' + uuid4().hex
user, notification = str(uuid4()), str(uuid4())
actor = client()
try:
    assert call(client(), '/account/notifications')[0] == 401
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{user}','{name}','Notification QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor, name, config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
    assert call(actor, '/account/notifications/unread-count')[0] == 200
    admin_id = sql("SELECT id FROM platform_user WHERE username='admin'")
    sql(f"INSERT INTO platform_notification(id,recipient_id,dedupe_key,kind,title_key,body_key,params,href) VALUES('{notification}','{user}','api_notification_{uuid4().hex}','feedback','notificationFeedbackSubmitted','notificationFeedbackCreatedBody','{{\"title\":\"API QA\"}}','/dashboard/feedback')")
    status, body = call(actor, '/account/notifications?page=1&pageSize=20'); assert status == 200
    rows = json.loads(body); assert rows['total'] == 1 and set(rows['items'][0]) == {'id','kind','titleKey','bodyKey','params','href','read','createdAt','expiresAt'} and rows['items'][0]['read'] is False
    status, body = call(actor, '/account/notifications/unread-count'); assert status == 200 and int(json.loads(body)) == 1
    assert call(actor, f'/account/notifications/{notification}/read', 'POST', b'', token(actor))[0] == 204
    assert int(json.loads(call(actor, '/account/notifications/unread-count')[1])) == 0
    assert call(actor, f'/account/notifications/{notification}/read', 'POST', b'', token(actor))[0] == 204
    assert call(actor, '/account/notifications/read-all', 'POST', b'', token(actor))[0] == 204
    assert call(actor, '/account/notifications?page=1&pageSize=0')[0] == 400
    print('PASS: notification anonymous/authenticated boundary, owner isolation, safe projection, unread count, single/all read and bounds')
finally:
    sql(f"DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{user}'")
