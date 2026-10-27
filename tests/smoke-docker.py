#!/usr/bin/env python3
"""ARM64 Docker + real MinIO integration test, with counted S3 data GETs.
Creates only isolated local containers/bucket. No production credentials are read.
"""
import concurrent.futures
import http.client
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import boto3
from botocore.config import Config

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'trino-paimon-iceberg:0.4.0-native-arm64'
MINIO_IMAGE = os.environ.get('MINIO_TEST_IMAGE', 'minio/minio:RELEASE.2025-04-22T22-12-26Z')
WORK = Path(tempfile.mkdtemp(prefix='paimon-native-smoke-'))
WORK.chmod(0o777)
NAME = 'native-cache-' + WORK.name
containers = []
report = {'image': IMAGE, 'cases': []}
counts = {'data_gets': 0, 'data_bytes': 0}
lock = threading.Lock()

def run(args, **kw):
    return subprocess.run(args, check=True, **kw)

def docker(*args, **kw):
    return run(['docker', *args], **kw)

class Proxy(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *args): pass
    def handle_request(self):
        conn = http.client.HTTPConnection('127.0.0.1', 19000, timeout=60)
        try:
            conn.request(self.command, self.path, headers=dict(self.headers))
            response = conn.getresponse()
            data = response.read()
            path = urllib.parse.urlsplit(self.path).path
            if self.command == 'GET' and response.status in (200, 206) and '/bucket-' in path:
                with lock:
                    counts['data_gets'] += 1
                    counts['data_bytes'] += len(data)
            self.send_response(response.status)
            for k, v in response.getheaders():
                if k.lower() not in ('transfer-encoding', 'content-length', 'connection'):
                    self.send_header(k, v)
            # HEAD advertises the remote object length but returns no body.
            self.send_header('Content-Length', response.getheader('Content-Length', '0') if self.command == 'HEAD' else str(len(data)))
            self.end_headers()
            if self.command != 'HEAD': self.wfile.write(data)
        finally: conn.close()
    do_GET = handle_request
    do_HEAD = handle_request

proxy = http.server.ThreadingHTTPServer(('127.0.0.1', 19001), Proxy)
proxy.daemon_threads = True
thread = threading.Thread(target=proxy.serve_forever, daemon=True)
thread.start()

s3 = boto3.client('s3', endpoint_url='http://127.0.0.1:19000',
                  aws_access_key_id='cachetest', aws_secret_access_key='cachetest-secret',
                  region_name='us-east-1', config=Config(s3={'addressing_style': 'path'},
                  request_checksum_calculation='when_required'))

def fixture(update=False):
    cmd = ['run', '--rm', '--network', 'none', '--entrypoint', '/opt/java/bin/java',
           '-v', str(WORK) + ':/fixture', '-v', str(ROOT/'vendor/target' if (ROOT/'vendor/target/paimon-trino-484-2.1.0-tests.jar').exists() else ROOT/'media') + ':/test-jars:ro', IMAGE,
           '-cp', '/opt/trino/plugin/paimon/*:/test-jars/paimon-trino-484-2.1.0-tests.jar',
           'org.apache.paimon.trino.NativeCacheFixture', '/fixture/warehouse']
    if update: cmd.append('update')
    docker(*cmd)
    for p in (WORK/'warehouse').rglob('*'):
        if p.is_file(): s3.upload_file(str(p), 'native-cache-test', 'warehouse/' + str(p.relative_to(WORK/'warehouse')))

def query(sql):
    return docker('exec', NAME, '/opt/java/bin/java', '-jar', '/opt/trino-cli.jar',
                  '--server', 'http://127.0.0.1:18089', '--user', 'smoke',
                  '--output-format', 'CSV', '--execute', sql,
                  capture_output=True, text=True, timeout=120).stdout.strip()

def start(enabled):
    etc = WORK/'etc'
    if etc.exists(): shutil.rmtree(etc)
    shutil.copytree(ROOT/'conf/etc', etc)
    p = etc/'jvm.config'; p.write_text(p.read_text().replace('-Xms4G', '-Xms512M').replace('-Xmx64G', '-Xmx3G'))
    p = etc/'config.properties'; p.write_text(p.read_text().replace('32GB', '512MB').replace('16GB', '512MB').replace('8080', '18089'))
    (etc/'catalog/paimon.properties').write_text('''connector.name=paimon
warehouse=s3://native-cache-test/warehouse
fs.s3.enabled=true
s3.endpoint=http://127.0.0.1:19001
s3.region=us-east-1
s3.path-style-access=true
s3.aws-access-key=cachetest
s3.aws-secret-key=cachetest-secret
fs.cache.enabled=false
cache-enabled=false
local-cache.enabled=''' + str(enabled).lower() + '''
local-cache.dir=/data/cache/paimon-native
local-cache.max-size=64 mb
local-cache.block-size=64 kb
local-cache.whitelist=meta,global-index,data,bucket-index,file-index
''')
    (etc/'catalog/iceberg.properties').write_text('connector.name=iceberg\niceberg.catalog.type=hive_metastore\nhive.metastore.uri=thrift://127.0.0.1:19083\nfs.s3.enabled=true\nfs.cache.enabled=false\n')
    docker('run', '-d', '--name', NAME, '--memory', '6g', '--network', 'host',
           '-v', str(etc) + ':/etc/trino:ro', '-v', str(WORK/'cache') + ':/data/cache', IMAGE)
    containers.append(NAME)
    for _ in range(120):
        try:
            out = query('SELECT version(); SHOW CATALOGS; SHOW SCHEMAS FROM paimon;')
            assert '484' in out and 'paimon' in out and 'iceberg' in out and 'cachetest' in out
            break
        except (subprocess.CalledProcessError, AssertionError):
            state = docker('inspect', '-f', '{{.State.Running}}', NAME, capture_output=True, text=True).stdout.strip()
            if state != 'true': raise RuntimeError('Trino stopped during readiness')
            time.sleep(1)
    else: raise RuntimeError('Trino readiness timed out')

def measure(table, phase):
    before = counts.copy()
    result = query(f'SELECT count(*), sum(id), sum(length(payload)) FROM paimon.cachetest.{table}')
    after = counts.copy()
    item = dict(table=table, phase=phase, result=result,
                **{k: after[k]-before[k] for k in before})
    report['cases'].append(item)
    print(json.dumps(item), flush=True)
    return item

def stop():
    docker('stop', '-t', '30', NAME)
    logs = docker('logs', NAME, capture_output=True, text=True)
    (WORK/f'trino-{len(report["cases"])}.log').write_text(logs.stdout + logs.stderr)
    docker('rm', NAME); containers.remove(NAME)

try:
    docker('run', '-d', '--name', NAME+'-minio', '--network', 'host',
           '-e', 'MINIO_ROOT_USER=cachetest', '-e', 'MINIO_ROOT_PASSWORD=cachetest-secret',
           MINIO_IMAGE, 'server', '/data', '--address', ':19000', '--console-address', ':19002')
    containers.append(NAME+'-minio')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(60):
        try:
            with opener.open('http://127.0.0.1:19000/minio/health/live', timeout=1): break
        except Exception: time.sleep(1)
    else: raise RuntimeError('MinIO readiness timed out')
    s3.create_bucket(Bucket='native-cache-test')
    fixture()
    (WORK/'cache').mkdir(); (WORK/'cache').chmod(0o777)
    tables = ['orc_scan', 'parquet_scan', 'pk_merge']
    start(False)
    baseline = {}
    for table in tables:
        first = measure(table, 'disabled-1'); repeat = measure(table, 'disabled-2')
        assert first['result'] == repeat['result'] and repeat['data_gets'] > 0
        assert first['result'].startswith('"2000","1999000",'), first
        baseline[table] = first['result']
    stop()
    start(True)
    for table in tables:
        cold = measure(table, 'native-cold'); hot = measure(table, 'native-hot')
        assert cold['result'] == hot['result'] == baseline[table]
        assert cold['data_gets'] > 0 and hot['data_gets'] < cold['data_gets'], (cold, hot)
        assert hot['data_bytes'] < cold['data_bytes'], (cold, hot)
    assert any(p.is_file() for p in (WORK/'cache').rglob('*'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: query('SELECT sum(id) FROM paimon.cachetest.pk_merge'), range(8)))
    assert all(x == '"1999000"' for x in results), results
    fixture(update=True)
    for table in tables:
        changed = measure(table, 'new-snapshot')
        expected_count, expected_sum = (2009, 2019044) if table == 'pk_merge' else (2010, 2019045)
        assert changed['result'].startswith(f'"{expected_count}","{expected_sum}",'), changed
    stop()
    start(True)
    for table in tables:
        resumed = measure(table, 'restart-hot')
        # New snapshot was read before restart; persisted native blocks must be reused.
        assert resumed['data_gets'] == 0, resumed
    report['status'] = 'passed'
finally:
    proxy.shutdown(); proxy.server_close()
    out = ROOT/'dist'; out.mkdir(exist_ok=True)
    report['status'] = report.get('status', 'failed')
    (out/'native-cache-validation.json').write_text(json.dumps(report, indent=2)+'\n')
    for name in reversed(containers):
        subprocess.run(['docker', 'logs', name], check=False)
        subprocess.run(['docker', 'rm', '-f', name], check=False)
    print('Integration work directory:', WORK, flush=True)
