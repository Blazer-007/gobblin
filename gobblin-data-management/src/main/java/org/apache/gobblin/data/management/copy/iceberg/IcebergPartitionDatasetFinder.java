/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.data.management.copy.iceberg;

import java.io.IOException;
import java.util.Properties;

import org.apache.hadoop.fs.FileSystem;
import org.apache.iceberg.TableMetadata;

import com.google.common.base.Preconditions;

/**
 * Finder class for locating and creating partitioned Iceberg datasets.
 * <p>
 * This class extends {@link IcebergDatasetFinder} and provides functionality to create
 * {@link IcebergPartitionDataset} instances based on the specified source and destination Iceberg catalogs.
 * </p>
 */
public class IcebergPartitionDatasetFinder extends IcebergDatasetFinder {
  public IcebergPartitionDatasetFinder(FileSystem sourceFs, Properties properties) {
    super(sourceFs, properties);
  }

/**
 * Creates an {@link IcebergPartitionDataset} instance for the specified source and destination Iceberg tables.
 */
  @Override
  protected IcebergDataset createIcebergDataset(IcebergCatalog sourceIcebergCatalog, String srcDbName, String srcTableName, IcebergCatalog destinationIcebergCatalog, String destDbName, String destTableName, Properties properties, FileSystem fs) throws IOException {
    IcebergTable srcIcebergTable = sourceIcebergCatalog.openTable(srcDbName, srcTableName);
    Preconditions.checkArgument(sourceIcebergCatalog.tableAlreadyExists(srcIcebergTable),
        String.format("Missing Source Iceberg Table: {%s}.{%s}", srcDbName, srcTableName));
    IcebergTable destIcebergTable = destinationIcebergCatalog.openTable(destDbName, destTableName);
    Preconditions.checkArgument(destinationIcebergCatalog.tableAlreadyExists(destIcebergTable),
        String.format("Missing Destination Iceberg Table: {%s}.{%s}", destDbName, destTableName));
    TableMetadata srcTableMetadata = srcIcebergTable.accessTableMetadata();
    TableMetadata destTableMetadata = destIcebergTable.accessTableMetadata();
    Preconditions.checkArgument(validateSchema(srcTableMetadata, destTableMetadata),
        String.format("Schema Mismatch between Source {%s}.{%s} and Destination {%s}.{%s} Iceberg Tables\n"
            + "Currently, only supporting copying between iceberg tables with same schema",
            srcDbName, srcTableName, destDbName, destTableName));
    Preconditions.checkArgument(validatePartitionSpec(srcTableMetadata, destTableMetadata),
        String.format("Partition Spec Mismatch between Source {%s}.{%s} and Destination {%s}.{%s} Iceberg Tables\n"
            + "Currently, only supporting copying between iceberg tables with same partition spec",
            srcDbName, srcTableName, destDbName, destTableName));
    return new IcebergPartitionDataset(srcIcebergTable, destIcebergTable, properties, fs, getConfigShouldCopyMetadataPath(properties));
  }

  private boolean validateSchema(TableMetadata srcTableMetadata, TableMetadata destTableMetadata) {
    // Currently, only supporting copying between iceberg tables with same schema
    return srcTableMetadata.schema().sameSchema(destTableMetadata.schema());
  }

  private boolean validatePartitionSpec(TableMetadata srcTableMetadata, TableMetadata destTableMetadata) {
    // Currently, only supporting copying between iceberg tables with same partition spec
    return srcTableMetadata.spec().compatibleWith(destTableMetadata.spec());
  }
}