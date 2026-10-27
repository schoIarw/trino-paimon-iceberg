/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino.cache;

import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.SeekableInputStream;
import org.apache.paimon.fs.cache.CachingFileIO;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.options.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests the actual Paimon bundle cache, not a replacement cache. */
public class PaimonNativeCacheTest {
    @TempDir java.nio.file.Path root;
    private final AtomicInteger opens = new AtomicInteger();

    private FileIO cache(String whitelist, String directory, String size) {
        FileIO delegate = LocalFileIO.create();
        FileIO counted = (FileIO) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {FileIO.class}, (proxy, method, args) -> {
                    if (method.getName().equals("newInputStream")) opens.incrementAndGet();
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        Options options = Options.fromMap(Map.of("local-cache.enabled", "true",
                "local-cache.dir", directory, "local-cache.max-size", size,
                "local-cache.block-size", "1 kb", "local-cache.whitelist", whitelist));
        CatalogContext context = CatalogContext.create(options);
        return CachingFileIO.wrapWithCachingIfNeeded(counted, context,
                CachingFileIO.createCacheManager(context));
    }

    private Path write(String relative, byte[] data) throws Exception {
        java.nio.file.Path p = root.resolve(relative);
        Files.createDirectories(p.getParent()); Files.write(p, data);
        return new Path(p.toUri());
    }
    private byte[] read(FileIO io, Path p) throws Exception {
        try (SeekableInputStream in = io.newInputStream(p)) { return in.readAllBytes(); }
    }
    @Test public void coldHotSeekConcurrentAndRestart() throws Exception {
        byte[] data = new byte[2500]; Arrays.fill(data, (byte) 37);
        Path p = write("table/bucket-0/data-abc.orc", data);
        String directory = root.resolve("cache").toString();
        try (FileIO io = cache("data", directory, "16 kb")) {
            assertThat(io).isInstanceOf(CachingFileIO.class);
            assertThat(read(io, p)).isEqualTo(data);
            int cold = opens.get(); assertThat(cold).isPositive();
            assertThat(read(io, p)).isEqualTo(data); assertThat(opens.get()).isEqualTo(cold);
            try (SeekableInputStream in = io.newInputStream(p)) {
                in.seek(1300); assertThat(in.read()).isEqualTo(37);
            }
            try (var pool = Executors.newFixedThreadPool(4)) {
                var jobs = java.util.stream.IntStream.range(0, 12)
                        .<java.util.concurrent.Callable<byte[]>>mapToObj(i -> () -> read(io, p)).toList();
                for (var f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(data);
            }
            assertThat(opens.get()).isEqualTo(cold);
        }
        int before = opens.get();
        try (FileIO restarted = cache("data", directory, "16 kb")) {
            assertThat(read(restarted, p)).isEqualTo(data);
            assertThat(opens.get()).isEqualTo(before);
        }
        try (var files = Files.walk(root.resolve("cache"))) {
            assertThat(files.anyMatch(Files::isRegularFile)).isTrue();
        }
    }
    @Test public void mutableHintsAndNonWhitelistedFilesBypass() throws Exception {
        Path hint = write("table/snapshot/LATEST", new byte[] {1});
        Path data = write("table/bucket-0/data-a.orc", new byte[] {3});
        try (FileIO io = cache("meta", root.resolve("cache").toString(), "16 kb")) {
            assertThat(read(io, hint)).containsExactly((byte) 1);
            write("table/snapshot/LATEST", new byte[] {2});
            assertThat(read(io, hint)).containsExactly((byte) 2);
            assertThat(read(io, data)).containsExactly((byte) 3);
            assertThat(read(io, data)).containsExactly((byte) 3);
            assertThat(opens.get()).isEqualTo(4);
        }
    }
    @Test public void lruEvictsAndChangedLengthDoesNotReuseOldBlocks() throws Exception {
        Path a = write("table/bucket-0/data-a.orc", new byte[1024]);
        Path b = write("table/bucket-0/data-b.orc", new byte[1024]);
        try (FileIO io = cache("data", root.resolve("cache").toString(), "1 kb")) {
            read(io, a); read(io, b); int before = opens.get();
            read(io, a); assertThat(opens.get()).isGreaterThan(before);
            write("table/bucket-0/data-a.orc", new byte[] {9, 8});
            assertThat(read(io, a)).containsExactly((byte) 9, (byte) 8);
        }
    }
}
