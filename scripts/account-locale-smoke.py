"""Account-isolated locale persistence across independent authenticated clients."""
from smoke_support import *
import uuid

names = ['qa_locale_' + uuid.uuid4().hex for _ in range(2)]
try:
    for name in names:
        sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'" + name + "','Locale QA',password_hash FROM platform_user WHERE username='admin'")
    a, b, other = client(), client(), client()
    assert login(a, names[0], config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
    assert login(b, names[0], config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
    assert login(other, names[1], config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
    assert call(client(), '/account/locale')[0] == 401
    assert json.loads(call(a, '/account/locale')[1]) == {'locale': None}
    assert call(a, '/account/locale', 'PUT', b'{"locale":"en"}', {'Content-Type': 'application/json'})[0] == 403
    for invalid in [None, '', 'en_US', 'xx', 'a' * 64]:
        assert write(a, '/account/locale', 'PUT', {'locale': invalid})[0] == 400
    version = json.loads(call(a, '/account/profile')[1])['version']
    assert write(a, '/account/locale', 'PUT', {'locale': 'zh-cn', 'username': names[1], 'enabled': False})[0] == 200
    assert json.loads(call(b, '/account/locale')[1]) == {'locale': 'zh-CN'}
    assert json.loads(call(other, '/account/locale')[1]) == {'locale': None}
    for locale in ['en', 'ja', 'ko', 'fr', 'es', 'de', 'zh-TW', 'zh-CN']:
        status, body = write(a, '/account/locale', 'PUT', {'locale': locale})
        assert status == 200 and json.loads(body)['locale'] == locale
        assert json.loads(call(b, '/account/locale')[1])['locale'] == locale
    assert json.loads(call(a, '/account/profile')[1])['version'] == version
    assert sql("SELECT preferred_locale FROM platform_user WHERE username='" + names[0] + "'") == 'zh-CN'
    print('PASS: eight languages, independent clients, owner isolation, canonicalization, invalid tags, CSRF, and unchanged profile version')
finally:
    for name in names:
        sql("DELETE FROM platform_audit WHERE actor='" + name + "'; DELETE FROM spring_session WHERE principal_name='" + name + "'; DELETE FROM platform_user WHERE username='" + name + "'")
