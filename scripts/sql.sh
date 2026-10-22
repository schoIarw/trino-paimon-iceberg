#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy.env
exec docker exec -i "$CONTAINER_NAME" /opt/java/bin/java -jar /opt/trino-cli.jar \
  --server http://127.0.0.1:8080 --user ai_reader "$@"
