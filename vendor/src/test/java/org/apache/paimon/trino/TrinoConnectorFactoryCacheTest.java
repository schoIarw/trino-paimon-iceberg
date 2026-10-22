/* Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.paimon.trino;

import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorContext;
import io.trino.testing.TestingConnectorContext;
import io.trino.spi.cache.ConnectorCacheFactory;
import io.trino.blob.cache.alluxio.AlluxioCacheStats;
import org.apache.paimon.trino.cache.PaimonDiskCacheTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

public class TrinoConnectorFactoryCacheTest {
    @TempDir Path directory;

    @Test
    public void cacheEnabledCatalogStartsOnCoordinator() throws Exception {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path warehouse = Files.createDirectory(directory.resolve("warehouse"));
        var disk = PaimonDiskCacheTest.createCache(cache);
        var blobCache = PaimonDiskCacheTest.adapter(disk, new AlluxioCacheStats());
        TestingConnectorContext delegate = new TestingConnectorContext();
        ConnectorContext context = (ConnectorContext) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ConnectorContext.class},
                (proxy, method, args) -> method.getName().equals("getCacheFactory")
                        ? (ConnectorCacheFactory) requirements -> Optional.of(blobCache)
                        : method.invoke(delegate, args));
        assertThat(context.getNodeManager().getCurrentNode().isCoordinator()).isTrue();
        try {
        Connector connector = new TrinoConnectorFactory().create(
                "paimon_cache_test", Map.of(
                        "warehouse", warehouse.toUri().toString(),
                        "fs.local.enabled", "true", "local.location", "/",
                        "fs.cache.enabled", "true"), context);
        assertThat(connector).isNotNull();
        connector.shutdown();
        } finally { disk.shutdown(); }
    }
}
