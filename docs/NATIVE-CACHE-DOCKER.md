# Paimon 原生 Local Cache：Docker 安装与配置手册

版本：0.4.0-native；分支：feature/paimon-native-local-cache。适用 ARM64/aarch64；Trino484、Paimon2.1.0、官方 Iceberg484、Temurin25。编写日期：2026-10-27。

## 1. 方案范围与原理

AI 查询进入这个独立 Trino 实例后，Paimon 表由 Paimon 官方 reader 读取，经过 Paimon `CachingFileIO` 后访问 MinIO。第一次读取时回源填充本机 SSD，之后读取同一不可变文件块可从 SSD 返回。缓存不是 SQL 结果缓存，解码、过滤、聚合、主键合并和并发调度仍由查询引擎执行。

本分支将原 ORC raw reader 统一切换到 Paimon reader，避免原生缓存被旁路；关闭缓存的对照也使用同一路径。ORC 与 Parquet 均由 Paimon reader 处理，主键表仍按 Paimon 语义合并。该选择可能改变 CPU/解码性能，必须按业务负载对比。

Iceberg 保留官方 Trino484 插件，但不使用 Paimon 缓存，也未启用 Alluxio 文件缓存。本分支没有独立 Paimon/Alluxio 服务容器，不改变现网入湖与写入链路。

| 缓存选项 | 含义 | 本分支配置 |
|---|---|---|
| local-cache.enabled | 开启原生块缓存 | 环境变量控制，初次先 false |
| local-cache.dir | 本地磁盘目录；未设置时为内存模式 | /data/cache/paimon-native |
| local-cache.max-size | manager 的缓存块容量上限，LRU 淘汰 | 100 gb，必须显式设置正数且不小于块大小 |
| local-cache.block-size | 固定文件块大小 | 1 mb |
| local-cache.whitelist | 文件类型准入 | meta,global-index,data,bucket-index,file-index |
| cache-enabled | Paimon Catalog 元数据对象缓存 | false，便于新提交可见性和回源对照 |
| fs.cache.enabled | Trino 文件缓存开关 | Paimon/Iceberg 均固定 false |

Paimon 默认 whitelist 不含 data，因此只开启 enabled 不足以确保普通数据文件被缓存。可变提示文件仍由 Paimon 原生规则绕过。此原生缓存配置没有沿用 Alluxio TTL 参数，不应填写 local-cache.ttl。容量限额按 manager/进程管理，不是集群总额，也不包括所有内存、索引和文件系统开销。

磁盘缓存键包含路径、长度、修改时间，并区分块大小。维持数据文件不可变，不要原地改写对象；HEAD/LIST 等元数据访问仍可能存在。内存缓存不跨进程重启保留；磁盘重启复用以文件标识和路径一致为前提。生产应用直接下载图片不会经过这个 Trino 实例。

## 2. 目标机准备

沿用已有 ARM64 CentOS7.9 AltArch / Docker20.10.8 环境；旧内核、libseccomp、cgroup 与 JDK25 必须实机预检，不能仅按版本号保证兼容。目标机无需 Java、Maven、Python，也无需联网构建镜像。需要 Bash、tar、sha256sum、findmnt、lsblk 等常规工具。

部署目录使用 `/opt/ai-trino-native/trino-paimon-iceberg`，容器名 `trino-paimon-native`，宿主机端口18081，缓存目录 `/data/ssd/paimon-native-cache`，运行目录 `/data/trino-native`，可与主分支18080端口实例分开部署，但仍需合计主机资源。

默认资源：32 CPU、容器96g、JVM64G、缓存100GB；宿主必须另外留出资源。轻量功能验证可用8 CPU、容器24g、Xmx16G、查询总/每节点上限6GB、heap headroom4GB，需联动调整，不能只降低容器内存却保留64G堆。SSD建议留至少20%空闲及文件系统开销，目录必须真的位于本地SSD挂载上。

```bash
uname -m
docker version
findmnt -T /data/ssd
lsblk -o NAME,TYPE,SIZE,ROTA,MOUNTPOINT
```

应为 aarch64。x86_64 不能直接运行现有ARM64发布镜像。

数据源前提：容器可达MinIO API地址；只读凭据有目标前缀 List/Get 权限；warehouse 指向现有 Paimon 仓库根目录。示例Paimon为filesystem catalog，Iceberg为已有Hive Metastore。如果现网Paimon使用HMS/REST/JDBC，先确认元数据发现方式，不能仅凭warehouse相同就认为catalog等价。当前镜像不面向HDFS。

## 3. 下载介质

在可联网的任意机器下载，不需要在下载机运行镜像：

```bash
mkdir -p native-cache-media
cd native-cache-media
RELEASE_BASE=https://github.com/schoIarw/trino-paimon-iceberg/releases/download/v0.4.0-native
for artifact in trino-paimon-iceberg-0.4.0-native-source.tar.gz trino-paimon-iceberg-0.4.0-native-arm64.docker.tar.gz SHA256SUMS IMAGE_ID NATIVE-CACHE-DOCKER.md TEST-VALIDATION.md native-cache-validation.json paimon-trino-484-2.1.0-tests.jar; do
  curl -fL --retry 3 "$RELEASE_BASE/$artifact" -o "$artifact"
done
sha256sum -c SHA256SUMS
```

将完整目录转移到目标机 `/opt/ai-trino-native/install-media`。发布页为 https://github.com/schoIarw/trino-paimon-iceberg/releases/tag/v0.4.0-native ，镜像为完整Docker load归档，不需要docker pull或目标机编译。校验不通过时停止导入，重新获取介质。

## 4. 解包与导入

```bash
mkdir -p /opt/ai-trino-native
cd /opt/ai-trino-native
tar -xzf install-media/trino-paimon-iceberg-0.4.0-native-source.tar.gz
cd trino-paimon-iceberg
mkdir -p media
cp ../install-media/trino-paimon-iceberg-0.4.0-native-arm64.docker.tar.gz media/
cp ../install-media/SHA256SUMS ../install-media/IMAGE_ID media/
bash scripts/load.sh
bash scripts/init.sh
docker image inspect trino-paimon-iceberg:0.4.0-native-arm64 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

应显示linux/arm64。load.sh仅验证当前镜像对应的checksum条目，避免完整发布清单中的其他文件不在media导致误报。init.sh只在不存在时复制模板，不覆盖已有配置。

## 5. 配置文件

完整模板列在本手册附录，可直接对照。首次按如下顺序编辑：

1. `deploy.env`：确认容器名、端口、缓存目录、运行目录、CPU/内存，先保留 `PAIMON_LOCAL_CACHE_ENABLED=false`。
2. `minio.env`：填写实际warehouse、MinIO endpoint、region、只读AK/SK，以及Iceberg使用的真实HMS URI。
3. `etc/catalog/paimon.properties`：核对local-cache五项配置，whitelist包含data，fs.cache.enabled必须false；默认关闭Catalog对象缓存。
4. `etc/catalog/iceberg.properties`：核对元数据类型与HMS；本分支不对Iceberg开启原生Paimon缓存。
5. `etc/jvm.config`与`etc/config.properties`：根据机器资源联动调整JVM堆、查询内存和headroom，保留其他模块/native-access等参数。

minio.env示例（用真实值替换REPLACE占位，不能残留REPLACE，否则start拒绝运行）：

```text
PAIMON_WAREHOUSE=s3://your-paimon-bucket/warehouse
S3_ENDPOINT=http://10.0.0.10:29000
S3_REGION=us-east-1
S3_ACCESS_KEY=your-readonly-access-key
S3_SECRET_KEY=your-readonly-secret-key
HIVE_METASTORE_URI=thrift://10.0.0.20:9083
```

`docker --env-file` 使用KEY=value，不加export或额外引号。MinIO控制台端口不是S3 API端口。容器localhost不代表宿主机，使用容器可达IP/DNS；endpoint选择HTTP/HTTPS，warehouse仍使用s3://。自签证书须把CA正确纳入JVM信任库。

Iceberg依赖原有HMS登记的表，不能换个空HMS就读取所有现网表。两种表使用不同MinIO/凭据时分开配置环境变量。暂不验证Iceberg时将iceberg.properties移到catalog目录外，并使用下面单项SQL检查，不能直接执行强制检查Iceberg的check.sh。

```bash
chmod 600 minio.env
mkdir -p /data/ssd/paimon-native-cache /data/trino-native
chown 1000:1000 /data/ssd/paimon-native-cache /data/trino-native
chmod 750 /data/ssd/paimon-native-cache /data/trino-native
findmnt -T /data/ssd/paimon-native-cache
df -h /data/ssd/paimon-native-cache /data/trino-native
```

容器内 `/data/cache/paimon-native` 实际对应宿主机 `/data/ssd/paimon-native-cache/paimon-native`。不要用旧Alluxio缓存目录，也不要把同一目录同时给多个进程/容器管理。

## 6. 预检与启动：先关闭缓存验证源站

```bash
cd /opt/ai-trino-native/trino-paimon-iceberg
bash scripts/preflight.sh
bash scripts/start.sh
docker logs --tail 100 trino-paimon-native
```

预检验证ARM64、镜像架构、Java启动和UID1000目录写权限，打印挂载与磁盘信息；它不替人判断SSD。脚本使用SELinux `:Z` 标签，目录应专属本实例。同名容器存在时脚本会拒绝覆盖。

初始化完成后执行：

```bash
bash scripts/sql.sh --execute 'SELECT version(); SHOW CATALOGS;'
bash scripts/sql.sh --execute 'SELECT * FROM system.runtime.nodes;'
bash scripts/sql.sh --execute 'SHOW SCHEMAS FROM paimon;'
bash scripts/sql.sh --execute 'SHOW SCHEMAS FROM iceberg;'
```

替换库表字段后读取实际值，先选择普通标量列，不要以VECTOR/BLOB兼容性作为首次连通性前提：

```bash
bash scripts/sql.sh --execute 'DESCRIBE paimon.your_db.your_table;'
bash scripts/sql.sh --execute 'SELECT id FROM paimon.your_db.your_table LIMIT 10;'
bash scripts/sql.sh --execute 'SELECT id FROM iceberg.your_db.your_table LIMIT 10;'
```

与原实例核对结果、主键合并和更新删除语义。SHOW SCHEMAS不证明实际对象数据可读。旧Paimon0.8表、schema evolution、特殊类型需逐项验收，不升级现网表。

## 7. 开启原生SSD缓存

先保存无缓存基线及MinIO读流量，再切换：

```bash
sed -i 's/^PAIMON_LOCAL_CACHE_ENABLED=false$/PAIMON_LOCAL_CACHE_ENABLED=true/' deploy.env
docker stop -t 120 trino-paimon-native
docker rm trino-paimon-native
bash scripts/start.sh
bash scripts/check.sh
du -sh /data/ssd/paimon-native-cache/paimon-native
```

环境变量变更必须重建容器，docker restart不会更新旧环境。rm只删除已停止容器，不删除绑定SSD目录。catalog配置变更需重启/重建生效。

## 8. 客户端访问

默认绑定127.0.0.1:18081，无密码认证，仅使用SSH隧道：

```bash
ssh -L 18081:127.0.0.1:18081 your-user@your-arm64-server
```

浏览器访问 http://127.0.0.1:18081 。容器内CLI通过scripts/sql.sh执行；JDBC使用 `jdbc:trino://127.0.0.1:18081/paimon/your_db`，客户端用户名称按现网规范填写。要对其他网段开放服务，先接入既有认证、TLS和访问控制，再调整BIND_IP及防火墙。

## 9. 验证收益

完整步骤见TEST-VALIDATION.md。核心证据是同文件集热读时MinIO数据GET/返回字节减少，且结果正确、SSD出现块文件。这里没有Alluxio manager，也不应查询Alluxio JMX指标来证明Paimon原生缓存命中。

优先使用稳定历史分区及真实热点列，执行3–5次，记录关闭缓存、首次缓存读、热读三组。不要仅用SHOW TABLES、LIMIT10、纯COUNT(*)或延迟变化判断数据缓存。数据持续写入/compaction会产生新文件，须记录快照/文件集。

## 10. 停启、备份、回退

```bash
docker logs --tail 200 trino-paimon-native
docker stats --no-stream trino-paimon-native
docker stop -t 120 trino-paimon-native
docker start trino-paimon-native
```

含密钥配置备份限制权限：

```bash
umask 077
tar -czf native-config-$(date +%Y%m%d-%H%M%S).tar.gz deploy.env minio.env etc
```

关闭原生缓存：

```bash
sed -i 's/^PAIMON_LOCAL_CACHE_ENABLED=true$/PAIMON_LOCAL_CACHE_ENABLED=false/' deploy.env
docker stop -t 120 trino-paimon-native
docker rm trino-paimon-native
bash scripts/start.sh
```

完全冷缓存实验用新空SSD专用目录：停止容器，创建新目录并授权UID1000，在deploy.env替换CACHE_DIR后重建。保留旧目录供回退，勿在线删除缓存块，更不能删除源站对象。业务也可路由回原Trino实例，两个分支互不合并。

## 11. 常见故障

| 问题 | 检查与处理 |
|---|---|
| exec format error | 核对aarch64与arm64；x86需另构建镜像 |
| Java25启动失败 | 保存具体错误，核对内核/libseccomp/cgroup；不要依赖privileged绕过 |
| 不可写 | 检查专用目录、UID1000、SELinux标签，勿递归修改整块生产盘属主 |
| Trino缓存开关报错 | 本分支禁止fs.cache.enabled=true，使用local-cache.* |
| 原生缓存容量错误 | 显式设置正数max-size>=block-size，块大小不超过int字节范围 |
| SHOW SCHEMAS正常但查询失败 | 核对真实对象权限、location、类型、格式、压缩与旧表兼容 |
| SSD目录不增长 | 开关是否在重建后生效、whitelist是否含data、查询是否实际扫描数据、挂载权限 |
| 热读仍有远端请求 | 区分数据GET和HEAD/LIST，核对快照/文件标识、缓存容量及淘汰 |
| 新提交不可见 | 确认写入已提交、数据源路径与Catalog缓存；初始保留cache-enabled=false |
| 热读仍慢 | 看CPU、Paimon解码/主键merge、GC、队列；缓存不是结果缓存 |
| Iceberg没有写缓存 | 预期行为，Iceberg不经过Paimon原生FileIO |
| OOMKilled | 联动缩小堆、查询内存、并发，给native/线程/页缓存留空间 |

## 12. 编译与发布

开发机需要JDK25、Maven3.9+、Python3.12+、curl、unzip、Docker或crane。推荐运行此分支的GitHub Actions：Java单测→镜像打包/完整性校验→真实MinIO集成→源码包/手册/JSON报告→独立预发布v0.4.0-native。生产目标机仅导入发布镜像。

手工编译：`bash scripts/build-connector.sh`，再按锁定基础镜像拉取/导出至build/downloads/ubuntu-arm64.tar，执行 `python3 scripts/package-image.py`。`artifacts.lock.json`固定JDK、Trino和Iceberg插件输入SHA256。镜像不包含Alluxio文件缓存插件。

参考：[Paimon2.1官方Local Cache](https://paimon.apache.org/docs/2.1/program-api/file-cache/)、项目native分支源码。以下附录为此分支完整模板。


### 附录：conf/deploy.env.example

```text
IMAGE_NAME=trino-paimon-iceberg:0.4.0-native-arm64
CONTAINER_NAME=trino-paimon-native
BIND_IP=127.0.0.1
HTTP_PORT=18081
CACHE_DIR=/data/ssd/paimon-native-cache
DATA_DIR=/data/trino-native
CONTAINER_MEMORY=96g
CONTAINER_CPUS=32
PAIMON_LOCAL_CACHE_ENABLED=false
```


### 附录：conf/minio.env.example

```text
PAIMON_WAREHOUSE=s3://REPLACE_BUCKET/REPLACE_WAREHOUSE
S3_ENDPOINT=http://REPLACE_MINIO:9000
S3_REGION=us-east-1
S3_ACCESS_KEY=REPLACE_ACCESS_KEY
S3_SECRET_KEY=REPLACE_SECRET_KEY
HIVE_METASTORE_URI=thrift://REPLACE_HMS:9083
```


### 附录：conf/etc/catalog/paimon.properties

```text
connector.name=paimon
warehouse=${ENV:PAIMON_WAREHOUSE}
fs.s3.enabled=true
s3.endpoint=${ENV:S3_ENDPOINT}
s3.region=${ENV:S3_REGION}
s3.path-style-access=true
s3.aws-access-key=${ENV:S3_ACCESS_KEY}
s3.aws-secret-key=${ENV:S3_SECRET_KEY}
fs.cache.enabled=false
cache-enabled=false
local-cache.enabled=${ENV:PAIMON_LOCAL_CACHE_ENABLED}
local-cache.dir=/data/cache/paimon-native
local-cache.max-size=100 gb
local-cache.block-size=1 mb
local-cache.whitelist=meta,global-index,data,bucket-index,file-index
```


### 附录：conf/etc/catalog/iceberg.properties

```text
connector.name=iceberg
iceberg.catalog.type=hive_metastore
hive.metastore.uri=${ENV:HIVE_METASTORE_URI}
fs.s3.enabled=true
s3.endpoint=${ENV:S3_ENDPOINT}
s3.region=${ENV:S3_REGION}
s3.path-style-access=true
s3.aws-access-key=${ENV:S3_ACCESS_KEY}
s3.aws-secret-key=${ENV:S3_SECRET_KEY}
fs.cache.enabled=false
```


### 附录：conf/etc/catalog/jmx.properties

```text
connector.name=jmx
```


### 附录：conf/etc/config.properties

```text
coordinator=true
node-scheduler.include-coordinator=true
http-server.http.port=8080
discovery.uri=http://127.0.0.1:8080
query.max-memory=32GB
query.max-memory-per-node=32GB
memory.heap-headroom-per-node=16GB
node-scheduler.cache-preferred-hosts-count=2
```


### 附录：conf/etc/node.properties

```text
node.environment=ai_cache
node.id=ai-native-cache-arm64-01
node.data-dir=/data/trino
```


### 附录：conf/etc/jvm.config

```text
-server
-Xms4G
-Xmx64G
-XX:+UseG1GC
-XX:G1HeapRegionSize=32M
-XX:+ExplicitGCInvokesConcurrent
-XX:+ExitOnOutOfMemoryError
-XX:-OmitStackTraceInFastThrow
-XX:ReservedCodeCacheSize=512M
-XX:PerMethodRecompilationCutoff=10000
-XX:PerBytecodeRecompilationCutoff=10000
-Djdk.attach.allowAttachSelf=true
-Djdk.nio.maxCachedBufferSize=2000000
-Dfile.encoding=UTF-8
-XX:+EnableDynamicAgentLoading
--enable-native-access=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
-Djava.io.tmpdir=/data/trino/tmp
```


### 附录：conf/etc/access-control.properties

```text
access-control.name=read-only
```


### 附录：完整Docker启动参数

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy.env
[[ "$PAIMON_LOCAL_CACHE_ENABLED" == true || "$PAIMON_LOCAL_CACHE_ENABLED" == false ]] || exit 1
if grep -q REPLACE minio.env; then echo 'Complete minio.env first'; exit 1; fi
if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "Container exists: $CONTAINER_NAME; explicitly stop/remove before recreating."; exit 1
fi
bash scripts/preflight.sh
docker run -d --pull=never --name "$CONTAINER_NAME" --restart unless-stopped \
  --memory "$CONTAINER_MEMORY" --cpus "$CONTAINER_CPUS" --ulimit nofile=131072:131072 \
  -p "$BIND_IP:$HTTP_PORT:8080" --env-file minio.env -e "PAIMON_LOCAL_CACHE_ENABLED=$PAIMON_LOCAL_CACHE_ENABLED" \
  -v "$PWD/etc:/etc/trino:ro,Z" -v "$CACHE_DIR:/data/cache:Z" -v "$DATA_DIR:/data/trino:Z" \
  --log-driver json-file --log-opt max-size=50m --log-opt max-file=5 "$IMAGE_NAME"
```
