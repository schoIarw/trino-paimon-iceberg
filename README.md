# Trino 484 + Paimon 2.1 native local cache + Iceberg

独立分支：`feature/paimon-native-local-cache`，版本 `0.4.0-native`，ARM64 Docker 安装镜像。

Paimon 表统一走 Paimon 官方 reader 与 `CachingFileIO`，使用自身的 local disk cache：按需分块、LRU、容量限制、文件类型白名单。移除 Trino ORC 快速读取旁路，不使用 Trino/Alluxio 文件缓存。开关关闭时仍使用同一 reader，便于公平对照。默认 `cache-enabled=false` 关闭 Catalog 元数据对象缓存，便于实时可见性验证；它与文件块缓存是两个独立开关。

Iceberg 使用官方 Trino484 插件，保留查询能力，`fs.cache.enabled=false`。**Iceberg 表不经过 Paimon local cache。** 本分支不把 Iceberg 转成 Paimon，也不提供 SQL 结果缓存。需要 Iceberg SSD 文件缓存时可继续使用主分支 Alluxio 方案。

- [一步步 Docker 安装与配置手册](docs/NATIVE-CACHE-DOCKER.md)
- [测试、验证与实机验收](docs/TEST-VALIDATION.md)
- [安装介质及验证报告](https://github.com/schoIarw/trino-paimon-iceberg/releases/tag/v0.4.0-native)

现有生产 Trino416/Paimon0.8 不升级。新实例使用只读权限接入现有 MinIO，旧表与特殊类型兼容性须逐表验收。

```bash
bash scripts/load.sh
bash scripts/init.sh
# 按手册配置 deploy.env、minio.env、etc/，创建专用 SSD 和运行目录
bash scripts/preflight.sh
bash scripts/start.sh
bash scripts/check.sh
```

发布工作流只有在 Java 单测、镜像内容检查和真实 MinIO 冷热读/并发/新快照/重启测试通过后才创建 `v0.4.0-native` 预发布。生产环境性能需现场测量，CI 不构成 CentOS7 内核或所有旧表兼容性保证。
