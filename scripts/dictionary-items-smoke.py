"""Tree item boundaries, versions and dictionary lifecycle protections."""
from smoke_support import *
import uuid
prefix='qa_items_'+uuid.uuid4().hex
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
types=[];items=[]
try:
    for suffix in ['a','b']:
        status,body=write(admin,'/platform/dictionaries','POST',{'code':prefix+suffix,'name':'Item QA','enabled':True,'structure':'TREE'});assert status==201
        types.append(json.loads(body)['id'])
    base='/platform/dictionaries/'+types[0]+'/items'
    payload={'value':'root','label':'Root','sortOrder':10,'enabled':True}
    status,body=write(admin,base,'POST',payload);assert status==201,(status,body)
    root=json.loads(body);items.append(root['id'])
    childPayload={**payload,'value':'child','label':'Child','parentId':root['id'],'sortOrder':0}
    status,body=write(admin,base,'POST',childPayload);assert status==201
    child=json.loads(body);items.append(child['id'])
    status,body=call(admin,base+'?parent=root');assert status==200 and json.loads(body)['total']==1 and json.loads(body)['items'][0]['id']==root['id']
    status,body=call(admin,base+'?parent='+root['id']);assert status==200 and json.loads(body)['total']==1 and json.loads(body)['items'][0]['id']==child['id']
    status,body=call(admin,base+'/'+child['id']+'/ancestors');assert status==200 and [i['id'] for i in json.loads(body)]==[root['id'],child['id']]
    assert call(admin,'/platform/dictionaries/'+types[1]+'/items?parent='+root['id'])[0]==404
    assert call(admin,'/platform/dictionaries/'+types[1]+'/items/'+child['id']+'/ancestors')[0]==404
    assert call(admin,base+'?parent=invalid')[0]==400
    assert write(admin,base,'POST',childPayload)[0]==409
    assert write(admin,'/platform/dictionaries/'+types[1]+'/items','POST',childPayload)[0]==404
    assert call(admin,'/platform/dictionaries/'+types[1]+'/items/'+root['id'])[0]==404
    assert write(admin,base+'/'+root['id'],'PUT',{**payload,'parentId':child['id'],'version':0})[0]==409
    assert write(admin,base+'/'+root['id'],'PUT',{**payload,'parentId':root['id'],'version':0})[0]==409
    assert write(admin,base+'/'+root['id']+'?version=0','DELETE',{})[0]==409
    assert write(admin,'/platform/dictionaries/'+types[0]+'?version=0','DELETE',{})[0]==409
    assert write(admin,'/platform/dictionaries/'+types[0],'PUT',{'name':'Item QA','enabled':True,'structure':'FLAT','version':0})[0]==409
    status,body=call(admin,base+'?pageSize=1');assert status==200 and json.loads(body)['total']==2 and json.loads(body)['items'][0]['id']==child['id']
    status,body=call(admin,base+'?pageSize=1&page=2');assert status==200 and json.loads(body)['items'][0]['id']==root['id']
    status,body=call(admin,base+'?search=Child');assert status==200 and json.loads(body)['total']==1
    status,body=write(admin,base+'/'+child['id'],'PUT',{**childPayload,'enabled':False,'version':0});assert status==200 and json.loads(body)['version']==1
    assert write(admin,base+'/'+child['id'],'PUT',{**childPayload,'version':0})[0]==409
    assert write(admin,base+'/'+child['id']+'?version=0','DELETE',{})[0]==409
    assert write(admin,base+'/'+child['id']+'?version=1','DELETE',{})[0]==204
    assert write(admin,'/platform/dictionaries/'+types[0],'PUT',{'name':'Item QA','enabled':True,'structure':'FLAT','version':0})[0]==200
    assert write(admin,base,'POST',childPayload)[0]==409
    assert write(admin,base+'/'+root['id']+'?version=0','DELETE',{})[0]==204
    assert call(client(),base)[0] in (401,403)
    print('PASS: item uniqueness, dictionary isolation, parent/self cycles, occupied deletion, flat conversion, paging/order/search, disable and stale versions')
finally:
    for id in reversed(items):sql("DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_dictionary_item WHERE id='"+id+"'")
    for id in types:sql("DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_dictionary WHERE id='"+id+"'")
