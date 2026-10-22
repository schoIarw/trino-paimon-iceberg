#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../media"
name=trino-paimon-iceberg-0.3.0-arm64.docker.tar.gz
if [[ ! -f "$name" ]]; then
  sha256sum -c PARTS_SHA256SUMS
  cat "$name.part01" "$name.part02" > "$name.partial"
  mv "$name.partial" "$name"
fi
sha256sum -c SHA256SUMS
docker load -i "$name"
docker image inspect trino-paimon-iceberg:0.3.0-arm64 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
