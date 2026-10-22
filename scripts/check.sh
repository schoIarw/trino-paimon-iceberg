#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy.env
docker inspect "$CONTAINER_NAME" --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{end}}'
bash scripts/sql.sh --execute 'SELECT version(); SHOW CATALOGS; SHOW SCHEMAS FROM paimon;'
bash scripts/sql.sh --execute 'SHOW SCHEMAS FROM iceberg;'
bash scripts/sql.sh --execute "SELECT table_name FROM jmx.information_schema.tables WHERE table_schema='current' AND table_name LIKE '%alluxio%' ORDER BY table_name"
du -sh "$CACHE_DIR/paimon"
