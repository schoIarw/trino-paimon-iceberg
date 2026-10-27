#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy.env
docker inspect "$CONTAINER_NAME" --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{end}}'
bash scripts/sql.sh --execute 'SELECT version(); SHOW CATALOGS; SHOW SCHEMAS FROM paimon;'
bash scripts/sql.sh --execute 'SHOW SCHEMAS FROM iceberg;'
du -sh "$CACHE_DIR/paimon-native"
docker exec "$CONTAINER_NAME" /bin/bash -ec 'test -d /data/cache/paimon-native'
