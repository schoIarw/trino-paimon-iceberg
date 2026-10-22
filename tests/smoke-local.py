#!/usr/bin/env python3
"""Validate packaged Java code with host JDK 24; NOT an ARM64 Docker test."""
import argparse,json,os,shutil,subprocess,tempfile,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--rootfs',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);a=p.parse_args()
root=a.rootfs.resolve();jdk=a.jdk.resolve();work=Path(tempfile.mkdtemp(prefix='trino-smoke-'))
shutil.copytree(root/'etc/trino',work/'etc')
for n in ['data','tmp','warehouse','cache']:(work/n).mkdir()
f=work/'etc/jvm.config';f.write_text(f.read_text().replace('-Xms4G','-Xms512M').replace('-Xmx64G','-Xmx2G').replace('/data/trino/tmp',str(work/'tmp')))
f=work/'etc/config.properties';f.write_text(f.read_text().replace('32GB','512MB').replace('16GB','512MB').replace('8080','18081'))
(work/'etc/node.properties').write_text('node.environment=test\nnode.id=test\nnode.internal-address=127.0.0.1\nnode.data-dir='+str(work/'data')+'\n')
(work/'etc/catalog/paimon.properties').write_text('connector.name=paimon\nwarehouse='+(work/'warehouse').as_uri()+'\nfs.native-local.enabled=true\nlocal.location=/\nfs.cache.enabled=true\nfs.cache.directories='+str(work/'cache')+'\nfs.cache.max-sizes=1GB\n')
env=os.environ.copy();env['JAVA_HOME']=str(jdk);env['PATH']=str(jdk/'bin')+':'+env['PATH']
print('Logs:',work,flush=True)
with open(work/'server.log','w') as log:
 server=subprocess.Popen([str(root/'opt/trino/bin/launcher'),'--etc-dir='+str(work/'etc'),'run'],env=env,stdout=log,stderr=subprocess.STDOUT)
 try:
  op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
  for _ in range(60):
   if server.poll() is not None:raise RuntimeError('Server exited; inspect server.log')
   try:
    with op.open('http://127.0.0.1:18081/v1/info',timeout=1) as response:info=json.load(response)
    if not info['starting']:break
   except Exception:pass
   time.sleep(1)
  else:raise TimeoutError('Server not ready')
  print(info,flush=True)
  sql="SELECT version(); SHOW CATALOGS; SHOW SCHEMAS FROM paimon; SELECT table_name FROM jmx.information_schema.tables WHERE table_schema='current' AND table_name LIKE '%alluxio%'"
  result=subprocess.run([str(jdk/'bin/java'),'-jar',str(root/'opt/trino-cli.jar'),'--server','http://127.0.0.1:18081','--user','smoke','--execute',sql],env=env,capture_output=True,text=True,timeout=60)
  print(result.stdout,flush=True);print(result.stderr,flush=True)
  if result.returncode:raise RuntimeError('SQL failed')
  assert '476' in result.stdout and 'paimon' in result.stdout and 'alluxio' in result.stdout
  print('PASS: packaged Java code starts, SQL works, Paimon cache metrics exported; host architecture only.',flush=True)
 finally:
  server.terminate()
  try:server.wait(timeout=15)
  except subprocess.TimeoutExpired:server.kill();server.wait()
