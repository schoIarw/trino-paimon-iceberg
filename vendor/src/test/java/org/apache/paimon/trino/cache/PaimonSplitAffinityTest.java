/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino.cache;

import org.apache.paimon.trino.TrinoSplit;
import io.airlift.json.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class PaimonSplitAffinityTest {
    @Test
    public void splitJsonPreservesAffinityAndAccountsMemory() {
        var codec = JsonCodec.jsonCodec(TrinoSplit.class);
        var original = new TrinoSplit("serialized", 0.2, "s3://bucket/file:0:100");
        var result = codec.fromJson(codec.toJson(original));
        assertThat(result.getAffinityKey()).contains("s3://bucket/file:0:100");
        assertThat(result.getRetainedSizeInBytes()).isGreaterThan(20L);
        assertThat(new TrinoSplit("serialized", 0.2).getAffinityKey()).isEmpty();
    }
}
