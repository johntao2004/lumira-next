"""Real JSON byte limits, including chunked requests, before any import writes."""
from smoke_support import *
import uuid
code='qa_body_'+uuid.uuid4().hex
c=client();assert login(c,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
payload=json.dumps({'schemaVersion':1,'code':code,'name':'Byte limit QA','enabled':True,'structure':'FLAT','items':[]}).encode()
limit=20*1024*1024
exact=payload+b' '*(limit-len(payload))
status,body=call(c,'/platform/dictionaries/preview','POST',exact,{**token(c),'Content-Type':'application/json'});assert status==200,(status,body)
for endpoint in ['preview','import']:
    status,body=call(c,'/platform/dictionaries/'+endpoint,'POST',exact+b' ',{**token(c),'Content-Type':'application/json'})
    assert status==413,(status,body)
    assert json.loads(body)['detail']=='dictionaryDatasetTooLarge'
# Explicit framing bypasses urllib proxy handlers for this direct chunked check.
import http.client
cookie_request=urllib.request.Request('http://127.0.0.1:8090/api/platform/dictionaries/preview')
for handler in c.handlers:
    if hasattr(handler,'cookiejar'):handler.cookiejar.add_cookie_header(cookie_request)
connection=http.client.HTTPConnection('127.0.0.1',8090,timeout=30)
try:
    connection.request('POST','/api/platform/dictionaries/preview',iter([exact,b' ']),{**token(c),'Cookie':cookie_request.get_header('Cookie',''),'Content-Type':'application/json'},encode_chunked=True)
    response=connection.getresponse();body=response.read()
    assert response.status==413 and json.loads(body)['detail']=='dictionaryDatasetTooLarge',(response.status,body)
finally:connection.close()
assert sql("SELECT count(*) FROM platform_dictionary WHERE code='"+code+"'")=='0'
print('PASS: exact 20 MB preview, oversized import/preview 413, chunked body 413, no dictionary writes')
