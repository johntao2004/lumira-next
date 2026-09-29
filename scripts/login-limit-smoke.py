"""Account rate limits are atomic, survive requests and reset by the database window."""
from smoke_support import *
import uuid,hashlib
name='qa_login_limit_'+uuid.uuid4().hex
key='account:'+hashlib.sha256(name.encode()).hexdigest()
limit=int(os.environ.get('LUMIRA_LOGIN_ACCOUNT_PER_MINUTE',30))
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+name+"','Limit QA',password_hash FROM platform_user WHERE username='admin'")
    owner=sql("SELECT id FROM platform_user WHERE username='"+name+"'")
    # No credentials or existing users are throttled by this isolated bucket fixture.
    sql("INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES('"+key+"',date_trunc('minute',CURRENT_TIMESTAMP),"+str(limit)+")")
    c=client();headers={**token(c),'Content-Type':'application/x-www-form-urlencoded','X-Forwarded-For':'203.0.113.55'}
    request=urllib.request.Request('http://127.0.0.1:8010/api/auth/login',data=urllib.parse.urlencode({'username':name,'password':config['LUMIRA_BOOTSTRAP_PASSWORD']}).encode(),headers=headers)
    try:
        c.open(request,timeout=20);raise AssertionError('Throttled account logged in')
    except urllib.error.HTTPError as error:
        assert error.code==429 and error.headers.get('Retry-After')=='60'
        assert json.loads(error.read())['detail']=='loginRateLimited'
    assert 'account.loginRateLimited' in sql("SELECT action FROM platform_audit WHERE target_id='"+owner+"'").splitlines()
    assert call(c,'/auth/me')[0]==401
    assert int(sql("SELECT attempts FROM platform_rate_bucket WHERE bucket_key='"+key+"'"))==limit+1
    # Simulate an elapsed window without a wall-clock sleep.
    sql("UPDATE platform_rate_bucket SET window_start=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE bucket_key='"+key+"'")
    assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert sql("SELECT attempts FROM platform_rate_bucket WHERE bucket_key='"+key+"'")=='1'
    d=client();assert login(d,name,'x'*73)==401
    assert 'account.loginRejected' in sql("SELECT action FROM platform_audit WHERE target_id='"+owner+"'").splitlines()
    print('PASS: rate-limited correct password rejected, retry header/code, no session, committed counter, elapsed-window reset, oversized password rejected and both rejections audited')
finally:
    sql("DELETE FROM platform_rate_bucket WHERE bucket_key='"+key+"'; DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM platform_user WHERE username='"+name+"'")
