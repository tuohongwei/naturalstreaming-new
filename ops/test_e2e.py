"""Real HTTP/MySQL contract regression. Run against an isolated development instance.
Credentials are read from NS_CREDENTIALS_FILE and never printed.
"""
import os,json,urllib.request,urllib.error,hashlib,io,wave,math,struct,uuid,concurrent.futures
from pathlib import Path

def run():
 c=json.loads(Path(os.environ['NS_CREDENTIALS_FILE']).read_text(encoding='utf-8-sig'))
 api=c['api'];admin=c['admin'];media=c['media'];upload=c['uploadToken'];publisher=c['publisherToken']
 checks=[]
 def call(base,path,method='GET',body=None,token=None,expected=200,headers=None):
  h={'User-Agent':'NaturalStreaming/1.0 (Development integration)'};h.update(headers or {})
  if token:h['Authorization']='Bearer '+token
  if isinstance(body,(dict,list)):body=json.dumps(body).encode();h['Content-Type']='application/json'
  req=urllib.request.Request(base+path,data=body,method=method,headers=h)
  try:
   with urllib.request.urlopen(req,timeout=90) as r:status=r.status;raw=r.read();rh=r.headers
  except urllib.error.HTTPError as e:status=e.code;raw=e.read();rh=e.headers
  assert status==expected,(method,path,status,raw[:400])
  return json.loads(raw) if 'json' in rh.get('Content-Type','') else raw,rh
 def ok(s):checks.append(s);print('PASS',s,flush=True)
 call(api,'/healthz');ok('health')
 call(api,'/v1/auth/login','POST',{'username':None,'password':None,'deviceId':None},expected=400)
 call(api,'/v1/not-a-route',expected=404)
 cred_id='scope-'+uuid.uuid4().hex[:12]
 credential,_=call(admin,'/v1/admin/credentials','POST',{'id':cred_id,'scopes':['upload','upload','credentials']},publisher,201)
 call(admin,'/v1/admin/themes',token=credential['token'])
 call(admin,'/v1/admin/credentials/'+cred_id.upper(),'DELETE',token=credential['token'],expected=404)
 call(admin,'/v1/admin/credentials/'+cred_id,'DELETE',token=credential['token'],expected=409)
 call(admin,'/v1/admin/credentials/'+cred_id,'DELETE',token=publisher)
 theme='regression-'+uuid.uuid4().hex[:12];version='1'
 user='test-'+uuid.uuid4().hex[:12];password=uuid.uuid4().hex+'Aa9!'
 account,_=call(admin,'/v1/admin/accounts','POST',{'username':user,'password':password},publisher,201)
 account_id=account['id']
 call(admin,'/v1/admin/accounts','POST',{'username':user+'-blocked','password':password},upload,403);ok('upload cannot manage accounts')
 b=io.BytesIO()
 with wave.open(b,'wb') as w:
  w.setparams((1,2,16000,0,'NONE','not compressed'))
  w.writeframes(b''.join(struct.pack('<h',int(3000*math.sin(i*2*math.pi*440/16000))) for i in range(48000)))
 audio=b.getvalue();sha=hashlib.sha256(audio).hexdigest()
 call(media,'/v1/uploads/'+sha,'PUT',audio,upload,201,{'Content-Type':'application/octet-stream'})
 call(media,'/v1/uploads/'+sha,'PUT',b'broken',upload,422);ok('upload/hash/probe')
 scene={'id':theme,'schema_version':1,'name':'Regression fixture','tracks':[{'id':'1','label':'Test tone','description':'Synthetic validation fixture, not production content','clips':['media/a.wav','media/b.wav'],'gain':0.08,'overlap_seconds':1,'channels':1,'diffuse':False,'spatial_role':'point_source','map_position':[0,-1]}],'default_preset':'balanced','presets':[{'id':'balanced','label':'Balanced','gains':{'1':0.08},'transition_seconds':2,'animation':{'enabled':False}}]}
 manifest={'theme_id':theme,'schema_version':1,'files':[{'path':p,'sha256':sha,'bytes':len(audio),'channels':1,'codec':'pcm_s16le','container':'wav'} for p in ['media/a.wav','media/b.wav']]}
 draft={'scene':scene,'manifest':manifest,'license':{'source':'Generated synthetic test tone','license':'CC0','reviewed':True}}
 path=f'/v1/admin/themes/{theme}/versions/{version}'
 call(admin,path,'PUT',draft,upload,201)
 call(admin,path+'/publish','POST',{},publisher,409)
 call(admin,path+'/validate','POST',{},upload)
 call(admin,path+'/publish','POST',{},upload,403)
 call(admin,path+'/publish','POST',{},publisher)
 call(admin,path+'/publish','POST',{},publisher)
 call(admin,path,'PUT',draft,upload,409);ok('draft validation, publish role and immutability')
 invalid_path=f'/v1/admin/themes/{theme}/versions/invalid'
 bad=json.loads(json.dumps(draft));bad['scene']['tracks'][0]['clips'][0]='../outside.wav'
 call(admin,invalid_path,'PUT',bad,upload,201)
 validation,_=call(admin,invalid_path+'/validate','POST',{},upload);assert not validation['valid']
 call(admin,invalid_path+'/publish','POST',{},publisher,409)
 call(admin,invalid_path,'PUT',draft,upload,409)
 revised=dict(draft,revision=1);call(admin,invalid_path,'PUT',revised,upload,201)
 validation,_=call(admin,invalid_path+'/validate','POST',{},upload);assert validation['valid']
 changed=json.loads(json.dumps(draft));changed['revision']=2;changed['scene']['tracks'][0]['description']='Updated fixture description'
 call(admin,invalid_path,'PUT',changed,upload,201)
 call(admin,invalid_path+'/publish','POST',{},publisher,409)
 ok('bad references, draft revision and validation invalidation')
 for suffix,mutate in [('duration',lambda x:x['manifest']['files'][0].update({'duration':99})),('sample-rate',lambda x:x['manifest']['files'][0].update({'sample_rate':1})),('overlap',lambda x:x['scene']['tracks'][0].update({'overlap_seconds':2.5}))]:
  invalid=json.loads(json.dumps(draft));mutate(invalid);p=path.rsplit('/',1)[0]+'/'+suffix
  call(admin,p,'PUT',invalid,upload,201);result,_=call(admin,p+'/validate','POST',{},upload);assert not result['valid'],suffix
 ok('declared media metadata and overlap bounds')
 login=lambda device:call(api,'/v1/auth/login','POST',{'username':user,'password':password,'deviceId':device})[0]['token']
 first=login('device-alpha')
 call(api,f'/v1/themes/{theme}/versions/1',token=first,expected=403)
 call(admin,f'/v1/admin/accounts/{account_id}/themes/{theme}','PUT',{'allowed':True},publisher)
 snapshot,_=call(api,'/v1/catalog/sync',token=first)
 assert any(t['id']==theme for t in snapshot['upserts']);cursor=snapshot['cursor']
 second=login('device-bravo')
 old,_=call(api,'/v1/me',token=first,expected=401);assert old['error']['code']=='session_revoked'
 ok('theme authorization and device replacement')
 cfg,_=call(api,f'/v1/themes/{theme}/versions/1',token=second);assert cfg['scene']['tracks'][0]['clips']==['media/a.wav','media/b.wav']
 ticket,_=call(api,'/v1/media/download-urls','POST',{'themeId':theme,'version':'1','paths':['media/a.wav']},second)
 url=ticket['files'][0]['url']
 full,_=call('',url,token=second);assert full==audio
 part,headers=call('',url,token=second,expected=206,headers={'Range':'bytes=10-19'});assert part==audio[10:20] and headers['Content-Range']==f'bytes 10-19/{len(audio)}'
 tail,_=call('',url,token=second,expected=206,headers={'Range':'bytes=-7'});assert tail==audio[-7:]
 call('',url,'HEAD',token=second)
 call('',url,token=second,expected=416,headers={'Range':'bytes=9999999-'})
 call('',url,expected=401)
 third=login('device-charlie');call('',url,token=third,expected=403);call('',url,token=second,expected=401);ok('range, HEAD and revoked download tickets')
 call(admin,f'/v1/admin/themes/{theme}/unpublish','POST',{},publisher)
 changes,_=call(api,'/v1/catalog/sync?cursor='+urllib.parse.quote(cursor),token=third)
 assert any(t['id']==theme and t['reason']=='unpublished' for t in changes['removed'])
 call(api,f'/v1/themes/{theme}/versions/1',token=third)
 call(admin,path+'/publish','POST',{},publisher)
 call(admin,f'/v1/admin/themes/{theme}/rollback','POST',{'version':'invalid'},publisher,404)
 call(admin,f'/v1/admin/accounts/{account_id}/themes/{theme}','PUT',{'allowed':False},publisher)
 changes,_=call(api,'/v1/catalog/sync?cursor='+urllib.parse.quote(cursor),token=third)
 assert any(t['id']==theme and t['reason']=='revoked' for t in changes['removed'])
 me,_=call(api,'/v1/me',token=third);assert any(x['theme_id']==theme and not x['allowed'] for x in me['entitlements'])
 call(api,f'/v1/themes/{theme}/versions/1',token=third,expected=403);ok('unpublish vs entitlement revoke')
 call(api,'/v1/catalog/sync?cursor=bad',token=third,expected=409)
 call(api,'/v1/auth/logout','POST',{},third);call(api,'/v1/me',token=third,expected=401)
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:tokens=list(pool.map(login,['device-race-1','device-race-2']))
 statuses=[]
 for token in tokens:
  try:call(api,'/v1/me',token=token);statuses.append(200)
  except AssertionError:
   revoked,_=call(api,'/v1/me',token=token,expected=401);assert revoked['error']['code']=='session_revoked';statuses.append(401)
 assert sorted(statuses)==[200,401];ok('concurrent login leaves one device')
 active=tokens[statuses.index(200)]
 call(admin,f'/v1/admin/accounts/{account_id}','PUT',{'enabled':False},publisher)
 disabled,_=call(api,'/v1/me',token=active,expected=401);assert disabled['error']['code']=='session_revoked'
 ok('disabled account revokes active session')
 print(json.dumps({'passed':len(checks),'theme':theme,'accountId':account_id},ensure_ascii=False))

if __name__=='__main__':run()
