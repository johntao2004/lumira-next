"""Dictionary type administration uses real permissions, versions and atomic writes."""
from smoke_support import *
import uuid
prefix='qa_dict_'+uuid.uuid4().hex
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
created=[]
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+prefix+"','Dictionary QA',password_hash FROM platform_user WHERE username='admin'")
    other=client();assert login(other,prefix,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    payload={'code':prefix,'name':'Dictionary QA','enabled':True,'structure':'TREE','remark':'test'}
    assert call(other,'/platform/dictionaries')[0]==403
    assert write(other,'/platform/dictionaries','POST',payload)[0]==403
    status,body=write(admin,'/platform/dictionaries','POST',payload);assert status==201,(status,body)
    record=json.loads(body);created.append(record['id']);path='/platform/dictionaries/'+record['id']
    assert record['structure']=='TREE' and record['version']==0
    assert write(admin,'/platform/dictionaries','POST',payload)[0]==409
    status,body=call(admin,'/platform/dictionaries?search='+prefix);assert status==200 and json.loads(body)['total']==1
    assert call(admin,'/platform/dictionaries?pageSize=101')[0]==400
    assert write(admin,path,'PUT',{**payload,'version':0,'structure':'INVALID'})[0]==400
    status,body=write(admin,path,'PUT',{**payload,'version':0,'enabled':False});assert status==200 and json.loads(body)['version']==1
    assert write(admin,path,'PUT',{**payload,'version':0})[0]==409
    assert write(admin,path+'?version=0','DELETE',{})[0]==409
    assert sql("SELECT count(*) FROM platform_audit WHERE target_id='"+record['id']+"'")=='2'
    assert write(admin,path+'?version=1','DELETE',{})[0]==204
    assert call(admin,path)[0]==404
    print('PASS: dictionary permission isolation, create, duplicate, search, page bounds, validation rollback, disable, stale update/delete and audit')
finally:
    for id in created:sql("DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_dictionary WHERE id='"+id+"'")
    sql("DELETE FROM spring_session WHERE principal_name='"+prefix+"'; DELETE FROM platform_user WHERE username='"+prefix+"'")
