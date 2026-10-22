/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino.cache;

import io.airlift.units.DataSize;
import io.opentelemetry.api.OpenTelemetry;
import io.trino.blob.cache.alluxio.AlluxioCache;
import io.trino.blob.cache.alluxio.AlluxioCacheConfig;
import io.trino.blob.cache.alluxio.AlluxioCacheStats;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.cache.CacheFileSystem;
import io.trino.filesystem.memory.MemoryFileSystem;
import io.trino.spi.cache.Blob;
import io.trino.spi.cache.BlobCache;
import io.trino.spi.cache.BlobSource;
import io.trino.spi.cache.CacheKey;
import org.apache.paimon.trino.fileio.TrinoFileIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class PaimonDiskCacheTest {
    public static final String DATA = "data-12345678-1234-1234-1234-123456789abc-0.parquet";
    @TempDir Path directory;

    public static BlobCache adapter(AlluxioCache cache, AlluxioCacheStats stats) {
        return new BlobCache() {
            public Optional<Blob> get(CacheKey key, BlobSource source) throws IOException {
                return cache.get(key, source, stats);
            }
            public void tryInvalidate(CacheKey key) { cache.tryInvalidate(key); }
        };
    }

    public static AlluxioCache createCache(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new AlluxioCache(OpenTelemetry.noop().getTracer("test"), new AlluxioCacheConfig()
                .setCacheDirectories(List.of(directory.toString()))
                .setMaxCacheSizes(List.of(DataSize.valueOf("16MB")))
                .setCachePageSize(DataSize.valueOf("64kB")));
    }

    private static byte[] read(TrinoFileSystem fs, Location location) throws IOException {
        try (var stream = fs.newInputFile(location).newStream()) { return stream.readAllBytes(); }
    }

    private static void put(TrinoFileSystem fs, Location location, byte[] bytes) throws IOException {
        fs.deleteFile(location);
        try (var stream = fs.newOutputFile(location).create()) { stream.write(bytes); }
    }

    @Test
    public void hotReadsUseDiskAndMutableHintsBypassIt() throws Exception {
        MemoryFileSystem remote = new MemoryFileSystem();
        AlluxioCacheStats stats = new AlluxioCacheStats();
        AlluxioCache cache = createCache(directory.resolve("pages"));
        try {
            TrinoFileSystem fs = new CacheFileSystem(remote, adapter(cache, stats), new PaimonCacheKeyProvider());
            Location data = Location.of("memory:///warehouse/table/bucket-0/" + DATA);
            byte[] expected = new byte[200_000];
            Arrays.fill(expected, (byte) 37);
            put(remote, data, expected);
            assertThat(read(fs, data)).isEqualTo(expected);
            double coldExternal = stats.getExternalReads().getAllTime().getTotal();
            assertThat(coldExternal).isPositive();
            assertThat(read(fs, data)).isEqualTo(expected);
            assertThat(stats.getExternalReads().getAllTime().getTotal()).isEqualTo(coldExternal);
            assertThat(stats.getCacheReads().getAllTime().getTotal()).isPositive();
            try (var paths = Files.walk(directory)) {
                assertThat(paths.anyMatch(Files::isRegularFile)).isTrue();
            }
            // Exercise the Paimon fallback/merge IO adapter as well as direct Trino reads.
            var io = new TrinoFileIO(fs, null);
            try (var stream = io.newInputStream(new org.apache.paimon.fs.Path(data.toString()))) {
                stream.seek(100_000);
                assertThat(stream.read()).isEqualTo(37);
            }
            assertThat(stats.getExternalReads().getAllTime().getTotal()).isEqualTo(coldExternal);
            try (var pool = Executors.newFixedThreadPool(4)) {
                var tasks = IntStream.range(0, 12).<java.util.concurrent.Callable<byte[]>>mapToObj(i -> () -> read(fs, data)).toList();
                for (var result : pool.invokeAll(tasks)) { assertThat(result.get()).isEqualTo(expected); }
            }
            assertThat(stats.getExternalReads().getAllTime().getTotal()).isEqualTo(coldExternal);

            Location hint = Location.of("memory:///warehouse/table/snapshot/LATEST");
            double beforeHints = stats.getCacheReads().getAllTime().getTotal();
            put(remote, hint, new byte[] {49});
            assertThat(read(fs, hint)).containsExactly((byte) 49);
            put(remote, hint, new byte[] {50});
            assertThat(read(fs, hint)).containsExactly((byte) 50);
            assertThat(stats.getCacheReads().getAllTime().getTotal()).isEqualTo(beforeHints);

            // An unexpected replacement with a changed length must not reuse old data pages.
            put(remote, data, new byte[] {7, 8, 9});
            assertThat(read(fs, data)).containsExactly((byte) 7, (byte) 8, (byte) 9);
        } finally { cache.shutdown(); }
    }

    @Test
    public void metadataAndUnknownFileNamesAreNotCached() throws Exception {
        var fs = new MemoryFileSystem();
        var keys = new PaimonCacheKeyProvider();
        for (String path : List.of("snapshot/LATEST", "snapshot/EARLIEST", "snapshot/snapshot-1",
                "schema/schema-0", "tag/production", "manifest/manifest-abc", "bucket-0/custom.parquet")) {
            assertThat(keys.getCacheKey(fs.newInputFile(Location.of("memory:///" + path)))).isEmpty();
        }
    }

    @Test
    public void bucketTimeAndLengthSeparateCacheEntries() throws Exception {
        var keys = new PaimonCacheKeyProvider();
        var first = keys.getCacheKey(input("s3://a/" + DATA, 10, 1));
        assertThat(first).isPresent();
        assertThat(keys.getCacheKey(input("s3://b/" + DATA, 10, 1))).isNotEqualTo(first);
        assertThat(keys.getCacheKey(input("s3://a/" + DATA, 10, 2))).isNotEqualTo(first);
        assertThat(keys.getCacheKey(input("s3://a/" + DATA, 11, 1))).isNotEqualTo(first);
        assertThat(keys.getCacheKey(input("s3://a/tag/" + DATA, 10, 1))).isEmpty();
    }

    private static io.trino.filesystem.TrinoInputFile input(String path, long size, long seconds) {
        return new io.trino.filesystem.TrinoInputFile() {
            public Location location() { return Location.of(path); }
            public long length() { return size; }
            public java.time.Instant lastModified() { return java.time.Instant.ofEpochSecond(seconds); }
            public boolean exists() { return true; }
            public io.trino.filesystem.TrinoInput newInput() { throw new UnsupportedOperationException(); }
            public io.trino.filesystem.TrinoInputStream newStream() { throw new UnsupportedOperationException(); }
        };
    }
}
