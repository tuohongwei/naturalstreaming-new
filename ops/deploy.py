"""Deploy only NaturalStreaming JARs. Requires existing provisioned project.
Usage: python ops/deploy.py --key ../keys/AiDevKey.pem --host ec2-user@13.214.63.156
Build with ops/build.ps1 first. Releases and previous link are preserved.
"""
import argparse,subprocess,hashlib,json,time,re
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--key',required=True);p.add_argument('--host',default='ec2-user@13.214.63.156');a=p.parse_args()
 root=Path(__file__).resolve().parent.parent;release=str(time.time_ns());stage='/tmp/naturalstreaming-release-'+release
 ssh=['ssh','-i',str(Path(a.key).resolve()),'-o','BatchMode=yes',a.host]
 def remote(cmd):return subprocess.run([*ssh,cmd],check=True)
 def copy(files,dest):subprocess.run(['scp','-q','-i',str(Path(a.key).resolve()),'-o','BatchMode=yes',*[str(x) for x in files],a.host+':'+dest],check=True)
 roles=['api','admin','files','migrations'];files=[root/'backend'/r/'target'/f'naturalstreaming-{r}.jar' for r in roles]
 for file in files:
  if not file.is_file():raise RuntimeError('Missing built JAR '+str(file))
 remote('mkdir -m 700 '+stage);copy(files,stage+'/');copy([root/'ops/server.py'],stage+'/server.py')
 manifest={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in files}
 # Arguments are fixed literals or numeric release IDs; no credentials are interpolated.
 script=f'''import pathlib,hashlib,json,os
import subprocess
base=pathlib.Path('/srv/naturalstreaming');stage=pathlib.Path('{stage}');target=base/'releases'/'{release}'
assert base.is_dir() and not base.is_symlink()
baseline=base/'backups'/'baseline-{release}.json'
subprocess.run(['python3',str(stage/'server.py'),'capture',str(baseline)],check=True)
manifest=json.loads({json.dumps(json.dumps(manifest))})
for name,want in manifest.items():
 p=stage/name
 assert p.is_file() and not p.is_symlink() and hashlib.sha256(p.read_bytes()).hexdigest()==want
target.mkdir()
for name in manifest:(target/name).write_bytes((stage/name).read_bytes())
(target/'checksums.json').write_text(json.dumps(manifest,indent=2))
current=base/'current'
assert current.is_symlink() and current.resolve().is_relative_to(base/'releases')
previous=os.readlink(current)
(target/'previous-release.txt').write_text(previous)
pending=base/'current.next'
assert not pending.exists() and not pending.is_symlink()
pending.symlink_to('releases/{release}');pending.replace(current)
try:
 subprocess.run(['python3',str(stage/'server.py'),'start'],check=True)
except subprocess.CalledProcessError:
 rollback=base/'rollback.next'
 assert not rollback.exists() and not rollback.is_symlink()
 rollback.symlink_to(previous);rollback.replace(current)
 for role in ['api','admin','files']:subprocess.run(['systemctl','restart','naturalstreaming-'+role],check=True)
 raise
subprocess.run(['python3',str(stage/'server.py'),'verify',str(baseline)],check=True)
'''
 local=root/'.local'/'install-release.py';local.parent.mkdir(exist_ok=True);local.write_text(script)
 copy([local],stage+'/install.py')
 remote('sudo flock -x /srv/naturalstreaming/deploy.lock python3 '+stage+'/install.py')
 print('Deployed verified release',release)

if __name__=='__main__':main()
