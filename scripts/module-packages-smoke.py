import json, uuid
from smoke_support import client, call, login, token, sql, config

c = client()
assert login(c, 'admin', config['LUMIRA_BOOTSTRAP_PASSWORD']) == 200
digest = ('b' + uuid.uuid4().hex).ljust(64, 'c')[:64]
module_id = 'qa-package-' + uuid.uuid4().hex[:12]
manifest = {
    'moduleId': module_id,
    'name': 'QA package',
    'version': '1.0.0',
    'schemaVersion': 'schema-1',
    'apiVersion': 'lumira.platform/v1',
    'artifactDigest': digest,
    'artifactRef': 'oci://registry.example/lumira/' + module_id + '@sha256:' + digest,
    'dependencies': ['platform'],
    'permissions': ['qa-package:example:read'],
    'routes': ['/dashboard/qa-package'],
}
reason = 'HTTP module package contract verification'
try:
    status, body = call(c, '/platform/modules/packages')
    assert status == 200, body
    status, body = call(c, '/platform/modules/packages', 'POST', json.dumps({'manifest': manifest, 'reason': reason}).encode(), {**token(c), 'Content-Type': 'application/json'})
    assert status == 201, body
    staged = json.loads(body)
    assert staged['status'] == 'STAGED' and staged['moduleId'] == module_id
    assert staged['runtimeReady'] is False
    status, body = call(c, '/platform/modules/packages', 'POST', json.dumps({'manifest': manifest, 'reason': reason}).encode(), {**token(c), 'Content-Type': 'application/json'})
    assert status == 201, body
    duplicate = json.loads(body)
    assert duplicate['id'] == staged['id'] and duplicate['revision'] == staged['revision']
    status, body = call(c, f"/platform/modules/packages/{staged['id']}/activate", 'POST', json.dumps({'revision': 1, 'reason': 'attempt unknown activation'}).encode(), {**token(c), 'Content-Type': 'application/json'})
    assert status == 409 and json.loads(body)['detail'] == 'moduleNotRegistered', body
    status, body = call(c, f"/platform/modules/packages/{staged['id']}/reject", 'POST', json.dumps({'revision': 1, 'reason': 'reject test package'}).encode(), {**token(c), 'Content-Type': 'application/json'})
    assert status == 200, body
    rejected = json.loads(body)
    assert rejected['status'] == 'REJECTED' and rejected['revision'] == 2
    status, body = call(c, f"/platform/modules/packages/{staged['id']}/reject", 'POST', json.dumps({'revision': 1, 'reason': 'stale reject'}).encode(), {**token(c), 'Content-Type': 'application/json'})
    assert status == 409 and json.loads(body)['detail'] == 'stalePackage', body
    print('PASS: module package list, idempotent stage, runtime registration fence, rejection and stale revision fence')
finally:
    call(c, '/auth/logout', 'POST', None, token(c))
    sql(f"DELETE FROM platform_audit WHERE target_id=(SELECT id FROM platform_module_package WHERE artifact_digest='{digest}'); DELETE FROM platform_module_package WHERE artifact_digest='{digest}';")
