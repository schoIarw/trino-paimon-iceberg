# 484/2.1 + Iceberg 484 验证记录（2026-10-22）

| 项目 | 结果 |
|---|---|
| JDK25 / Maven 编译 | 成功 |
| 11 个选定测试类、18 个测试方法 | 成功，0 failure / error；包含磁盘缓存、split 亲和、连接器缓存启动 |
| Maven `package -DskipTests` | 成功，生成 `paimon-trino-484-2.1.0-plugin.tar.gz` |
| Trino 484 / Paimon 2.1 / Iceberg 484 ARM64 Docker load 归档 | `tests/verify-image.py` 检查归档 SHA256、ARM64、三个插件目录、两个 catalog 配置和 JDK 25 路径；等待本版 CI 执行 |
| ARM64 Docker 实机启动及 MinIO 读写 | 此工作环境没有 Docker daemon 或目标 ARM64 主机，待目标部署后验证 |

缓存单测在 JVM 中以真实 AlluxioCache 和 Trino CacheFileSystem 建立页缓存，验证 Paimon FileIO 的 hot read 不新增 externalReads。该结论限定于测试的 UUID 命名数据文件、内存源站与固定读取模式。生产对象存储、老表、HMS、并发负载和多节点命中率尚需实测。

生产路径默认只读；提供 `tests/smoke-docker.py` 供 ARM64 CI 验证 Paimon 与 Iceberg catalog 同时加载。烟测中的 HMS URI 是占位地址，只验证注册与进程启动，不验证 Iceberg 表读取。目标机需用真实 HMS 和现有 Iceberg 表，再按 `CACHE-VALIDATION.md` 比较重复查询的 Alluxio 指标、SSD 页数、MinIO GET 和时延。
