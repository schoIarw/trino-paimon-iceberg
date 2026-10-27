#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../media"
name=trino-paimon-iceberg-0.4.0-native-arm64.docker.tar.gz
[[ -f "$name" ]] || { echo "Download the full native-cache ARM64 image into media/ first"; exit 1; }
# The source package contains the checksum for this image. The release-wide
# SHA256SUMS may also list manuals and source archives that are outside media/.
awk -v image="$name" '$2 == image { print; found=1 } END { if (!found) exit 1 }' SHA256SUMS | sha256sum -c -
docker load -i "$name"
docker image inspect trino-paimon-iceberg:0.4.0-native-arm64 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
