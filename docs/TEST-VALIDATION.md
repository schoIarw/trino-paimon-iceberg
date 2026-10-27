# Paimon 原生 Local Cache 测试与验证

适用0.4.0-native。分为自动化隔离测试与现场只读验收，不能用CI性能数字代表生产收益。

## 1. 自动化测试覆盖

Java单测使用实际Paimon2.1 `CachingFileIO`/LocalDiskCacheManager，通过计数FileIO代理确认底层打开次数：冷读填充、热读不增加远端流打开、seek、并发热读、磁盘manager重建复用、可变LATEST绕过、白名单排除、LRU容量淘汰、文件长度变化避免旧块误用。工厂测试验证无需Trino cache manager即可启动，以及拒绝双缓存和无限容量。

Docker测试使用真实ARM64镜像和真实MinIO容器。Paimon官方API在临时目录生成ORC、Parquet、主键表，将文件上传至独立测试bucket；Trino通过计数S3代理读取，记录GET数和返回字节。比较关闭缓存两次、原生缓存冷读、热读，再测8次查询/4并发、写入第二快照后新增/更新/删除可见性，以及重启复用。断言结果数值与预期一致，不只检查服务启动。

测试生成 `native-cache-validation.json`，每项包括table、phase、result、data_gets、data_bytes。发布以工作流全部通过为前提。Iceberg此处仅验证插件加载/catalog存在；真实HMS表、VECTOR/BLOB类型和CentOS7运行环境由现场验收覆盖。

## 2. 在隔离ARM64开发机重跑

本测试只使用临时容器、本地临时目录及独立MinIO bucket，不接收生产凭据。需要Python3.12+、pip、Docker、可用的19000/19001/19002/18089端口、足够内存磁盘。MinIO计数代理只绑定localhost，Docker使用host网络，仅在隔离Linux测试机运行。

```bash
pip3 install boto3==1.40.51
# 源码编译已生成测试jar时直接执行；否则将发布中的测试jar放到media/
mkdir -p media
cp /path/to/paimon-trino-484-2.1.0-tests.jar media/
docker load -i /path/to/trino-paimon-iceberg-0.4.0-native-arm64.docker.tar.gz
docker pull quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z
python3 tests/smoke-docker.py
cat dist/native-cache-validation.json
```

离线机还需事先准备Python/boto3依赖及测试用MinIO镜像，联网ARM64机器使用docker save导出MinIO镜像后目标机docker load。测试MinIO镜像不是生产部署依赖，安装Trino镜像无需它。可用MINIO_TEST_IMAGE环境变量指定已导入的等价测试镜像标签，版本变化须记录。

失败时脚本输出临时目录、容器日志和JSON；finally清理自己的测试容器，不删除生产资源。读取测试报告status必须passed，不能只看JSON文件存在。

## 3. 现场无缓存基线

按安装手册先设置PAIMON_LOCAL_CACHE_ENABLED=false。选择稳定的历史分区或暂停写入的隔离测试表；记录Paimon快照、实际数据文件集、数据量及查询列。与原Trino核对普通表/主键表的结果。

用真实字段替换示例，读取值的表达式可避免纯COUNT(*)元数据优化：

```bash
mkdir -p validation
chmod 700 validation
bash scripts/sql.sh --execute "EXPLAIN ANALYZE SELECT sum(length(CAST(id AS varchar))) FROM paimon.your_db.your_table WHERE dt='2026-10-01';" > validation/disabled.txt
```

同步记录MinIO针对测试账号/bucket/前缀的数据GET数、GET字节、HEAD/LIST与延迟。要区分生产其他访问的流量；无法隔离的全局流量不能用于推算该SQL命中率。

## 4. 原生缓存冷读与热读

按安装手册开启原生缓存并重建容器，同文件集同列同过滤条件先读一次，再重复3–5次：

```bash
bash scripts/sql.sh --execute "EXPLAIN ANALYZE SELECT sum(length(CAST(id AS varchar))) FROM paimon.your_db.your_table WHERE dt='2026-10-01';" > validation/native-first.txt
bash scripts/sql.sh --execute "EXPLAIN ANALYZE SELECT sum(length(CAST(id AS varchar))) FROM paimon.your_db.your_table WHERE dt='2026-10-01';" > validation/native-hot.txt
du -sh /data/ssd/paimon-native-cache/paimon-native
find /data/ssd/paimon-native-cache/paimon-native -type f -print -quit
docker stats --no-stream trino-paimon-native
```

在MinIO现有监控/审计中比较数据GET与字节的同窗口增量；具体指标名随版本变化，以现场暴露内容为准。缓存热点驻留时应显著减少数据GET/字节，HEAD仍可能存在。本分支没有Alluxio缓存JMX；SSD文件出现或SQL变快单独都不足以证明命中。

## 5. 一致性与重启

由既有Flink/Spark写入端向专用测试表提交新增、同主键更新及删除，再查本实例，验证结果。初始cache-enabled=false避免Catalog对象缓存干扰，不用TTL代替一致性判断。不要覆盖生产对象来制造测试。

热读后停止并重启实例，重新执行同查询，检查结果及MinIO数据GET字节，验证磁盘块复用；重启后元数据HEAD不能被误算为数据miss。完全冷读使用新的SSD目录，停止容器后更换挂载，保留旧目录供回退。

## 6. 并发、容量与表类型

按1/4/8/16等业务相符并发逐级测量，记录P50/P95、失败率、CPU、堆、GC、MinIO流量和SSD读写。分别覆盖ORC、Parquet、主键表、旧0.8表、分区裁剪和字段变更。对VECTOR/BLOB/外置索引单独检查支持性，不以普通标量表成功推断全部可用。

容量测试在独立小预算缓存目录中进行，读取超过上限的多批文件，验证LRU淘汰后旧数据仍能正确回源。预算统计缓存块字节，磁盘实际占用可因目录/文件系统开销与暂态而更大，需预留空间。多节点每个worker使用独立本地目录、相同cache配置与唯一node.id，再核查调度亲和，单节点测试不证明多节点命中率。

## 7. 验收记录

|表/格式|快照或文件集|并发|阶段|结果校验|P95|数据GET增量|GET字节增量|SSD占用|错误数|
|---|---|---:|---|---|---|---:|---:|---|---:|
|填写|填写|1|disabled/cold/hot/restart各一行|填写|实测|实测|实测|实测|实测|

通过条件：查询结果一致、新提交可见、热读GET/字节减少、确有磁盘块、并发无结果错误、缓存失效可回源。业务性能目标应依据实际负载确定，不预填提升倍数。
