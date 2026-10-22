/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino.cache;

import io.trino.filesystem.TrinoInputFile;
import io.trino.filesystem.cache.CacheKeyProvider;
import io.trino.filesystem.cache.DefaultCacheKeyProvider;
import io.trino.spi.cache.CacheKey;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Cache only generated immutable data objects; unknown paths fail closed to remote reads. */
public final class PaimonCacheKeyProvider implements CacheKeyProvider {
    private static final Set<String> METADATA_DIRECTORIES = Set.of(
            "snapshot", "schema", "tag", "branch", "consumer", "manifest", "statistics");
    private static final Pattern DATA_NAME = Pattern.compile(
            "(?:data-|changelog-)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-[0-9]+\\.[A-Za-z0-9.]+"
    );
    private final DefaultCacheKeyProvider delegate = new DefaultCacheKeyProvider();

    @Override
    public Optional<CacheKey> getCacheKey(TrinoInputFile inputFile) throws IOException {
        String path = inputFile.location().path();
        for (String segment : path.split("/")) {
            if (METADATA_DIRECTORIES.contains(segment)) {
                return Optional.empty();
            }
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!DATA_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        // Preserve scheme, bucket, path, modification time and length from Trino 484.
        return delegate.getCacheKey(inputFile);
    }
}
