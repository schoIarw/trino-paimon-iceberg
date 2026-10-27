/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.trino;

import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.trino.catalog.TrinoCatalog;
import org.apache.paimon.types.RowType;

import com.google.inject.Inject;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableCredentials;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.predicate.TupleDomain;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.apache.paimon.trino.ClassLoaderUtils.runWithContextClassLoader;

/** Trino {@link ConnectorPageSourceProvider}. */
public class TrinoPageSourceProvider implements ConnectorPageSourceProvider {

    private final TrinoCatalog trinoCatalog;

    @Inject
    public TrinoPageSourceProvider(
            TrinoMetadataFactory trinoMetadataFactory) {
        this.trinoCatalog =
                requireNonNull(trinoMetadataFactory, "trinoMetadataFactory is null")
                        .create()
                        .catalog();
    }

    @Override
    public ConnectorPageSource createPageSource(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorSplit split,
            ConnectorTableHandle tableHandle,
            Optional<ConnectorTableCredentials> tableCredentials,
            List<ColumnHandle> columns,
            DynamicFilter dynamicFilter) {
        trinoCatalog.initSession(session);
        TrinoTableHandle trinoTableHandle = (TrinoTableHandle) tableHandle;
        Table table = trinoTableHandle.tableWithDynamicOptions(trinoCatalog, session);
        return runWithContextClassLoader(
                () -> {
                    Optional<TrinoColumnHandle> rowId =
                            columns.stream()
                                    .map(TrinoColumnHandle.class::cast)
                                    .filter(column -> column.isRowId())
                                    .findFirst();
                    if (rowId.isPresent()) {
                        List<ColumnHandle> dataColumns =
                                columns.stream()
                                        .map(TrinoColumnHandle.class::cast)
                                        .filter(column -> !column.isRowId())
                                        .collect(Collectors.toList());
                        Set<String> rowIdFileds =
                                ((io.trino.spi.type.RowType) rowId.get().getTrinoType())
                                        .getFields().stream()
                                                .map(io.trino.spi.type.RowType.Field::getName)
                                                .map(Optional::get)
                                                .collect(Collectors.toSet());

                        HashMap<String, Integer> fieldToIndex = new HashMap<>();
                        for (int i = 0; i < dataColumns.size(); i++) {
                            TrinoColumnHandle trinoColumnHandle =
                                    (TrinoColumnHandle) dataColumns.get(i);
                            if (rowIdFileds.contains(trinoColumnHandle.getColumnName())) {
                                fieldToIndex.put(trinoColumnHandle.getColumnName(), i);
                            }
                        }
                        return TrinoMergePageSourceWrapper.wrap(
                                createPageSource(
                                        session,
                                        table,
                                        trinoTableHandle.getFilter(),
                                        (TrinoSplit) split,
                                        dataColumns,
                                        trinoTableHandle.getLimit()),
                                fieldToIndex);
                    } else {
                        return createPageSource(
                                session,
                                table,
                                trinoTableHandle.getFilter(),
                                (TrinoSplit) split,
                                columns,
                                trinoTableHandle.getLimit());
                    }
                },
                TrinoPageSourceProvider.class.getClassLoader());
    }

    private ConnectorPageSource createPageSource(
            ConnectorSession session,
            Table table,
            TupleDomain<TrinoColumnHandle> filter,
            TrinoSplit split,
            List<ColumnHandle> columns,
            OptionalLong limit) {
        RowType rowType = table.rowType();
        List<String> fieldNames = rowType.getFieldNames();
        List<String> projectedFields =
                columns.stream()
                        .map(TrinoColumnHandle.class::cast)
                        .map(TrinoColumnHandle::getColumnName)
                        .toList();
        Optional<Predicate> paimonFilter = new TrinoFilterConverter(rowType).convert(filter);
        // Do not bypass Paimon's CachingFileIO through Trino's ORC raw reader.
        // Keep this path identical when the cache is disabled for fair A/B tests.
        try {
            return createLegacyPageSource(table, paimonFilter, fieldNames, projectedFields,
                    split.decodeSplit(), columns, limit);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ConnectorPageSource createLegacyPageSource(
            Table table,
            Optional<Predicate> paimonFilter,
            List<String> fieldNames,
            List<String> projectedFields,
            Split paimonSplit,
            List<ColumnHandle> columns,
            OptionalLong limit) {
        int[] columnIndex = projectedFields.stream().mapToInt(fieldNames::indexOf).toArray();
        ReadBuilder read = table.newReadBuilder();
        paimonFilter.ifPresent(read::withFilter);

        if (!fieldNames.equals(projectedFields)) {
            read.withProjection(columnIndex);
        }

        try {
            return new TrinoPageSource(
                    read.newRead().executeFilter().createReader(paimonSplit), columns, limit);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
