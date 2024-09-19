/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.connectors.base.source.assigner.splitter;

import org.apache.flink.cdc.common.annotation.Experimental;
import org.apache.flink.cdc.connectors.base.config.JdbcSourceConfig;
import org.apache.flink.cdc.connectors.base.dialect.JdbcDataSourceDialect;
import org.apache.flink.cdc.connectors.base.source.meta.split.SnapshotSplit;
import org.apache.flink.cdc.connectors.base.source.utils.JdbcChunkUtils;
import org.apache.flink.cdc.connectors.base.utils.ObjectUtils;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.util.FlinkRuntimeException;

import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.history.TableChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.math.BigDecimal.ROUND_CEILING;
import static org.apache.flink.cdc.connectors.base.utils.ObjectUtils.doubleCompare;
import static org.apache.flink.table.api.DataTypes.FIELD;
import static org.apache.flink.table.api.DataTypes.ROW;

/** The {@code ChunkSplitter} used to split table into a set of chunks for JDBC data source. */
@Experimental
public abstract class JdbcSourceChunkSplitter implements ChunkSplitter {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcSourceChunkSplitter.class);
    protected final JdbcSourceConfig sourceConfig;
    protected final JdbcDataSourceDialect dialect;

    public JdbcSourceChunkSplitter(JdbcSourceConfig sourceConfig, JdbcDataSourceDialect dialect) {
        this.sourceConfig = sourceConfig;
        this.dialect = dialect;
    }

    /** Generates all snapshot splits (chunks) for the give table path. */
    @Override
    public Collection<SnapshotSplit> generateSplits(TableId tableId) {
        try (JdbcConnection jdbc = dialect.openJdbcConnection(sourceConfig)) {

            LOG.info("Start splitting table {} into chunks...", tableId);
            long start = System.currentTimeMillis();

            Table table =
                    Objects.requireNonNull(dialect.queryTableSchema(jdbc, tableId)).getTable();
            Column splitColumn = getSplitColumn(table, sourceConfig.getChunkKeyColumn());
            final List<ChunkRange> chunks;
            try {
                chunks = splitTableIntoChunks(jdbc, tableId, splitColumn);
            } catch (SQLException e) {
                throw new FlinkRuntimeException("Failed to split chunks for table " + tableId, e);
            }

            // convert chunks into splits
            List<SnapshotSplit> splits = new ArrayList<>();
            RowType splitType = getSplitType(splitColumn);
            for (int i = 0; i < chunks.size(); i++) {
                ChunkRange chunk = chunks.get(i);
                SnapshotSplit split =
                        createSnapshotSplit(
                                jdbc,
                                tableId,
                                i,
                                splitType,
                                chunk.getChunkStart(),
                                chunk.getChunkEnd());
                splits.add(split);
            }

            long end = System.currentTimeMillis();
            LOG.info(
                    "Split table {} into {} chunks, time cost: {}ms.",
                    tableId,
                    splits.size(),
                    end - start);
            return splits;
        } catch (Exception e) {
            throw new FlinkRuntimeException(
                    String.format("Generate Splits for table %s error", tableId), e);
        }
    }

    /**
     * Query the maximum value of the next chunk, and the next chunk must be greater than or equal
     * to <code>includedLowerBound</code> value [min_1, max_1), [min_2, max_2),... [min_n, null).
     * Each time this method is called it will return max1, max2...
     *
     * <p>Each database has different grammar to get limit number of data, for example, `limit N` in
     * mysql or postgres, `top(N)` in sqlserver , `FETCH FIRST %S ROWS ONLY` in DB2.
     *
     * @param jdbc JDBC connection.
     * @param tableId table identity.
     * @param splitColumn column.
     * @param chunkSize chunk size.
     * @param includedLowerBound the previous chunk end value.
     * @return next chunk end value.
     */
    protected abstract Object queryNextChunkMax(
            JdbcConnection jdbc,
            TableId tableId,
            Column splitColumn,
            int chunkSize,
            Object includedLowerBound)
            throws SQLException;

    /**
     * Approximate total number of entries in the lookup table.
     *
     * <p>Each database has different system table to lookup up approximate total number. For
     * example, `pg_class` in postgres, `sys.dm_db_partition_stats` in sqlserver, `SYSCAT.TABLE` in
     * db2.
     *
     * @param jdbc JDBC connection.
     * @param tableId table identity.
     * @return approximate row count.
     */
    protected abstract Long queryApproximateRowCnt(JdbcConnection jdbc, TableId tableId)
            throws SQLException;

    /**
     * Checks whether split column is evenly distributed across its range.
     *
     * @param splitColumn split column.
     * @return true that means split column with type BIGINT, INT, DECIMAL.
     */
    protected boolean isEvenlySplitColumn(Column splitColumn) {
        DataType flinkType = fromDbzColumn(splitColumn);
        LogicalTypeRoot typeRoot = flinkType.getLogicalType().getTypeRoot();

        // currently, we only support the optimization that split column with type BIGINT, INT,
        // DECIMAL
        return typeRoot == LogicalTypeRoot.BIGINT
                || typeRoot == LogicalTypeRoot.INTEGER
                || typeRoot == LogicalTypeRoot.DECIMAL;
    }

    /**
     * Get a corresponding Flink data type from a debezium {@link Column}.
     *
     * @param splitColumn dbz split column.
     * @return flink data type
     */
    protected abstract DataType fromDbzColumn(Column splitColumn);

    /** Returns the distribution factor of the given table. */
    protected double calculateDistributionFactor(
            TableId tableId, Object min, Object max, long approximateRowCnt) {

        if (!min.getClass().equals(max.getClass())) {
            throw new IllegalStateException(
                    String.format(
                            "Unsupported operation type, the MIN value type %s is different with MAX value type %s.",
                            min.getClass().getSimpleName(), max.getClass().getSimpleName()));
        }
        if (approximateRowCnt == 0) {
            return Double.MAX_VALUE;
        }
        BigDecimal difference = ObjectUtils.minus(max, min);
        // factor = (max - min + 1) / rowCount
        final BigDecimal subRowCnt = difference.add(BigDecimal.valueOf(1));
        double distributionFactor =
                subRowCnt.divide(new BigDecimal(approximateRowCnt), 4, ROUND_CEILING).doubleValue();
        LOG.info(
                "The distribution factor of table {} is {} according to the min split key {}, max split key {} and approximate row count {}",
                tableId,
                distributionFactor,
                min,
                max,
                approximateRowCnt);
        return distributionFactor;
    }

    /**
     * Get the column which is seen as chunk key.
     *
     * @param table table identity.
     * @param chunkKeyColumn column name which is seen as chunk key, if chunkKeyColumn is null, use
     *     primary key instead. @Column the column which is seen as chunk key.
     */
    protected Column getSplitColumn(Table table, @Nullable String chunkKeyColumn) {
        return JdbcChunkUtils.getSplitColumn(table, chunkKeyColumn);
    }

    /** ChunkEnd less than or equal to max. */
    protected boolean isChunkEndLeMax(Object chunkEnd, Object max, Column splitColumn) {
        return ObjectUtils.compare(chunkEnd, max) <= 0;
    }

    /** ChunkEnd greater than or equal to max. */
    protected boolean isChunkEndGeMax(Object chunkEnd, Object max, Column splitColumn) {
        return ObjectUtils.compare(chunkEnd, max) >= 0;
    }

    /**
     * Query the maximum and minimum value of the column in the table. e.g. query string <code>
     * SELECT MIN(%s) FROM %s WHERE %s > ?</code>
     *
     * @param jdbc JDBC connection.
     * @param tableId table identity.
     * @param splitColumn column.
     * @return maximum and minimum value.
     */
    protected Object[] queryMinMax(JdbcConnection jdbc, TableId tableId, Column splitColumn)
            throws SQLException {
        return JdbcChunkUtils.queryMinMax(
                jdbc,
                jdbc.quotedTableIdString(tableId),
                jdbc.quotedColumnIdString(splitColumn.name()));
    }

    /**
     * Query the minimum value of the column in the table, and the minimum value must greater than
     * the excludedLowerBound value. e.g. prepare query string <code>
     * SELECT MIN(%s) FROM %s WHERE %s > ?</code>
     *
     * @param jdbc JDBC connection.
     * @param tableId table identity.
     * @param splitColumn column.
     * @param excludedLowerBound the minimum value should be greater than this value.
     * @return minimum value.
     */
    protected Object queryMin(
            JdbcConnection jdbc, TableId tableId, Column splitColumn, Object excludedLowerBound)
            throws SQLException {
        return JdbcChunkUtils.queryMin(
                jdbc,
                jdbc.quotedColumnIdString(splitColumn.name()),
                jdbc.quotedTableIdString(tableId),
                excludedLowerBound);
    }

    /**
     * convert dbz column to Flink row type.
     *
     * @param splitColumn split column.
     * @return flink row type.
     */
    private RowType getSplitType(Column splitColumn) {
        return (RowType)
                ROW(FIELD(splitColumn.name(), fromDbzColumn(splitColumn))).getLogicalType();
    }

    /**
     * We can use evenly-sized chunks or unevenly-sized chunks when split table into chunks, using
     * evenly-sized chunks which is much efficient, using unevenly-sized chunks which will request
     * many queries and is not efficient.
     */
    private List<ChunkRange> splitTableIntoChunks(
            JdbcConnection jdbc, TableId tableId, Column splitColumn) throws SQLException {
        final Object[] minMax = queryMinMax(jdbc, tableId, splitColumn);
        final Object min = minMax[0];
        final Object max = minMax[1];
        if (min == null || max == null || min.equals(max)) {
            // empty table, or only one row, return full table scan as a chunk
            return Collections.singletonList(ChunkRange.all());
        }

        final int chunkSize = sourceConfig.getSplitSize();
        final double distributionFactorUpper = sourceConfig.getDistributionFactorUpper();
        final double distributionFactorLower = sourceConfig.getDistributionFactorLower();

        if (isEvenlySplitColumn(splitColumn)) {
            long approximateRowCnt = queryApproximateRowCnt(jdbc, tableId);
            double distributionFactor =
                    calculateDistributionFactor(tableId, min, max, approximateRowCnt);

            boolean dataIsEvenlyDistributed =
                    doubleCompare(distributionFactor, distributionFactorLower) >= 0
                            && doubleCompare(distributionFactor, distributionFactorUpper) <= 0;

            if (dataIsEvenlyDistributed) {
                // the minimum dynamic chunk size is at least 1
                final int dynamicChunkSize = Math.max((int) (distributionFactor * chunkSize), 1);
                return splitEvenlySizedChunks(
                        tableId, min, max, approximateRowCnt, chunkSize, dynamicChunkSize);
            } else {
                return splitUnevenlySizedChunks(jdbc, tableId, splitColumn, min, max, chunkSize);
            }
        } else {
            return splitUnevenlySizedChunks(jdbc, tableId, splitColumn, min, max, chunkSize);
        }
    }

    /**
     * Split table into evenly sized chunks based on the numeric min and max value of split column,
     * and tumble chunks in step size.
     */
    private List<ChunkRange> splitEvenlySizedChunks(
            TableId tableId,
            Object min,
            Object max,
            long approximateRowCnt,
            int chunkSize,
            int dynamicChunkSize) {
        LOG.info(
                "Use evenly-sized chunk optimization for table {}, the approximate row count is {}, the chunk size is {}, the dynamic chunk size is {}",
                tableId,
                approximateRowCnt,
                chunkSize,
                dynamicChunkSize);
        if (approximateRowCnt <= chunkSize) {
            // there is no more than one chunk, return full table as a chunk
            return Collections.singletonList(ChunkRange.all());
        }

        final List<ChunkRange> splits = new ArrayList<>();
        Object chunkStart = null;
        Object chunkEnd = ObjectUtils.plus(min, dynamicChunkSize);
        while (ObjectUtils.compare(chunkEnd, max) <= 0) {
            splits.add(ChunkRange.of(chunkStart, chunkEnd));
            chunkStart = chunkEnd;
            try {
                chunkEnd = ObjectUtils.plus(chunkEnd, dynamicChunkSize);
            } catch (ArithmeticException e) {
                // Stop chunk split to avoid dead loop when number overflows.
                break;
            }
        }
        // add the ending split
        splits.add(ChunkRange.of(chunkStart, null));
        return splits;
    }

    /** Split table into unevenly sized chunks by continuously calculating next chunk max value. */
    private List<ChunkRange> splitUnevenlySizedChunks(
            JdbcConnection jdbc,
            TableId tableId,
            Column splitColumn,
            Object min,
            Object max,
            int chunkSize)
            throws SQLException {
        LOG.info(
                "Use unevenly-sized chunks for table {}, the chunk size is {}", tableId, chunkSize);
        final List<ChunkRange> splits = new ArrayList<>();
        Object chunkStart = null;
        Object chunkEnd = nextChunkEnd(jdbc, min, tableId, splitColumn, max, chunkSize);
        int count = 0;
        while (chunkEnd != null && isChunkEndLeMax(chunkEnd, max, splitColumn)) {
            // we start from [null, min + chunk_size) and avoid [null, min)
            splits.add(ChunkRange.of(chunkStart, chunkEnd));
            // may sleep a while to avoid DDOS on PostgreSQL server
            maySleep(count++, tableId);
            chunkStart = chunkEnd;
            chunkEnd = nextChunkEnd(jdbc, chunkEnd, tableId, splitColumn, max, chunkSize);
        }
        // add the ending split
        splits.add(ChunkRange.of(chunkStart, null));
        return splits;
    }

    private Object nextChunkEnd(
            JdbcConnection jdbc,
            Object previousChunkEnd,
            TableId tableId,
            Column splitColumn,
            Object max,
            int chunkSize)
            throws SQLException {
        // chunk end might be null when max values are removed
        Object chunkEnd =
                queryNextChunkMax(jdbc, tableId, splitColumn, chunkSize, previousChunkEnd);
        if (Objects.equals(previousChunkEnd, chunkEnd)) {
            // we don't allow equal chunk start and end,
            // should query the next one larger than chunkEnd
            chunkEnd = queryMin(jdbc, tableId, splitColumn, chunkEnd);
        }
        if (isChunkEndGeMax(chunkEnd, max, splitColumn)) {
            return null;
        } else {
            return chunkEnd;
        }
    }

    private SnapshotSplit createSnapshotSplit(
            JdbcConnection jdbc,
            TableId tableId,
            int chunkId,
            RowType splitKeyType,
            Object chunkStart,
            Object chunkEnd) {
        // currently, we only support single split column
        Object[] splitStart = chunkStart == null ? null : new Object[] {chunkStart};
        Object[] splitEnd = chunkEnd == null ? null : new Object[] {chunkEnd};
        Map<TableId, TableChanges.TableChange> schema = new HashMap<>();
        schema.put(tableId, dialect.queryTableSchema(jdbc, tableId));
        return new SnapshotSplit(
                tableId,
                splitId(tableId, chunkId),
                splitKeyType,
                splitStart,
                splitEnd,
                null,
                schema);
    }

    private String splitId(TableId tableId, int chunkId) {
        return tableId.toString() + ":" + chunkId;
    }

    private void maySleep(int count, TableId tableId) {
        // every 10 queries to sleep 0.1s
        if (count % 10 == 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // nothing to do
            }
            LOG.info("JdbcSourceChunkSplitter has split {} chunks for table {}", count, tableId);
        }
    }
}
