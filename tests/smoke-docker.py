#!/usr/bin/env python3
import pathlib,shutil,subprocess,tempfile,time
root=pathlib.Path(__file__).resolve().parents[1]
work=pathlib.Path(tempfile.mkdtemp(prefix='trino-docker-smoke-'));name='ai-trino-smoke-'+work.name
shutil.copytree(root/'conf/etc',work/'etc')
p=work/'etc/jvm.config';p.write_text(p.read_text().replace('-Xms4G','-Xms1G').replace('-Xmx64G','-Xmx4G'))
p=work/'etc/config.properties';p.write_text(p.read_text().replace('32GB','1GB').replace('16GB','1GB'))
(work/'etc/catalog/paimon.properties').write_text('connector.name=paimon\nwarehouse=local:///data/trino/local/warehouse\nfs.local.enabled=true\nlocal.location=/data/trino/local\nfs.cache.enabled=true\n')
(work/'etc/catalog/iceberg.properties').write_text('connector.name=iceberg\niceberg.catalog.type=hive_metastore\nhive.metastore.uri=${ENV:HIVE_METASTORE_URI}\nfs.local.enabled=true\nlocal.location=/data/trino/local\nfs.cache.enabled=true\n')
(work/'etc/cache-manager-alluxio.properties').write_text('cache-manager.name=alluxio\nfs.cache.directories=/data/cache/paimon\nfs.cache.max-sizes=1GB\nfs.cache.ttl=1d\nfs.cache.page-size=1MB\n')
def run(args,**kwargs):return subprocess.run(args,**kwargs)
try:
 run(['docker','run','-d','--name',name,'--memory','8g','-e','HIVE_METASTORE_URI=thrift://127.0.0.1:9083','-v',str(work/'etc')+':/etc/trino:ro','trino-paimon-iceberg:0.3.0-arm64'],check=True)
 for _ in range(120):
  r=run(['docker','exec',name,'/opt/java/bin/java','-jar','/opt/trino-cli.jar','--server','http://127.0.0.1:8080','--user','smoke','--execute','SELECT version(); SHOW CATALOGS; SHOW SCHEMAS FROM paimon;'],capture_output=True,text=True)
  if r.returncode==0 and '484' in r.stdout and 'paimon' in r.stdout and 'iceberg' in r.stdout:print(r.stdout);break
  state=run(['docker','inspect','-f','{{.State.Running}}',name],capture_output=True,text=True)
  if state.stdout.strip()!='true':raise RuntimeError('Container stopped')
  time.sleep(2)
 else:raise RuntimeError('SQL readiness failed: '+r.stdout+r.stderr)
finally:
 run(['docker','logs',name]);run(['docker','rm','-f',name])
