"""Run as root on the development server. Only NaturalStreaming resources are managed.
Usage: server.py provision|start|http|https|verify
Never prints runtime secrets. All database mutations target naturalstreaming / ns_* only.
"""
import sys,os,json,secrets,subprocess,hashlib,time,urllib.request,tarfile,io,socket
from pathlib import Path

BASE=Path('/srv/naturalstreaming');CONF=Path('/etc/naturalstreaming');NG=Path('/etc/nginx/conf.d/naturalstreaming.conf')
HOSTS=['naturalstreaming.net','www.naturalstreaming.net','api.naturalstreaming.net','admin-api.naturalstreaming.net','admin.naturalstreaming.net','media.naturalstreaming.net']
def run(*a,**kw):return subprocess.run(a,check=True,**kw)
def secure(path,content):
 with os.fdopen(os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:f.write(content)
def no_link(path):
 for p in [path,*path.parents]:
  if p.is_symlink():raise RuntimeError('Unexpected symlink '+str(p))
def mysql(sql):return run('mysql','--defaults-extra-file=/etc/aichef/mysql-migrate.cnf','-N',input=sql,text=True,capture_output=True).stdout
def hashes():return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path('/etc/nginx/conf.d').glob('*.conf') if p!=NG}
def baseline():
 for p in [BASE,CONF,NG]:no_link(p)
 for p in [BASE.parent,CONF.parent,NG.parent,Path('/etc/aichef/mysql-migrate.cnf')]:
  s=p.stat();print('Inspected',str(p),'dir' if p.is_dir() else 'file',oct(s.st_mode&0o777),s.st_uid,s.st_gid)
 units=run('systemctl','list-units','--type=service','--state=running','--no-legend','--plain',capture_output=True,text=True).stdout
 return {'nginx':hashes(),'units':{u:run('systemctl','show',u,'-p','MainPID','--value',capture_output=True,text=True).stdout.strip() for u in (x.split()[0] for x in units.splitlines()) if u.startswith(('aichef-','saytavi-','crm-','videolab','thaivisa'))}}
def provision():
 before=baseline()
 if CONF.exists() or BASE.exists():raise RuntimeError('Project already exists; refuse reprovision')
 for port in [8190,8191,8192]:
  with socket.socket() as s:
   if s.connect_ex(('127.0.0.1',port))==0:raise RuntimeError('Port occupied')
 if mysql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='naturalstreaming'; SELECT User FROM mysql.user WHERE User IN ('ns_api','ns_admin','ns_files','ns_migrate');").strip():raise RuntimeError('Existing database identity')
 BASE.mkdir(mode=0o755);CONF.mkdir(mode=0o700);(BASE/'tools').mkdir();(BASE/'acme').mkdir();(BASE/'releases').mkdir();(BASE/'backups').mkdir()
 secure(CONF/'baseline.json',json.dumps(before))
 state={k:secrets.token_urlsafe(36) for k in ['api','admin','files','migrate','uploadToken','publisherToken','signingKey']}
 state.update(apiUrl='https://api.naturalstreaming.net',adminUrl='https://admin-api.naturalstreaming.net',mediaUrl='https://media.naturalstreaming.net')
 secure(CONF/'deployment.json',json.dumps(state))
 sql="CREATE DATABASE naturalstreaming CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
 for role in ['api','admin','files','migrate']:sql+=f"CREATE USER 'ns_{role}'@'localhost' IDENTIFIED BY '{state[role]}';\n"
 sql+="GRANT ALL ON naturalstreaming.* TO 'ns_migrate'@'localhost';\n"
 mysql(sql)
 for role,port in [('api',8190),('admin',8191),('files',8192),('migrate',0)]:
  env={'DB_URL':'jdbc:mysql://127.0.0.1:3306/naturalstreaming?connectionTimeZone=UTC','DB_USERNAME':'ns_'+role,'DB_PASSWORD':state[role]}
  if role!='migrate':env.update(PORT=port,NS_ROLE=role,SIGNING_KEY=state['signingKey'],MEDIA_URL=state['mediaUrl'],STORAGE_ROOT='/var/lib/naturalstreaming-files',FFPROBE=str(BASE/'tools/ffprobe'),FFMPEG=str(BASE/'tools/ffmpeg'))
  secure(CONF/(role+'.env'),''.join(k+'='+str(v)+'\n' for k,v in env.items()))
 print('Provisioned isolated schema, identities and private configuration')
def env(role):return dict(os.environ,**dict(line.split('=',1) for line in (CONF/(role+'.env')).read_text().splitlines()))
def tools():
 if (BASE/'tools/ffprobe').is_file():return
 url='https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz'
 with urllib.request.urlopen(url,timeout=120) as r:data=r.read()
 (BASE/'tools/source.json').write_text(json.dumps({'url':url,'sha256':hashlib.sha256(data).hexdigest()}))
 with tarfile.open(fileobj=io.BytesIO(data),mode='r:xz') as tar:
  for name in ['ffmpeg','ffprobe']:
   members=[m for m in tar.getmembers() if m.isfile() and Path(m.name).name==name]
   if len(members)!=1:raise RuntimeError('Invalid tool archive')
   member=members[0];member.name=name;tar.extract(member,BASE/'tools',filter='data')
def start():
 for p in [BASE,CONF,NG]:no_link(p)
 tools()
 run('/opt/java/jdk-26/bin/java','-jar',str(BASE/'current/naturalstreaming-migrations.jar'),env=env('migrate'))
 sql="GRANT SELECT,INSERT,UPDATE ON naturalstreaming.* TO 'ns_admin'@'localhost';\n"
 for table in ['account','login_session','entitlement','service_credential','theme','theme_version','media_file','catalog_clock','catalog_event']:
  for role in ['api','files']:sql+=f"GRANT SELECT ON naturalstreaming.{table} TO 'ns_{role}'@'localhost';\n"
 sql+="GRANT UPDATE ON naturalstreaming.account TO 'ns_api'@'localhost'; GRANT INSERT,UPDATE ON naturalstreaming.login_session TO 'ns_api'@'localhost'; GRANT INSERT ON naturalstreaming.media_file TO 'ns_files'@'localhost';\n"
 state=json.loads((CONF/'deployment.json').read_text())
 for name,scopes,k in [('frontend-uploader','upload','uploadToken'),('owner-publisher','upload,publish,accounts,credentials','publisherToken')]:
  h=hashlib.sha256(state[k].encode()).hexdigest();sql+=f"INSERT IGNORE INTO naturalstreaming.service_credential(id,token_hash,scopes) VALUES('{name}','{h}','{scopes}');\n"
 mysql(sql)
 for role in ['api','admin','files']:
  unit=Path('/etc/systemd/system/naturalstreaming-'+role+'.service');no_link(unit)
  if unit.exists() and 'Description=NaturalStreaming ' not in unit.read_text():raise RuntimeError('Unrecognized unit')
  body=f'''[Unit]
Description=NaturalStreaming {role}
After=network-online.target mysqld.service
Wants=network-online.target
[Service]
DynamicUser=true
WorkingDirectory=/srv/naturalstreaming
EnvironmentFile=/etc/naturalstreaming/{role}.env
ExecStart=/opt/java/jdk-26/bin/java -Xms32m -Xmx256m -jar /srv/naturalstreaming/current/naturalstreaming-{role}.jar
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectControlGroups=true
LimitNOFILE=4096
'''
  if role=='files':body+='StateDirectory=naturalstreaming-files\nStateDirectoryMode=0700\n'
  body+='\n[Install]\nWantedBy=multi-user.target\n';unit.write_text(body)
 run('systemctl','daemon-reload')
 for role in ['api','admin','files']:run('systemctl','enable','naturalstreaming-'+role);run('systemctl','restart','naturalstreaming-'+role)
 for port in [8190,8191,8192]:
  for attempt in range(45):
   try:
    with urllib.request.urlopen(f'http://127.0.0.1:{port}/healthz',timeout=2) as r:assert r.status==200
    break
   except Exception:
    if attempt==44:raise
    time.sleep(1)
 print('Three isolated services healthy')
def nginx(https=False):
 no_link(NG);old=NG.read_bytes() if NG.exists() else None
 if old is not None and b'# NaturalStreaming only' not in old:raise RuntimeError('Unrecognized virtual host')
 if old:(BASE/'backups'/('nginx-'+str(time.time_ns())+'.conf')).write_bytes(old)
 acme='location ^~ /.well-known/acme-challenge/ { root /srv/naturalstreaming/acme; default_type text/plain; try_files $uri =404; }'
 body='# NaturalStreaming only\nlimit_req_zone $binary_remote_addr zone=ns_login:1m rate=10r/m;\n'
 body+='server { listen 80; server_name '+' '.join(HOSTS)+'; '+acme+' location / { '+('return 301 https://$host$request_uri;' if https else 'return 503;')+' } }\n'
 if https:
  cert='/etc/letsencrypt-naturalstreaming/live/naturalstreaming.net'
  for host,port in [('api.naturalstreaming.net',8190),('admin-api.naturalstreaming.net',8191),('media.naturalstreaming.net',8192)]:
   body+=f'''server {{ listen 443 ssl; server_name {host};
 ssl_certificate {cert}/fullchain.pem; ssl_certificate_key {cert}/privkey.pem;
 client_max_body_size 1m; client_body_timeout 120s;
 add_header X-Content-Type-Options nosniff always;
 location = /v1/auth/login {{ limit_req zone=ns_login burst=5 nodelay; limit_req_status 429; proxy_pass http://127.0.0.1:{port}; proxy_set_header Host $host; }}
 location ^~ /v1/uploads/ {{ client_max_body_size 128m; proxy_pass http://127.0.0.1:{port}; proxy_set_header Host $host; proxy_read_timeout 180s; proxy_send_timeout 180s; proxy_buffering off; access_log off; }}
 location / {{ proxy_pass http://127.0.0.1:{port}; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto https; proxy_read_timeout 180s; proxy_send_timeout 180s; proxy_buffering off; proxy_request_buffering on; access_log off; }}
}}
'''
  body+=f'''server {{ listen 443 ssl; server_name naturalstreaming.net www.naturalstreaming.net admin.naturalstreaming.net;
ssl_certificate {cert}/fullchain.pem; ssl_certificate_key {cert}/privkey.pem;
location / {{ default_type text/plain; return 200 "NaturalStreaming development backend. App and admin web pages are not published yet.\\n"; }}
}}
'''
 NG.write_text(body)
 try:run('nginx','-t')
 except Exception:
  if old is None:NG.unlink()
  else:NG.write_bytes(old)
  raise
 run('systemctl','reload','nginx')
def verify():
 before=json.loads((CONF/'baseline.json').read_text());assert before['nginx']==hashes(),'Unrelated nginx configuration changed'
 for unit,pid in before['units'].items():
  current=run('systemctl','show',unit,'-p','MainPID','--value',capture_output=True,text=True).stdout.strip()
  assert current==pid,(unit,'PID changed',pid,current)
  run('systemctl','is-active','--quiet',unit)
 print('Unrelated virtual hosts unchanged; existing application service PIDs unchanged and active')
if __name__=='__main__':
 {'provision':provision,'start':start,'http':lambda:nginx(False),'https':lambda:nginx(True),'verify':verify}[sys.argv[1]]()
