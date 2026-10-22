#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/downloads
cache_zip=build/downloads/trino-blob-cache-alluxio.zip
cache_jar=build/downloads/trino-blob-cache-alluxio-484.jar
if [[ ! -s "$cache_zip" ]]; then
  curl -fL --retry 3 https://github.com/trinodb/trino/releases/download/484/trino-blob-cache-alluxio-484.zip -o "$cache_zip"
fi
unzip -p "$cache_zip" trino-blob-cache-alluxio-484/trino-blob-cache-alluxio-484.jar > "$cache_jar"
mvn -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$cache_jar" -DgroupId=io.trino -DartifactId=trino-blob-cache-alluxio \
  -Dversion=484 -Dpackaging=jar -DgeneratePom=true
mvn -B -ntp -f vendor/pom.xml -P '!toolchain' -Dcheckstyle.skip -Dspotless.check.skip \
  -Dtest=TrinoConnectorFactoryCacheTest,PaimonDiskCacheTest,PaimonSplitAffinityTest,TrinoColumnHandleTest,TrinoConnectorFactoryTest,TrinoFilterConverterTest,TrinoFilterExtractorTest,TrinoPartitioningHandleTest,TrinoPluginTest,TrinoSplitTest,TrinoTableHandleTest \
  clean package "$@"
