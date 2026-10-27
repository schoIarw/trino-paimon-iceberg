/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino;
import io.trino.testing.TestingConnectorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
public class TrinoNativeCacheFactoryTest {
    @TempDir Path root;
    @Test public void startsWithoutTrinoCacheManager() {
        var c = new TrinoConnectorFactory().create("native_cache", Map.of(
                "warehouse", root.resolve("warehouse").toUri().toString(),
                "local-cache.enabled", "true", "local-cache.dir", root.resolve("cache").toString(),
                "local-cache.max-size", "1 mb", "fs.cache.enabled", "false"), new TestingConnectorContext());
        assertThat(c).isNotNull(); c.shutdown();
    }
    @Test public void rejectsDoubleCachingAndUnboundedCache() {
        assertThatThrownBy(() -> new TrinoConnectorFactory().create("native", Map.of(
                "warehouse", root.toString(), "fs.cache.enabled", "true"), new TestingConnectorContext()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fs.cache.enabled");
        assertThatThrownBy(() -> new TrinoConnectorFactory().create("native", Map.of(
                "warehouse", root.toString(), "local-cache.enabled", "true"), new TestingConnectorContext()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("local-cache.max-size");
    }
}
