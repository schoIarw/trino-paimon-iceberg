/* Licensed under the Apache License, Version 2.0. */
package org.apache.paimon.trino;

import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.options.Options;
import java.util.List;
import java.util.Map;

/** Creates isolated ORC/Parquet and primary-key test tables; never targets production. */
public final class NativeCacheFixture {
    public static void main(String[] args) throws Exception {
        String warehouse = java.nio.file.Path.of(args[0]).toUri().toString();
        boolean update = args.length > 1 && args[1].equals("update");
        try (var catalog = CatalogFactory.createCatalog(CatalogContext.create(
                Options.fromMap(Map.of("warehouse", warehouse))))) {
            catalog.createDatabase("cachetest", true);
            for (String name : List.of("orc_scan", "parquet_scan", "pk_merge")) {
                Identifier id = Identifier.create("cachetest", name);
                if (!update) {
                    catalog.createTable(id, new Schema(List.of(
                            new DataField(0, "id", DataTypes.INT().notNull()),
                            new DataField(1, "payload", DataTypes.STRING())),
                            List.of(), name.equals("pk_merge") ? List.of("id") : List.of(),
                            Map.of("file.format", name.equals("parquet_scan") ? "parquet" : "orc",
                                    "bucket", name.equals("pk_merge") ? "1" : "-1"), "cache fixture"), false);
                }
                FileStoreTable table = (FileStoreTable) catalog.getTable(id);
                try (var writer = table.newWrite("fixture"); var commit = table.newCommit("fixture")) {
                    int begin = update ? 2000 : 0;
                    for (int i = begin; i < begin + (update ? 10 : 2000); i++) {
                        writer.write(GenericRow.of(i, BinaryString.fromString("payload-" + i + "-" + "x".repeat(200))));
                    }
                    // A second commit exercises PK merging across files and new snapshot visibility.
                    if (update && name.equals("pk_merge")) {
                        writer.write(GenericRow.of(0, BinaryString.fromString("updated")));
                        writer.write(GenericRow.ofKind(org.apache.paimon.types.RowKind.DELETE,
                                1, BinaryString.fromString("unused")));
                    }
                    commit.commit(update ? 1 : 0, writer.prepareCommit(true, update ? 1 : 0));
                }
            }
        }
    }
}
