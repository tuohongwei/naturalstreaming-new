"""Frontend-only smoke: login, sync, fixed version, authorized download and cache check.
This login replaces the test account's old device session. Do not run while testing another device.
"""
import os,json,hashlib,urllib.request
from pathlib import Path
from upload_theme import request

def main():
 c=json.loads(Path(os.environ['NS_CREDENTIALS_FILE']).read_text(encoding='utf-8-sig'))
 session=request(c['api'],'/v1/auth/login','POST',{'username':c['testUsername'],'password':c['testPassword'],'deviceId':'codex-smoke-device'},'')
 token=session['token'];catalog=request(c['api'],'/v1/catalog/sync','GET',None,token)
 items=catalog['upserts'];assert items,'No published themes authorized'
 t=items[0];doc=request(c['api'],f"/v1/themes/{t['id']}/versions/{t['version']}",'GET',None,token)
 selected=doc['scene']['tracks'][0]['clips'];ticket=request(c['api'],'/v1/media/download-urls','POST',{'themeId':t['id'],'version':t['version'],'paths':selected},token)
 cache=Path(os.getenv('NS_CACHE_DIR','.local/frontend-cache'));cache.mkdir(parents=True,exist_ok=True);downloaded=0
 for file in ticket['files']:
  p=cache/file['sha256']
  if p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()==file['sha256']:continue
  with urllib.request.urlopen(urllib.request.Request(file['url'],headers={'Authorization':'Bearer '+token,'User-Agent':'NaturalStreaming/1.0 (Frontend smoke)'}),timeout=60) as r:data=r.read()
  assert len(data)==file['bytes'] and hashlib.sha256(data).hexdigest()==file['sha256']
  temp=p.with_suffix('.part');temp.write_bytes(data);temp.replace(p);downloaded+=1
 # From here, only local reads; demonstrates the downloader handoff, not Android playback.
 (cache/'configuration.json').write_text(json.dumps(doc,ensure_ascii=False),encoding='utf-8')
 for path in selected:
  f=next(x for x in doc['manifest']['files'] if x['path']==path);assert (cache/f['sha256']).is_file()
 print(json.dumps({'theme':t['id'],'tracks':len(doc['scene']['tracks']),'selectedClips':len(selected),'newFilesDownloaded':downloaded,'localFilesVerified':True},ensure_ascii=False))

if __name__=='__main__':main()
