"""Real authenticated email API checks. No mail is enqueued or sent."""
import hashlib, json, secrets, uuid
from smoke_support import sql, client, call, login, write, config
prefix='qa_email_http_'+uuid.uuid4().hex
ids=[str(uuid.uuid4()) for _ in range(3)]
names=[prefix+str(n) for n in range(3)]
clients=[client() for _ in ids]
def challenge(owner,address):
    token=secrets.token_urlsafe(32);digest=hashlib.sha256(token.encode()).hexdigest()
    sql("INSERT INTO platform_account_email(user_id,pending_email,token_hash,expires_at) VALUES('%s','%s','%s',CURRENT_TIMESTAMP+INTERVAL '10 minutes') ON CONFLICT(user_id) DO UPDATE SET pending_email=EXCLUDED.pending_email,token_hash=EXCLUDED.token_hash,expires_at=EXCLUDED.expires_at,failures=0"%(owner,address,digest))
    return token
def confirm(c,t):return write(c,'/account/email/confirm','POST',{'token':t})[0]
try:
    for owner,name,c in zip(ids,names,clients):
        sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Email API QA',password_hash FROM platform_user WHERE username='admin'"%(owner,name))
        assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    a,b,limited=clients
    assert call(client(),'/account/email')[0]==401
    assert call(a,'/account/email/confirm','POST',b'{}',{'Content-Type':'application/json'})[0]==403
    status,body=call(a,'/account/email');assert status==200,status
    view=json.loads(body);assert view['email'] is None and view['version']==0
    assert set(view)=={'email','verifiedAt','version','pendingEmail','expiresAt','requestedAt'}
    assert write(a,'/account/email/request','POST',{'version':0,'email':'qa@example.invalid','currentPassword':'wrong'})[0]==400
    assert sql("SELECT count(*) FROM platform_account_email WHERE user_id='%s'"%ids[0])=='0'
    code=challenge(ids[0],prefix+'@example.invalid')
    assert confirm(b,code)==400,'Foreign account accepted token'
    assert confirm(a,'invalid')==400
    assert sql("SELECT failures FROM platform_account_email WHERE user_id='%s'"%ids[0])=='1','Failure rolled back'
    assert confirm(a,code)==204
    assert confirm(a,code)==400,'Token replay accepted'
    view=json.loads(call(a,'/account/email')[1]);assert view['email']==prefix+'@example.invalid' and view['pendingEmail'] is None
    assert 'token' not in view and 'tokenHash' not in view
    duplicate=challenge(ids[1],prefix.upper()+'@example.invalid')
    assert confirm(b,duplicate)==409,'Duplicate email accepted'
    assert sql("SELECT pending_email IS NOT NULL AND email IS NULL FROM platform_account_email WHERE user_id='%s'"%ids[1])=='t'
    exhausted=challenge(ids[1],prefix+'-second@example.invalid')
    for _ in range(5):assert confirm(b,'invalid')==400
    assert confirm(b,exhausted)==400,'Exhausted challenge accepted'
    # Fill this account's current minute bucket immediately before the call, avoiding wall-clock boundary loops.
    key='email-verify:'+hashlib.sha256(names[2].encode()).hexdigest()
    sql("INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES('%s',date_trunc('minute',CURRENT_TIMESTAMP),10)"%key)
    assert confirm(limited,'invalid')==429
    assert sql("SELECT attempts FROM platform_rate_bucket WHERE bucket_key='%s'"%key)=='11'
    assert sql("SELECT count(*) FROM platform_audit WHERE actor='%s' AND action='account.emailVerified'"%names[0])=='1'
    print('PASS: email status, anonymous/CSRF, wrong password, account binding, committed failures, single use, uniqueness conflict, attempt cap and HTTP limiter; no email sent')
finally:
    for c in clients:
        try:write(c,'/auth/logout','POST',{})
        except Exception:pass
    sql("DELETE FROM platform_audit WHERE actor LIKE '%s%%'"%prefix)
    sql("DELETE FROM platform_user WHERE username LIKE '%s%%'"%prefix)
    for name in names:
        sql("DELETE FROM platform_rate_bucket WHERE bucket_key='email-verify:%s'"%hashlib.sha256(name.encode()).hexdigest())
