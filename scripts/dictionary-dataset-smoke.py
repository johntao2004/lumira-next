"""Versioned dataset import/export roundtrip with non-destructive failure behavior."""
from smoke_support import *
import uuid
code='qa_dataset_'+uuid.uuid4().hex;ids=[]
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
base='/platform/dictionaries'
try:
    rows=[{'value':'child','label':'Child','parentValue':'root','sortOrder':1,'enabled':False,'remark':'child note'},{'value':'root','label':'Root','parentValue':None,'sortOrder':2,'enabled':True,'remark':''}]
    data={'schemaVersion':1,'code':code,'name':'Dataset QA','enabled':True,'structure':'TREE','remark':'roundtrip','items':rows}
    status,body=write(admin,base+'/preview','POST',data);assert status==200 and json.loads(body)['itemCount']==2
    assert sql("SELECT count(*) FROM platform_dictionary WHERE code='"+code+"'")=='0'
    assert call(admin,base+'/preview','POST',b'{', {**token(admin),'Content-Type':'application/json'})[0]==400
    status,body=write(admin,base+'/import','POST',data);assert status==201,(status,body)
    original=json.loads(body);ids.append(original['id'])
    status,body=call(admin,base+'/'+original['id']+'/export');assert status==200
    exported=json.loads(body);assert exported==data,(exported,data)
    assert write(admin,base+'/import','POST',data)[0]==409
    status,body=write(admin,base+'/import','POST',{**exported,'code':code+'_copy'});assert status==201
    copy=json.loads(body);ids.append(copy['id'])
    status,body=call(admin,base+'/'+copy['id']+'/export');assert status==200 and json.loads(body)['items']==rows
    for bad in [[{**rows[0],'parentValue':'missing'}],[{**rows[0],'parentValue':'child'}],[rows[1],rows[1]]]:
        assert write(admin,base+'/import','POST',{**data,'code':code+'_bad','items':bad})[0]==400
    assert sql("SELECT count(*) FROM platform_dictionary WHERE code='"+code+"_bad'")=='0'
    assert call(client(),base+'/'+original['id']+'/export')[0] in (401,403)
    print('PASS: unsorted tree import, exact export/reimport, duplicate rejection, invalid datasets leave no partial type, anonymous denial')
finally:
    for id in ids:
        sql("DELETE FROM platform_dictionary_item WHERE dictionary_id='"+id+"' AND parent_id IS NOT NULL; DELETE FROM platform_dictionary_item WHERE dictionary_id='"+id+"'; DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_dictionary WHERE id='"+id+"'")
