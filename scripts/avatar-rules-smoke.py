from smoke_support import *
from uuid import uuid4
from pathlib import Path
import struct,zlib,copy
owner=str(uuid4());name='qa_avatar_rules_'+uuid4().hex;admin=client();user=client();changed=False
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d))
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00\xff'))+chunk(b'IEND',b'')
def post():return call(user,'/account/avatar?version=0','POST',png,{'Content-Type':'application/octet-stream',**token(user)})
def score():return json.loads(call(user,'/account/profile-completion')[1])['score']
try:
    assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    initial=json.loads(call(admin,'/platform/settings/profile-fields')[1]);assert initial['version']==0,'Refuse to overwrite configured profile fields'
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Avatar Rules QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(user,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert score()['totalWeight']==20  # Legacy schemas retain the old denominator.
    data=copy.deepcopy(initial['data'])
    for field in data['fields']:field['visible']=field['key']=='displayName'
    avatar={'key':'avatarUrl','label':'Avatar QA','description':'Rule QA','type':'IMAGE','visible':True,'required':True,'weight':20,'placeholder':'','groupKey':'basic','groupLabel':'Basic information','sortNo':50,'custom':False,'options':[]}
    data['fields'].append(avatar)
    status,body=call(admin,'/platform/settings/profile-fields','PUT',json.dumps({'version':0,'data':data,'changeReason':'Avatar rules QA'}).encode(),{'Content-Type':'application/json',**token(admin)});assert status==200,(status,body);changed=True
    version=json.loads(body)['version']
    assert score()['totalWeight']==25 and score()['earnedWeight']==5
    assert all(f['key']!='avatarUrl' for f in json.loads(call(user,'/account/profile')[1])['fields'])
    status,body=post();assert status==200,(status,body)
    assert score()['percent']==100
    status,body=call(user,'/account/avatar?version=1','DELETE',headers=token(user));assert status==400 and json.loads(body)['detail']=='avatarRequired'
    invalid=copy.deepcopy(data);invalid['fields'][-1]['custom']=True
    assert call(admin,'/platform/settings/profile-fields','PUT',json.dumps({'version':version,'data':invalid,'changeReason':'Invalid custom image'}).encode(),{'Content-Type':'application/json',**token(admin)})[0]==400
    avatar['visible']=False
    status,body=call(admin,'/platform/settings/profile-fields','PUT',json.dumps({'version':version,'data':data,'changeReason':'Hide avatar QA'}).encode(),{'Content-Type':'application/json',**token(admin)});assert status==200;version=json.loads(body)['version']
    assert call(user,'/account/avatar?version=1','POST',png,{'Content-Type':'application/octet-stream',**token(user)})[0]==403
    assert call(user,'/account/avatar?version=1','DELETE',headers=token(user))[0]==403
    assert score()['totalWeight']==5 and score()['percent']==100
    # Restoring an older schema without the optional field remains valid.
    status,body=call(admin,'/platform/settings/profile-fields','PUT',json.dumps({'version':version,'data':initial['data'],'changeReason':'Restore legacy rules QA'}).encode(),{'Content-Type':'application/json',**token(admin)});assert status==200
    assert call(user,'/account/avatar?version=1','DELETE',headers=token(user))[0]==200
    assert score()['totalWeight']==20
    print('PASS: optional avatar schema, weighted completion, required removal denial, hidden writes denied, text profile isolation, invalid custom image rejected, legacy schema restoration')
finally:
    ids=sql(f"SELECT id FROM platform_file WHERE owner_id='{owner}'").splitlines()
    sql(f"DELETE FROM platform_account_media WHERE user_id='{owner}'")
    for id in ids:
        for suffix in ['.blob','.upload']:(Path('.runtime/files')/id[:2]/(id+suffix)).unlink(missing_ok=True)
    sql(f"DELETE FROM platform_file WHERE owner_id='{owner}'; DELETE FROM platform_audit WHERE actor='{name}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
    if changed:sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'")
