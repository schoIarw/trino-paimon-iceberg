/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino.cache;

import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.Split;
import io.trino.filesystem.cache.SplitAffinityProvider;

import java.util.Comparator;
import java.util.Optional;

/** Anchor a multi-file split to its largest file, independently of snapshot and file ordering. */
public final class PaimonSplitAffinity {
    private PaimonSplitAffinity() {}

    private record File(String path, long size) {}

    public static Optional<String> key(Split split, SplitAffinityProvider provider) {
        if (!(split instanceof DataSplit data)) {
            return Optional.empty();
        }
        return data.dataFiles().stream()
                .map(file -> new File(file.externalPath().orElse(data.bucketPath() + "/" + file.fileName()), file.fileSize()))
                .max(Comparator.comparingLong(File::size).thenComparing(File::path))
                .flatMap(file -> provider.getKey(file.path(), 0, file.size()));
    }
}
