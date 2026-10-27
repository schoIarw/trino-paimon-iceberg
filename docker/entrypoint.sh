#!/usr/bin/env bash
set -euo pipefail
if (( $# )); then exec "$@"; fi
mkdir -p /data/trino/tmp /data/cache/paimon-native
for dir in /data/trino /data/trino/tmp /data/cache/paimon-native; do
  [[ -w "$dir" ]] || { echo "Not writable by UID 1000: $dir" >&2; exit 1; }
done
exec /opt/trino/bin/launcher --etc-dir=/etc/trino run
