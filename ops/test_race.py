"""Deterministic MySQL snapshot regression, restricted to a new regression theme.
Requires owner NS_CREDENTIALS_FILE and --key for development server read/fixture writes.
"""
import argparse,concurrent.futures,json,os,subprocess,time,uuid
from pathlib import Path
from upload_theme import request

def main():
 p=argparse.ArgumentParser();p.add_argument('--key',required=True);a=p.parse_args()
 c=json.loads(Path(os.environ['NS_CREDENTIALS_FILE']).read_text());token=c['publisherToken']
 theme='snapshot-'+uuid.uuid4().hex[:12]
 demo=request(c['admin'],'/v1/admin/themes/integration-tone-demo/versions/1','GET',None,token)
 scene=demo['scene'];manifest=demo['manifest'];scene['id']=theme;manifest['theme_id']=theme
 draft={'scene':scene,'manifest':manifest,'license':demo['license']};endpoint=f'/v1/admin/themes/{theme}/versions/1'
 request(c['admin'],endpoint,'PUT',draft,token)
 ssh=['ssh','-i',str(Path(a.key).resolve()),'-o','BatchMode=yes','ec2-user@13.214.63.156']
 sqlcmd='sudo mysql --defaults-extra-file=/etc/aichef/mysql-migrate.cnf --batch --skip-column-names --unbuffered'
 def sql(text):return subprocess.run([*ssh,sqlcmd],input=text,text=True,capture_output=True,check=True).stdout
 def interleave(action,mutation):
  proc=subprocess.Popen([*ssh,sqlcmd],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
  try:
   proc.stdin.write("USE naturalstreaming; START TRANSACTION; SELECT revision FROM catalog_clock WHERE id=1 FOR UPDATE; SELECT 'LOCKED';\n");proc.stdin.flush()
   while proc.stdout.readline().strip()!='LOCKED':
    if proc.poll() is not None:raise RuntimeError('Lock session exited')
   with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
    pending=pool.submit(request,c['admin'],endpoint+'/'+action,'POST',{},token)
    for _ in range(40):
     waiting=sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='naturalstreaming' AND l.OBJECT_NAME='catalog_clock';")
     if int(waiting.strip())>0:break
     time.sleep(0.1)
    else:raise RuntimeError('No observed blocked clock transaction')
    proc.stdin.write(mutation+"; COMMIT; SELECT 'DONE';\n");proc.stdin.flush()
    result=None;failure=None
    try:result=pending.result(timeout=30)
    except RuntimeError as e:failure=str(e)
    return result,failure
  finally:
   proc.stdin.close();proc.wait(timeout=10)
 invalid=f"UPDATE theme_version SET scene_json=JSON_REMOVE(scene_json,'$.tracks[0].description'),revision=revision+1,validated_revision=NULL WHERE theme_id='{theme}' AND version='1'"
 result,failure=interleave('validate',invalid)
 assert failure is None and result['valid'] is False,('stale validation approved unseen revision',result,failure)
 print('PASS validation sees current content after clock wait')
 draft['revision']=2;request(c['admin'],endpoint,'PUT',draft,token);valid=request(c['admin'],endpoint+'/validate','POST',{},token);assert valid['valid']
 result,failure=interleave('publish',invalid)
 assert failure and 'HTTP 409:' in failure,('stale publication accepted edited revision',result,failure)
 print('PASS publication rejects unvalidated content after clock wait')

if __name__=='__main__':main()
