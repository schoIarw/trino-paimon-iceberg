#!/usr/bin/env bash
# Build an isolated test-only image from a SHA256-pinned official MinIO binary.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(uname -m)" == aarch64 ]] || { echo 'This fixture is ARM64'; exit 1; }
context=build/minio-fixture
mkdir -p "$context"
if [[ ! -s "$context/minio" ]]; then
  curl -fL --retry 3 https://github.com/minio/minio/releases/download/RELEASE.2025-04-22T22-12-26Z/minio.linux-arm64.RELEASE.2025-04-22T22-12-26Z -o "$context/minio"
fi
printf '%s  %s\n' 6c2f3142c94240206123177f4ba1e360daa5d1e0a4962e90757ef4f92c3ab57c "$context/minio" | sha256sum -c -
chmod 755 "$context/minio"
cat > "$context/Dockerfile" <<'DOCKER'
FROM ubuntu@sha256:722ea796ac2d57eeb3627c58a582fc1acc58be51faf815e1bce1682ae5c092f7
COPY minio /usr/local/bin/minio
ENTRYPOINT ["/usr/local/bin/minio"]
DOCKER
docker build --platform linux/arm64 -t paimon-native-test-minio:2025-04-22 "$context"
