#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp -f vendor/pom.xml -P '!toolchain' -Dcheckstyle.skip -Dspotless.check.skip \
  -Dtest=PaimonNativeCacheTest,TrinoNativeCacheFactoryTest,PaimonSplitAffinityTest,TrinoColumnHandleTest,TrinoConnectorFactoryTest,TrinoFilterConverterTest,TrinoFilterExtractorTest,TrinoPartitioningHandleTest,TrinoPluginTest,TrinoSplitTest,TrinoTableHandleTest \
  clean package "$@"
