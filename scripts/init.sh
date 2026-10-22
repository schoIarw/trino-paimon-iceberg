#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for file in deploy.env minio.env; do
  if [[ ! -e "$file" ]]; then cp "conf/$file.example" "$file"; fi
done
chmod 600 minio.env
if [[ ! -d etc ]]; then cp -R conf/etc etc; fi
echo 'Edit deploy.env, minio.env and etc/catalog/paimon.properties.'
