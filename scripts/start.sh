#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy.env
[[ "$CACHE_ENABLED" == true || "$CACHE_ENABLED" == false ]] || exit 1
if grep -q REPLACE minio.env; then echo 'Complete minio.env first'; exit 1; fi
if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "Container exists: $CONTAINER_NAME; explicitly stop/remove before recreating."; exit 1
fi
bash scripts/preflight.sh
docker run -d --pull=never --name "$CONTAINER_NAME" --restart unless-stopped \
  --memory "$CONTAINER_MEMORY" --cpus "$CONTAINER_CPUS" --ulimit nofile=131072:131072 \
  -p "$BIND_IP:$HTTP_PORT:8080" --env-file minio.env -e "CACHE_ENABLED=$CACHE_ENABLED" \
  -v "$PWD/etc:/etc/trino:ro,Z" -v "$CACHE_DIR:/data/cache:Z" -v "$DATA_DIR:/data/trino:Z" \
  --log-driver json-file --log-opt max-size=50m --log-opt max-file=5 "$IMAGE_NAME"
