"""Enabled dictionary lookup is usable without admin permissions and filters ancestors."""
from smoke_support import *
import uuid
code='qa_lookup_'+uuid.uuid4().hex
type_id=str(uuid.uuid4());root=str(uuid.uuid4());child=str(uuid.uuid4())
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+code+"','Lookup QA',password_hash FROM platform_user WHERE username='admin'")
    sql("INSERT INTO platform_dictionary(id,code,name,structure) VALUES('"+type_id+"','"+code+"','Lookup QA','TREE')")
    sql("INSERT INTO platform_dictionary_item(id,dictionary_id,value,label) VALUES('"+root+"','"+type_id+"','root','Root'); INSERT INTO platform_dictionary_item(id,dictionary_id,parent_id,value,label) VALUES('"+child+"','"+type_id+"','"+root+"','child','Child')")
    c=client();assert login(c,code,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    path='/dictionaries/'+code+'/items'
    assert call(client(),path)[0]==401
    assert call(c,'/platform/dictionaries')[0]==403
    status,body=call(c,path+'?pageSize=1');assert status==200
    data=json.loads(body);assert data['total']==2 and len(data['items'])==1
    assert set(data['items'][0])=={'id','parentId','value','label','sortOrder'}
    status,body=call(c,path+'?pageSize=1&page=2');assert status==200 and json.loads(body)['items'][0]['id']!=data['items'][0]['id']
    status,body=call(c,path+'?search=Child');assert status==200 and json.loads(body)['total']==1
    assert call(c,path+'/'+child)[0]==200
    sql("UPDATE platform_dictionary_item SET enabled=false WHERE id='"+root+"'")
    status,body=call(c,path);assert status==200 and json.loads(body)['total']==0
    assert call(c,path+'/'+child)[0]==404
    sql("UPDATE platform_dictionary_item SET enabled=true WHERE id='"+root+"'; UPDATE platform_dictionary_item SET enabled=false WHERE id='"+child+"'")
    status,body=call(c,path);assert status==200 and json.loads(body)['total']==1
    sql("UPDATE platform_dictionary SET enabled=false WHERE id='"+type_id+"'")
    assert call(c,path)[0]==404
    assert call(c,path+'/'+root)[0]==404
    assert write(c,path,'POST',{})[0]==403
    assert call(c,path+'?pageSize=101')[0]==400
    print('PASS: authenticated lookup, no admin access, minimal fields, pagination/search, parent disable hides descendants, item/type disable, mutation denial')
finally:
    sql("DELETE FROM platform_dictionary_item WHERE id='"+child+"'; DELETE FROM platform_dictionary_item WHERE id='"+root+"'; DELETE FROM platform_dictionary WHERE id='"+type_id+"'; DELETE FROM spring_session WHERE principal_name='"+code+"'; DELETE FROM platform_user WHERE username='"+code+"'")
