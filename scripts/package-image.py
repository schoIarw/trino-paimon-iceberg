#!/usr/bin/env python3
"""Create a Docker-load archive with pinned ARM64 base and verified artifacts."""
import argparse, copy, gzip, hashlib, io, json, shutil, subprocess, tarfile, tempfile, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE='ubuntu@sha256:722ea796ac2d57eeb3627c58a582fc1acc58be51faf815e1bce1682ae5c092f7'
ARTIFACTS={
 'jdk-arm64.tar.gz':'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.4_7.tar.gz',
 'trino-core.tar.gz':'https://github.com/trinodb/trino/releases/download/484/trino-server-core-484.tar.gz',
 'trino-jmx.zip':'https://github.com/trinodb/trino/releases/download/484/trino-jmx-484.zip',
 'trino-cli.jar':'https://github.com/trinodb/trino/releases/download/484/trino-cli-484',
 'trino-blob-cache-alluxio.zip':'https://github.com/trinodb/trino/releases/download/484/trino-blob-cache-alluxio-484.zip',
 'trino-iceberg.zip':'https://github.com/trinodb/trino/releases/download/484/trino-iceberg-484.zip'}

def digest(path):
    with open(path,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def untar(path,dest,strip=0):
    dest.mkdir(parents=True,exist_ok=True)
    with tarfile.open(path) as t:
        for original in t:
            m=copy.copy(original); parts=Path(m.name).parts[strip:]
            if not parts:continue
            m.name=str(Path(*parts))
            if m.islnk() and strip:m.linkname=str(Path(*Path(m.linkname).parts[strip:]))
            t.extract(m,dest,filter='data')

def add_bytes(t,name,data):
    m=tarfile.TarInfo(name);m.size=len(data);m.mode=0o644;t.addfile(m,io.BytesIO(data))

def main():
    p=argparse.ArgumentParser();p.add_argument('--downloads',type=Path,default=ROOT/'build/downloads');p.add_argument('--crane',default='crane');p.add_argument('--record-lock',action='store_true');a=p.parse_args()
    d=a.downloads.resolve();d.mkdir(parents=True,exist_ok=True)
    lock_path=ROOT/'artifacts.lock.json'
    for name,url in ARTIFACTS.items():
        if not (d/name).exists():subprocess.run(['curl','-fL','--retry','3',url,'-o',str(d/name)],check=True)
    observed={name:{'url':url,'sha256':digest(d/name)} for name,url in ARTIFACTS.items()}
    if a.record_lock:
        if lock_path.exists():raise RuntimeError('Lock exists; refusing replacement')
        lock_path.write_text(json.dumps({'base_image':BASE,'artifacts':observed},indent=2)+'\n')
    lock=json.loads(lock_path.read_text())
    if lock!={'base_image':BASE,'artifacts':observed}:raise RuntimeError('Artifact lock mismatch')
    base=d/'ubuntu-arm64.tar'
    if not base.exists():subprocess.run([a.crane,'pull','--platform','linux/arm64',BASE,str(base)],check=True)
    plugin=ROOT/'vendor/target/paimon-trino-484-2.1.0-plugin.tar.gz'
    if not plugin.exists():raise RuntimeError('Build connector first')
    (ROOT/'build').mkdir(exist_ok=True)
    work=Path(tempfile.mkdtemp(prefix='image-',dir=ROOT/'build'));root=work/'rootfs'
    untar(d/'jdk-arm64.tar.gz',root/'opt/java',1)
    untar(d/'trino-core.tar.gz',root/'opt/trino',1)
    untar(plugin,root/'opt/trino/plugin')
    # Verify that the plugin distribution contains exactly the freshly built jar.
    jar='paimon-trino-484-2.1.0.jar'
    if digest(root/'opt/trino/plugin/paimon'/jar)!=digest(ROOT/'vendor/target'/jar):raise RuntimeError('Stale connector jar in package')
    with zipfile.ZipFile(d/'trino-jmx.zip') as z:
        if any('..' in Path(n).parts or n.startswith('/') for n in z.namelist()):raise RuntimeError('Unsafe ZIP')
        z.extractall(work/'jmx')
    shutil.copytree(work/'jmx/trino-jmx-484',root/'opt/trino/plugin/jmx')
    with zipfile.ZipFile(d/'trino-blob-cache-alluxio.zip') as z:
        if any('..' in Path(n).parts or n.startswith('/') for n in z.namelist()):raise RuntimeError('Unsafe cache ZIP')
        z.extractall(work/'alluxio')
    cache_dir=root/'opt/trino/plugin/blob-cache-alluxio'
    if not cache_dir.exists():
        shutil.copytree(work/'alluxio/trino-blob-cache-alluxio-484',cache_dir)
    if not (cache_dir/'trino-blob-cache-alluxio-484.jar').is_file():
        raise RuntimeError('Alluxio cache plugin JAR missing')
    with zipfile.ZipFile(d/'trino-iceberg.zip') as z:
        if any('..' in Path(n).parts or n.startswith('/') for n in z.namelist()):raise RuntimeError('Unsafe Iceberg ZIP')
        z.extractall(work/'iceberg')
    shutil.copytree(work/'iceberg/trino-iceberg-484',root/'opt/trino/plugin/iceberg')
    if not (root/'opt/trino/plugin/iceberg/trino-iceberg-484.jar').is_file():
        raise RuntimeError('Iceberg connector JAR missing from official plugin')
    shutil.copy2(d/'trino-cli.jar',root/'opt/trino-cli.jar')
    shutil.copytree(ROOT/'conf/etc',root/'etc/trino')
    (root/'opt/bin').mkdir(parents=True)
    for name in ['entrypoint.sh','healthcheck.sh']:
        shutil.copy2(ROOT/'docker'/name,root/'opt/bin'/name);(root/'opt/bin'/name).chmod(0o755)
    for name in ['data/trino/tmp','data/trino/local/warehouse','data/cache/paimon']:(root/name).mkdir(parents=True)
    (root/'opt/build-info.json').write_text(json.dumps({'trino':'484','paimon':'2.1.0','iceberg_plugin':'484','java':'25.0.4+7','patch':'ai-cache-0.3.0','connector_sha256':digest(plugin),'inputs':lock},indent=2)+'\n')
    layer=work/'layer.tar'
    def normal(m):m.uid=m.gid=1000;m.uname=m.gname='';m.mtime=0;return m
    with tarfile.open(layer,'w') as t:
        for child in sorted(root.iterdir()):t.add(child,arcname=child.name,filter=normal)
    with tarfile.open(base) as t:
        manifest=json.load(t.extractfile('manifest.json'))
        if len(manifest)!=1:raise RuntimeError('Expected one base image')
        old=manifest[0];config=json.load(t.extractfile(old['Config']))
        if config['architecture']!='arm64' or config['os']!='linux':raise RuntimeError('Wrong architecture')
        for name,want in zip(old['Layers'],config['rootfs']['diff_ids'],strict=True):
            f=t.extractfile(name);magic=f.read(2);f.seek(0)
            if magic==b'\x1f\x8b':f=gzip.GzipFile(fileobj=f)
            if 'sha256:'+hashlib.file_digest(f,'sha256').hexdigest()!=want:raise RuntimeError('Base layer hash mismatch')
    layer_hash=digest(layer);config['rootfs']['diff_ids'].append('sha256:'+layer_hash)
    compressed=work/'layer.tar.gz'
    with open(layer,'rb') as src,gzip.open(compressed,'wb',compresslevel=1) as dst:shutil.copyfileobj(src,dst)
    config['created']='2026-10-22T00:00:00Z';config.setdefault('history',[]).append({'created':config['created'],'created_by':'trino-paimon-iceberg package-image.py'})
    config['config']={'User':'1000:1000','WorkingDir':'/opt/trino','Env':['JAVA_HOME=/opt/java','PATH=/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin','LANG=C.UTF-8','CACHE_ENABLED=false'],'Entrypoint':['/opt/bin/entrypoint.sh'],'Cmd':[],'ExposedPorts':{'8080/tcp':{}},'StopSignal':'SIGTERM','Healthcheck':{'Test':['CMD','/opt/bin/healthcheck.sh'],'Interval':30000000000,'Timeout':5000000000,'StartPeriod':120000000000,'Retries':5},'Labels':{'org.opencontainers.image.title':'trino-paimon-iceberg','org.opencontainers.image.version':'0.3.0'}}
    config.pop('container_config',None);config.pop('container',None)
    raw=json.dumps(config,separators=(',',':')).encode();image_id=hashlib.sha256(raw).hexdigest();config_name=image_id+'.json';layer_name=layer_hash+'.tar.gz'
    manifest=[{'Config':config_name,'RepoTags':['trino-paimon-iceberg:0.3.0-arm64'],'Layers':old['Layers']+[layer_name]}]
    out=ROOT/'dist';out.mkdir(exist_ok=True);target=out/'trino-paimon-iceberg-0.3.0-arm64.docker.tar.gz'
    with tarfile.open(target,'w:gz',compresslevel=1) as t,tarfile.open(base) as b:
        for name in old['Layers']:t.addfile(b.getmember(name),b.extractfile(name))
        t.add(compressed,arcname=layer_name);add_bytes(t,config_name,raw);add_bytes(t,'manifest.json',json.dumps(manifest).encode())
    (out/'SHA256SUMS').write_text(digest(target)+'  '+target.name+'\n');(out/'IMAGE_ID').write_text('sha256:'+image_id+'\n')
    (out/'ROOTFS_PATH').write_text(str(root)+'\n')
    (work/'Dockerfile').write_text('FROM '+BASE+'\nCOPY --chown=1000:1000 rootfs/ /\nENV JAVA_HOME=/opt/java LANG=C.UTF-8 CACHE_ENABLED=false\nENV PATH=/opt/java/bin:$PATH\nUSER 1000:1000\nWORKDIR /opt/trino\nEXPOSE 8080\nHEALTHCHECK --interval=30s --timeout=5s --start-period=120s CMD ["/opt/bin/healthcheck.sh"]\nENTRYPOINT ["/opt/bin/entrypoint.sh"]\n')
    print(target,flush=True)
    print('Docker alternative context:',work,flush=True)

if __name__=='__main__':main()
