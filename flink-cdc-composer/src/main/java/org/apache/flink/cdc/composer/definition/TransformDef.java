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

package org.apache.flink.cdc.composer.definition;

import org.apache.flink.cdc.common.utils.StringUtils;

import java.util.Objects;
import java.util.Optional;

/**
 * Definition of a transformation.
 *
 * <p>A transformation definition contains:
 *
 * <ul>
 *   <li>sourceTable: a regex pattern for matching input table IDs. Required for the definition.
 *   <li>projection: a string for projecting the row of matched table as output. Optional for the
 *       definition.
 *   <li>filter: a string for filtering the row of matched table as output. Optional for the
 *       definition.
 *   <li>primaryKeys: a string for primary key columns for matching input table IDs, seperated by
 *       `,`. Optional for the definition.
 *   <li>partitionKeys: a string for partition key columns for matching input table IDs, seperated
 *       by `,`. Optional for the definition.
 *   <li>tableOptions: a string for table options for matching input table IDs, options are
 *       seperated by `,`, key and value are seperated by `=`. Optional for the definition.
 *   <li>description: description for the transformation. Optional for the definition.
 * </ul>
 */
public class TransformDef {
    private final String sourceTable;
    private final String projection;
    private final String filter;
    private final String description;
    private final String primaryKeys;
    private final String partitionKeys;
    private final String tableOptions;

    public TransformDef(
            String sourceTable,
            String projection,
            String filter,
            String primaryKeys,
            String partitionKeys,
            String tableOptions,
            String description) {
        this.sourceTable = sourceTable;
        this.projection = projection;
        this.filter = filter;
        this.primaryKeys = primaryKeys;
        this.partitionKeys = partitionKeys;
        this.tableOptions = tableOptions;
        this.description = description;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public Optional<String> getProjection() {
        return Optional.ofNullable(projection);
    }

    public boolean isValidProjection() {
        return !StringUtils.isNullOrWhitespaceOnly(projection);
    }

    public Optional<String> getFilter() {
        return Optional.ofNullable(filter);
    }

    public boolean isValidFilter() {
        return !StringUtils.isNullOrWhitespaceOnly(filter);
    }

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    public String getPrimaryKeys() {
        return primaryKeys;
    }

    public String getPartitionKeys() {
        return partitionKeys;
    }

    public String getTableOptions() {
        return tableOptions;
    }

    @Override
    public String toString() {
        return "TransformDef{"
                + "sourceTable='"
                + sourceTable
                + '\''
                + ", projection='"
                + projection
                + '\''
                + ", filter='"
                + filter
                + '\''
                + ", description='"
                + description
                + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransformDef that = (TransformDef) o;
        return Objects.equals(sourceTable, that.sourceTable)
                && Objects.equals(projection, that.projection)
                && Objects.equals(filter, that.filter)
                && Objects.equals(description, that.description)
                && Objects.equals(primaryKeys, that.primaryKeys)
                && Objects.equals(partitionKeys, that.partitionKeys)
                && Objects.equals(tableOptions, that.tableOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sourceTable,
                projection,
                filter,
                description,
                primaryKeys,
                partitionKeys,
                tableOptions);
    }
}
