# Trino 484 + Paimon 2.1 + Iceberg 本地 SSD 缓存（ARM64 实验版）

面向 CentOS 7.9 AltArch、Docker 20.10.8、aarch64 的单节点部署。SSH 是登录和隧道入口；缓存数据由容器写入宿主机挂载的本地 SSD。此源码基于 Apache Paimon Trino 连接器，构建 Trino 484 兼容版本，并使用 Trino 484 的 Alluxio blob cache 插件。

| 组件 | 版本 |
|---|---|
| Trino server-core / CLI / JMX / Alluxio blob cache / Iceberg 插件 | 484 |
| Paimon bundle | 2.1.0 |
| Iceberg 插件内 Apache Iceberg 库 | 1.12.0（官方 Trino 484 ZIP） |
| Java ARM64 | Eclipse Temurin 25.0.4+7 |
| 基础镜像 | 锁定的 Ubuntu ARM64 digest |
| 镜像标签 | `trino-paimon-iceberg:0.3.0-arm64` |

## 实现

- `vendor/src/main/java/org/apache/paimon/trino/fileio/TrinoFileIO.java` 使 Paimon 的数据文件读取经过 Trino filesystem；连接器 `FileSystemModule` 启用 484 的共享 blob cache 接口。`conf/etc/config.properties` 加载 Alluxio 管理器，缓存目录 `/data/cache/paimon` 对应宿主机 SSD bind mount。
- `PaimonCacheKeyProvider` 仅缓存具有 UUID 文件名的数据和 changelog 文件；snapshot、schema、manifest 等元数据以及未知文件名直接读源。实际缓存键还包含位置、修改时间和长度，避免同路径对象替换时重用旧数据。
- `PaimonSplitAffinity` 为多文件 split 选最大数据文件作为稳定亲和键，便于调度器将后续读取分派到持有缓存的节点。单节点部署的收益主要来自重复扫描。
- `PaimonDiskCacheTest` 使用真实 Alluxio 缓存页、Trino memory filesystem 和 Paimon `TrinoFileIO` 验证冷读、热读、并发读、元数据绕过与不同长度/修改时间的键。`TrinoConnectorFactoryCacheTest` 验证连接器在缓存启用时的 Guice 初始化。测试不代替真实 MinIO、生产表、ARM64 Docker 和负载压测。
- Iceberg 使用 Trino 484 官方 `trino-iceberg-484.zip` 插件，与服务端版本完全一致；`conf/etc/catalog/iceberg.properties` 配置 Hive Metastore、MinIO 和同一个节点级 Alluxio 缓存。Iceberg 缓存走 Trino 官方连接器逻辑，不使用 Paimon 的自定义文件名过滤器。现网 Iceberg 表格式与快照仍需验收。

当前范围是普通 UUID 命名的数据/变更日志文件。旧版本/自定义命名、外置索引与向量文件绕过缓存；Paimon 0.8 老表、Hive Metastore 及多节点亲和调度仍需在目标环境验证。SQL 查询可能被 Paimon 合并读和页大小影响，不能由连接器启动推出实际命中率。
当前镜像面向 S3/MinIO；没有打入独立的 Trino HDFS 运行目录，`fs.hadoop.enabled`/HDFS warehouse 不在此版本的部署范围。

## 构建

在 ARM64 构建机安装 JDK 25、Maven 3.9+、Python 3.12+、curl、unzip 与 `crane`，或者运行 `.github/workflows/build.yml` 的 ARM64 工作流。Trino 484 的 Alluxio 插件以官方发布 ZIP 提供；`scripts/build-connector.sh` 从 ZIP 提取 JAR 到本地 Maven 仓库，随后编译并运行选定测试。

```bash
bash scripts/build-connector.sh
python3 scripts/package-image.py
(cd dist && sha256sum -c SHA256SUMS)
python3 tests/verify-image.py
```

打包器从官方 release 下载 JDK25 ARM64、Trino 484 core/JMX/CLI/Alluxio/Iceberg ZIP；使用 `artifacts.lock.json` 固定每个 SHA256，使用 `crane pull --platform linux/arm64` 获取锁定的基础镜像，输出 Docker load 归档和 `IMAGE_ID`。`docker build` 的替代上下文也写入 `build/image-*`。镜像包含三个插件目录 `paimon`、`iceberg`、`trino-blob-cache-alluxio`。本地开发可用 JDK25 执行 Maven；x86 编译通过不意味着 ARM64 启动通过。

## 在目标机部署

从 v0.3.0 的 GitHub Release 下载源码包和 ARM64 Docker 镜像。解包源码，将镜像放入 `media/`，运行：

```bash
bash scripts/load.sh
bash scripts/init.sh
vi deploy.env
vi minio.env
vi etc/catalog/paimon.properties
vi etc/catalog/iceberg.properties
mkdir -p /data/ssd/trino-cache /data/trino-ai
chown 1000:1000 /data/ssd/trino-cache /data/trino-ai
bash scripts/preflight.sh
bash scripts/start.sh
bash scripts/check.sh
bash scripts/sql.sh --execute 'SHOW SCHEMAS FROM paimon'
bash scripts/sql.sh --execute 'SHOW SCHEMAS FROM iceberg'
```

在 `minio.env` 中填入 HMS 地址 `HIVE_METASTORE_URI=thrift://HMS主机:9083`，确认 Iceberg 表的元数据确实由该 HMS 管理；若现网使用 REST/JDBC 等目录，按 Trino 484 官方文档调整 `iceberg.catalog.type` 和其连接参数。先以 `CACHE_ENABLED=false` 验证两种表的源站连接。把 `deploy.env` 中 `CACHE_ENABLED=true` 并重建容器后，对同一批不可变数据文件执行两次查询。检查 `jmx` 的 Alluxio 读/外部读指标、SSD 页文件以及对象存储 GET 请求次数；查询延迟只有结合命中率、远端流量与分片分布才能判断是否有效加速。初始上限 `100GB` 在 `etc/cache-manager-alluxio.properties` 调整，SSD 需独立挂载。配置变更需要重启容器。具体步骤见 `docs/CACHE-VALIDATION.md`。

默认仅绑定 `127.0.0.1:18080`：`ssh -L 18080:127.0.0.1:18080 root@alluxio-1`。服务未配置密码认证；发布到其他网卡前接入现网认证和访问控制。容器默认只读 SQL 策略，MinIO 凭据也建议只读。

## 源码交付和限制

源码仓库：https://github.com/schoIarw/trino-paimon-iceberg 。`vendor/` 为 Paimon 连接器源码；Iceberg 使用与 Trino 484 匹配的官方发布插件，固定下载 URL 和 SHA256 在 `artifacts.lock.json`；`scripts/` 为构建/部署脚本，`conf/` 为配置。

Docker 20.10.8 和 CentOS 7.9 的具体内核、libseccomp 与 cgroup 配置无法从版本号保证 JDK25 容器兼容；必须在目标 ARM64 运行 `scripts/preflight.sh` 和实际查询。构建过程不要求目标机有 Java 或 Maven。
