"""Upload a theme folder or a zip containing multiple themes; never publishes.
NS_CREDENTIALS_FILE points to a private JSON file containing admin, media, uploadToken.
The unmodified supplied sample may be saved as a draft even when validation fails.
"""
import argparse,hashlib,json,os,urllib.request,urllib.error,zipfile
from pathlib import Path,PurePosixPath,PureWindowsPath

def safe_relative(value):
 if not isinstance(value,str) or not value or '\\' in value or PurePosixPath(value).is_absolute() or PureWindowsPath(value).drive or any(x in ('','.','..') for x in value.split('/')):raise ValueError('Unsafe relative path')
 return value

def read_directory_file(root,relative,limit):
 safe_relative(relative);root=Path(root).absolute()
 if root.is_symlink():raise ValueError('Symlink theme root')
 candidate=root/relative
 for p in [candidate,*candidate.parents]:
  if p==root:break
  if p.is_symlink():raise ValueError('Symlink in theme')
 resolved=candidate.resolve(strict=True)
 if not resolved.is_relative_to(root.resolve()) or not resolved.is_file() or resolved.stat().st_size>limit:raise ValueError('File outside theme or too large')
 with resolved.open('rb') as f:data=f.read(limit+1)
 if len(data)>limit:raise ValueError('File too large')
 return data

def read_zip_entry(z,name,limit):
 safe_relative(name);info=z.getinfo(name)
 if info.file_size>limit or ((info.external_attr>>16)&0o170000)==0o120000:raise ValueError('Oversized or linked ZIP entry')
 with z.open(info) as f:data=f.read(limit+1)
 if len(data)>limit:raise ValueError('ZIP entry too large')
 return data

def request(base,path,method,body,token):
 headers={'Authorization':'Bearer '+token,'User-Agent':'NaturalStreaming/1.0 (Content uploader)'}
 if isinstance(body,dict):body=json.dumps(body,ensure_ascii=False).encode();headers['Content-Type']='application/json'
 elif body is not None:headers['Content-Type']='application/octet-stream'
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=180) as r:return json.loads(r.read())
 except urllib.error.HTTPError as e:
  details=e.read().decode(errors='replace')
  raise RuntimeError(f'{method} {path}: HTTP {e.code}: {details}') from None

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('source');p.add_argument('--version',required=True);p.add_argument('--credentials',default=os.getenv('NS_CREDENTIALS_FILE'));p.add_argument('--license-file',help='JSON with source, license, reviewed');p.add_argument('--validate',action='store_true');a=p.parse_args()
 if not a.credentials:p.error('Set NS_CREDENTIALS_FILE or --credentials')
 c=json.loads(Path(a.credentials).read_text(encoding='utf-8-sig'));token=c['uploadToken'];source=Path(a.source).absolute()
 if source.is_symlink():raise ValueError('Symlink source')
 license=json.loads(Path(a.license_file).read_text(encoding='utf-8-sig')) if a.license_file else {'source':'Pending content review','license':'Pending content review','reviewed':False}
 z=None
 try:
  if source.is_file():
   z=zipfile.ZipFile(source);names=z.namelist();scenes=[n for n in names if n.endswith('/scene.json') and not n.startswith('__MACOSX/')]
   if 'scene.json' in names:scenes.append('scene.json')
   if len(names)!=len(set(names)):raise ValueError('Duplicate ZIP paths')
   read=lambda name,limit:read_zip_entry(z,name,limit)
  else:
   scenes=['scene.json'] if (source/'scene.json').is_file() else [x.relative_to(source).as_posix() for x in source.rglob('scene.json')]
   read=lambda name,limit:read_directory_file(source,name,limit)
  if not scenes:raise RuntimeError('No scene.json found')
  for name in scenes:
   prefix=name[:-10]
   if z is None:
    theme_root=source/prefix
    if any(p.is_symlink() for p in [theme_root,*theme_root.parents] if p.is_relative_to(source.absolute())):raise ValueError('Symlink theme root')
    theme_read=lambda path,limit:read_directory_file(theme_root,path,limit)
   else:theme_read=lambda path,limit:read(prefix+safe_relative(path),limit)
   scene=json.loads(theme_read('scene.json',500000));manifest=json.loads(theme_read('manifest.json',500000));theme=scene['id']
   for entry in manifest['files']:
    safe_relative(entry['path'])
    data=theme_read(entry['path'],134217728);sha=hashlib.sha256(data).hexdigest()
    if sha!=entry['sha256'] or len(data)!=entry['bytes']:raise RuntimeError('Local manifest mismatch: '+entry['path'])
    # PUT is idempotent by content hash; the service verifies all bytes and actual decode.
    result=request(c['media'],'/v1/uploads/'+sha,'PUT',data,token)
    print(theme,entry['path'],result['bytes'],'verified',flush=True)
   endpoint=f'/v1/admin/themes/{theme}/versions/{a.version}'
   draft={'scene':scene,'manifest':manifest,'license':license}
   try:old=request(c['admin'],endpoint,'GET',None,token)
   except RuntimeError as e:
    if 'HTTP 404:' not in str(e):raise
   else:draft['revision']=old['revision']
   result=request(c['admin'],endpoint,'PUT',draft,token);print(json.dumps(result,ensure_ascii=False))
   if a.validate:print(json.dumps(request(c['admin'],endpoint+'/validate','POST',{},token),ensure_ascii=False))
 finally:
  if z:z.close()

if __name__=='__main__':main()
