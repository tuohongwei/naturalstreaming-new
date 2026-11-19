"""Root-only configuration of NaturalStreaming console. Secret JSON arrives on stdin.
Preserves existing admin environment mode/owner and creates a private recovery copy.
"""
import os,sys,json,stat,time,hashlib
from pathlib import Path

def main():
 data=json.load(sys.stdin);p=Path('/etc/naturalstreaming/admin.env');parent=p.parent
 for path in [p,parent,*parent.parents]:
  if path.is_symlink():raise RuntimeError('Unexpected symlink '+str(path))
 s=p.stat();assert stat.S_ISREG(s.st_mode) and s.st_uid==0 and stat.S_IMODE(s.st_mode)==0o600
 parent_stat=parent.stat();assert parent.is_dir() and parent_stat.st_uid==0 and stat.S_IMODE(parent_stat.st_mode)==0o700
 assert data['username']=='admin' and len(data['password'])>=12 and len(data['serviceToken'])>=32
 old=p.read_bytes();stamp=str(time.time_ns());backup=parent/('admin.env.before-console-'+stamp)
 with os.fdopen(os.open(backup,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'wb') as f:f.write(old)
 env=dict(line.split('=',1) for line in old.decode().splitlines() if line and '=' in line)
 env.update(CONSOLE_SERVICE_TOKEN=data['serviceToken'],CONSOLE_ORIGIN='https://admin.naturalstreaming.net',CONSOLE_BOOTSTRAP_USER=data['username'],CONSOLE_BOOTSTRAP_PASSWORD=data['password'])
 tmp=parent/('admin.env.console-'+stamp)
 with os.fdopen(os.open(tmp,os.O_CREAT|os.O_EXCL|os.O_WRONLY,stat.S_IMODE(s.st_mode)),'w') as f:f.write(''.join(k+'='+str(v)+'\n' for k,v in env.items()))
 assert tmp.stat().st_uid==s.st_uid and stat.S_IMODE(tmp.stat().st_mode)==stat.S_IMODE(s.st_mode)
 os.replace(tmp,p)
 print('Updated only admin.env; original owner/mode retained; recovery copy:',backup)

if __name__=='__main__':main()
