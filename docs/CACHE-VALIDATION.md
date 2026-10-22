# Trino 484 / Paimon 2.1 缓存验证

## 已验证的源码路径

1. Paimon 的 `TrinoFileIO.newInputStream` 调用 `TrinoFileSystem.newInputFile().newStream()`，因此会经过 484 `CacheFileSystem`。`FileSystemModule` 仅在 `fs.cache.enabled=true` 时装饰受支持的文件系统。
2. `PaimonCacheKeyProvider` 对 UUID 命名的数据/changelog 文件使用 Trino 484 默认键；元数据与未知文件名返回空键。缓存键的版本取远端 `lastModified` 与 `length`，因此第一次查询可能产生额外 HEAD 请求。若对象存储对时间戳不稳定，命中率也会下降。
3. Alluxio 页缓存从插件目录加载，在 `cache-manager-alluxio.properties` 设置路径、容量、TTL 和页大小；Paimon catalog 只保留 `fs.cache.enabled`。调度亲和键由 split 的最大文件位置生成。
4. Iceberg 484 原生插件使用自己的文件系统缓存路径，同一 Alluxio 管理器在 Iceberg catalog 中通过 `fs.cache.enabled` 启用；Paimon 的自定义 `CacheKeyProvider` 不负责 Iceberg 文件。两类表要分别采集缓存指标及对象存储流量。

已在本地 JDK25/x86 使用真实 AlluxioCache 跑过 `PaimonDiskCacheTest`：第二次读取不增加 `externalReads`，磁盘产生页文件；Paimon `TrinoFileIO` seek 也命中；snapshot 提示文件始终绕过缓存。`PaimonSplitAffinityTest` 与带缓存的连接器初始化测试通过。生产 MinIO 和 ARM64 Docker 尚需实机确认。

## 目标机步骤

1. `bash scripts/preflight.sh`，确认输出 CPU 架构、内核、SSD 挂载、Java25 容器启动和目录权限。
2. 以 `CACHE_ENABLED=false` 启动，分别 `SHOW SCHEMAS FROM paimon` 和 `SHOW SCHEMAS FROM iceberg`，读取两类目标表的少量数据，记录表快照、源站 GET/字节数与查询耗时。
3. `CACHE_ENABLED=true` 后重建容器，对同一快照执行两遍相同查询，分别记录 `system.runtime.nodes`、`jmx` 中 Alluxio `CacheReads` / `ExternalReads` 指标、`du -sh /data/ssd/trino-cache` 和 MinIO GET/字节数。热读应增加缓存读、减少外部读；只看 SQL 延迟不足以判断。
4. 在独立测试表写入新快照并查询；确认新数据可见。测试旧表、自定义文件名、ORC/Parquet、主键合并以及 changelog。不要改写生产对象来模拟覆盖。
5. 多节点时分别查看每个 worker 的 SSD 使用率和远端 GET；确认同一文件的 split 分派稳定。单节点无需分布式亲和验证。

缓存本身不会保证所有查询加速；小数据量、读取模式、数据文件格式、远端延迟、内存命中和调度均影响结果。更新生产参数前保留具体指标和日志。默认 100GB 上限须按实际 SSD 余量调整。
