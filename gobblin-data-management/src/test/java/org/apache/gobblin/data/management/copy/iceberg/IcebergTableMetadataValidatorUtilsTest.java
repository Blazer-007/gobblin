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
import java.util.HashMap;

import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.Schema;
import org.apache.iceberg.shaded.org.apache.avro.SchemaBuilder;
import org.testng.Assert;
import org.testng.annotations.Test;

public class IcebergTableMetadataValidatorUtilsTest {
  private static final PartitionSpec unpartitionedPartitionSpec = PartitionSpec.unpartitioned();
  private static final Schema schema1 = AvroSchemaUtil.toIceberg(SchemaBuilder.record("schema1")
      .fields()
      .requiredString("field1")
      .requiredString("field2")
      .endRecord());
  private static final Schema schema2IsNotSchema1Compat = AvroSchemaUtil.toIceberg(SchemaBuilder.record("schema2")
      .fields()
      .requiredString("field2")
      .requiredString("field1")
      .endRecord());
  private static final Schema schema3 = AvroSchemaUtil.toIceberg(SchemaBuilder.record("schema3")
      .fields()
      .requiredString("field1")
      .requiredString("field2")
      .requiredInt("field3")
      .endRecord());
  private static final Schema schema4IsNotSchema3Compat = AvroSchemaUtil.toIceberg(SchemaBuilder.record("schema4")
      .fields()
      .requiredInt("field1")
      .requiredString("field2")
      .requiredInt("field3")
      .endRecord());
  private static final PartitionSpec partitionSpec1 = PartitionSpec.builderFor(schema1)
      .identity("field1")
      .build();
  private static final TableMetadata tableMetadataWithSchema1AndUnpartitionedSpec = TableMetadata.newTableMetadata(
      schema1, unpartitionedPartitionSpec, "tableLocationForSchema1WithUnpartitionedSpec", new HashMap<>());
  private static final TableMetadata tableMetadataWithSchema1AndPartitionSpec1 = TableMetadata.newTableMetadata(
      schema1, partitionSpec1, "tableLocationForSchema1WithPartitionSpec1", new HashMap<>());
  private static final TableMetadata tableMetadataWithSchema3AndUnpartitionedSpec = TableMetadata.newTableMetadata(
      schema3, unpartitionedPartitionSpec, "tableLocationForSchema3WithUnpartitionedSpec", new HashMap<>());
  private static final String SCHEMA_MISMATCH_EXCEPTION = "Schema Mismatch between Metadata";
  private static final String PARTITION_SPEC_MISMATCH_EXCEPTION = "Partition Spec Mismatch between Metadata";
  private static final boolean VALIDATE_STRICT_PARTITION_EQUALITY_TRUE = Boolean.TRUE;
  private static final boolean VALIDATE_STRICT_PARTITION_EQUALITY_FALSE = Boolean.FALSE;
  @Test
  public void testValidateSameSchema() throws IOException {
    IcebergTableMetadataValidatorUtils.failUnlessCompatibleStructure(
        tableMetadataWithSchema1AndUnpartitionedSpec, tableMetadataWithSchema1AndUnpartitionedSpec,
        VALIDATE_STRICT_PARTITION_EQUALITY_TRUE
    );
  }

  @Test
  public void testValidateDifferentSchema() {
    // Schema 1 and Schema 2 have different field order

    TableMetadata tableMetadataWithSchema2AndUnpartitionedSpec = TableMetadata.newTableMetadata(schema2IsNotSchema1Compat,
        unpartitionedPartitionSpec, "tableLocationForSchema2WithUnpartitionedSpec", new HashMap<>());

    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndUnpartitionedSpec,
        tableMetadataWithSchema2AndUnpartitionedSpec, SCHEMA_MISMATCH_EXCEPTION);
  }

  @Test
  public void testValidateSchemaWithDifferentTypes() {
    // schema 3 and schema 4 have different field types for field1

    TableMetadata tableMetadataWithSchema4AndUnpartitionedSpec = TableMetadata.newTableMetadata(schema4IsNotSchema3Compat,
        unpartitionedPartitionSpec, "tableLocationForSchema4WithUnpartitionedSpec", new HashMap<>());

    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema3AndUnpartitionedSpec,
        tableMetadataWithSchema4AndUnpartitionedSpec, SCHEMA_MISMATCH_EXCEPTION);
  }

  @Test
  public void testValidateSchemaWithEvolvedSchemaI() {
    // TODO: This test should pass in the future when we support schema evolution
    // Schema 3 has one more extra field as compared to Schema 1
    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndUnpartitionedSpec,
        tableMetadataWithSchema3AndUnpartitionedSpec, SCHEMA_MISMATCH_EXCEPTION);
  }

  @Test
  public void testValidateSchemaWithEvolvedSchemaII() {
    // TODO: This test should pass in the future when we support schema evolution
    // Schema 3 has one more extra field as compared to Schema 1
    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema3AndUnpartitionedSpec,
        tableMetadataWithSchema1AndUnpartitionedSpec, SCHEMA_MISMATCH_EXCEPTION);
  }

  @Test
  public void testValidateOneSchemaEvolvedFromIntToLongType() {
    // Adding this test as to verify that partition copy doesn't proceed further for this case
    // as while doing poc and testing had seen final commit gets fail if there is mismatch in field type
    // specially from int to long
    Schema schema5EvolvedFromSchema4 = AvroSchemaUtil.toIceberg(SchemaBuilder.record("schema5")
        .fields()
        .requiredLong("field1")
        .requiredString("field2")
        .requiredInt("field3")
        .endRecord());
    PartitionSpec partitionSpec = PartitionSpec.builderFor(schema5EvolvedFromSchema4)
        .identity("field1")
        .build();
    TableMetadata tableMetadataWithSchema5AndPartitionSpec = TableMetadata.newTableMetadata(schema5EvolvedFromSchema4,
        partitionSpec, "tableLocationForSchema5WithPartitionSpec", new HashMap<>());

    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndUnpartitionedSpec,
        tableMetadataWithSchema5AndPartitionSpec, SCHEMA_MISMATCH_EXCEPTION);
  }

  @Test
  public void testValidateSamePartitionSpec() throws IOException {
    IcebergTableMetadataValidatorUtils.failUnlessCompatibleStructure(
        tableMetadataWithSchema1AndPartitionSpec1, tableMetadataWithSchema1AndPartitionSpec1,
        VALIDATE_STRICT_PARTITION_EQUALITY_TRUE
    );
  }

  @Test
  public void testValidatePartitionSpecWithDiffName() {
    PartitionSpec partitionSpec12 = PartitionSpec.builderFor(schema1)
        .identity("field2")
        .build();
    TableMetadata tableMetadataWithSchema1AndPartitionSpec12 = TableMetadata.newTableMetadata(schema1, partitionSpec12,
        "tableLocationForSchema1WithPartitionSpec12", new HashMap<>());
    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndPartitionSpec1,
        tableMetadataWithSchema1AndPartitionSpec12, PARTITION_SPEC_MISMATCH_EXCEPTION);
  }

  @Test
  public void testValidatePartitionSpecWithUnpartitioned() {
    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndUnpartitionedSpec,
        tableMetadataWithSchema1AndPartitionSpec1, PARTITION_SPEC_MISMATCH_EXCEPTION);
  }

  @Test
  public void testPartitionSpecWithDifferentTransform() {
    PartitionSpec partitionSpec12 = PartitionSpec.builderFor(schema1)
        .truncate("field1", 4)
        .build();
    TableMetadata tableMetadataWithSchema1AndPartitionSpec12 = TableMetadata.newTableMetadata(schema1, partitionSpec12,
        "tableLocationForSchema1WithPartitionSpec12", new HashMap<>());
    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndPartitionSpec1,
        tableMetadataWithSchema1AndPartitionSpec12, PARTITION_SPEC_MISMATCH_EXCEPTION);
  }

  @Test
  public void testStrictPartitionSpecEquality() throws IOException {
    PartitionSpec partitionSpecWithTwoCols = PartitionSpec.builderFor(schema1)
        .identity("field1")
        .identity("field2")
        .build();

    TableMetadata tableMetadataWithSchema1AndPartitionSpecWithTwoCols = TableMetadata.newTableMetadata(schema1,
        partitionSpecWithTwoCols, "tableLocationForSchema1WithPartitionSpecWithTwoCols", new HashMap<>());
    TableMetadata updatedMetadataForTableMetadataWithSchema1AndPartitionSpec1 = tableMetadataWithSchema1AndPartitionSpec1
        .updatePartitionSpec(tableMetadataWithSchema1AndPartitionSpecWithTwoCols.spec());

    IcebergTableMetadataValidatorUtils.failUnlessCompatibleStructure(
        tableMetadataWithSchema1AndPartitionSpecWithTwoCols,
        updatedMetadataForTableMetadataWithSchema1AndPartitionSpec1,
        VALIDATE_STRICT_PARTITION_EQUALITY_FALSE);

    verifyFailUnlessCompatibleStructureIOException(tableMetadataWithSchema1AndPartitionSpecWithTwoCols,
        updatedMetadataForTableMetadataWithSchema1AndPartitionSpec1, PARTITION_SPEC_MISMATCH_EXCEPTION);
  }

  private void verifyFailUnlessCompatibleStructureIOException(TableMetadata tableAMetadata,
      TableMetadata tableBMetadata, String expectedMessage) {
    IOException exception = Assert.expectThrows(IOException.class, () -> {
      IcebergTableMetadataValidatorUtils.failUnlessCompatibleStructure(tableAMetadata, tableBMetadata,
          VALIDATE_STRICT_PARTITION_EQUALITY_TRUE);
    });
    Assert.assertTrue(exception.getMessage().startsWith(expectedMessage));
  }
}
