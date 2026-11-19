"""Real HTTPS cookie/CSRF/admin gateway regression, using ignored console-access.json.
Creates and disables only its own console-test-* fixture account. Does not login App accounts.
"""
import json,os,urllib.request,urllib.error,http.cookiejar,uuid
from pathlib import Path

def main():
 c=json.loads(Path(os.getenv('NS_CONSOLE_FILE','.local/console-access.json')).read_text(encoding='utf-8'));base=c.get('url','https://admin.naturalstreaming.net')
 jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar));csrf=None
 def call(path,method='GET',body=None,expected=200,origin=True,protect=True,headers=None):
  h={'User-Agent':'NaturalStreaming/1.0 (Console regression)'}
  if origin:h['Origin']=base
  if protect and csrf:h['X-CSRF-Token']=csrf
  h.update(headers or {})
  if isinstance(body,dict):body=json.dumps(body).encode();h['Content-Type']='application/json'
  try:
   with opener.open(urllib.request.Request(base+path,method=method,data=body,headers=h),timeout=180) as r:status=r.status;data=r.read();rh=r.headers
  except urllib.error.HTTPError as e:status=e.code;data=e.read();rh=e.headers
  assert status==expected,(method,path,status,data[:300])
  return json.loads(data) if 'application/json' in rh.get('Content-Type','') else data,rh
 call('/console/session',expected=401)
 call('/console/login','POST',{'username':c['username'],'password':c['password']},expected=403,origin=False)
 login,headers=call('/console/login','POST',{'username':c['username'],'password':c['password']});csrf=login['csrfToken']
 cookie=headers.get('Set-Cookie','');assert 'Secure' in cookie and 'HttpOnly' in cookie and 'SameSite=Strict' in cookie
 me,_=call('/console/session');assert me['username']==c['username'];assert me['csrfToken']==csrf
 call('/console/api/admin/accounts','POST',{},expected=403,protect=False)
 call('/console/api/admin/credentials',expected=403)
 themes,_=call('/console/api/admin/themes');assert 'themes' in themes
 doc,_=call('/console/api/admin/themes/integration-tone-demo/versions/1');sha=doc['manifest']['files'][0]['sha256']
 data,h=call('/console/api/uploads/'+sha+'/content',expected=206,headers={'Range':'bytes=0-15'});assert len(data)==16
 theme='console-test-'+uuid.uuid4().hex[:10];doc['scene']['id']=theme;doc['manifest']['theme_id']=theme
 draft={k:doc[k] for k in ['scene','manifest','license']};endpoint='/console/api/admin/themes/'+theme+'/versions/1'
 call(endpoint,'PUT',draft,expected=201);result,_=call(endpoint+'/validate','POST',{'revision':1});assert result['valid']
 draft['revision']=1;draft['scene']['description']='A concurrent, unseen fixture revision'
 call(endpoint,'PUT',draft,expected=201);result,_=call(endpoint+'/validate','POST',{'revision':2});assert result['valid']
 call(endpoint+'/publish','POST',{'revision':1},expected=409)
 call(endpoint+'/publish','POST',{},expected=400)
 call(endpoint+'/publish','POST',{'revision':2});call('/console/api/admin/themes/'+theme+'/unpublish','POST',{})
 name='console-test-'+uuid.uuid4().hex[:10]
 a,_=call('/console/api/admin/accounts','POST',{'username':name,'password':uuid.uuid4().hex},expected=201)
 call('/console/api/admin/accounts/'+str(a['id']),'PUT',{'enabled':False})
 audit,_=call('/console/api/admin/audit');assert 'events' in audit
 html,_=call('/');assert b'NaturalStreaming' in html and b'console' in html
 call('/console/logout','POST',{});call('/console/session',expected=401)
 print('PASS console login, secure cookie, session, CSRF, scoped routes, range preview, account fixture, audit, logout')

if __name__=='__main__':main()
