#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy.env
[[ "$(uname -m)" == aarch64 ]] || { echo 'Requires ARM64/aarch64 host'; exit 1; }
docker version --format 'Docker server={{.Server.Version}} arch={{.Server.Arch}}'
uname -r
getconf PAGESIZE
[[ "$CACHE_DIR" == /* && "$DATA_DIR" == /* && "$CACHE_DIR" != / && "$DATA_DIR" != / ]] || exit 1
[[ -d "$CACHE_DIR" && -d "$DATA_DIR" ]] || { echo 'Create cache/data directories and chown them to UID 1000.'; exit 1; }
findmnt -T "$CACHE_DIR"
df -h "$CACHE_DIR" "$DATA_DIR"
lsblk -o NAME,TYPE,SIZE,ROTA,MOUNTPOINT
[[ "$(docker image inspect "$IMAGE_NAME" --format '{{.Architecture}}')" == arm64 ]] || exit 1
docker run --rm --network none --entrypoint /opt/java/bin/java "$IMAGE_NAME" -version
docker run --rm --network none --entrypoint /bin/bash \
  -v "$CACHE_DIR:/data/cache:Z" -v "$DATA_DIR:/data/trino:Z" "$IMAGE_NAME" \
  -ec 'test -w /data/cache && test -w /data/trino && mkdir -p /data/trino/tmp /data/cache/paimon'
