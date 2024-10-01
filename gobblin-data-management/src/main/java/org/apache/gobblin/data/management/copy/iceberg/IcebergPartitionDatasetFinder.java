package org.apache.gobblin.data.management.copy.iceberg;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FileSystem;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.types.Types;


@Slf4j
public class IcebergPartitionDatasetFinder extends IcebergDatasetFinder {

  public IcebergPartitionDatasetFinder(FileSystem sourceFs, Properties properties) {
    super(sourceFs, properties);
  }

  @Override
  protected IcebergDataset createIcebergDataset(IcebergCatalog sourceIcebergCatalog, String srcDbName, String srcTableName, IcebergCatalog destinationIcebergCatalog, String destDbName, String destTableName, Properties properties, FileSystem fs) throws IOException {
    IcebergTable srcIcebergTable = sourceIcebergCatalog.openTable(srcDbName, srcTableName);
    Preconditions.checkArgument(sourceIcebergCatalog.tableAlreadyExists(srcIcebergTable), String.format("Missing Source Iceberg Table: {%s}.{%s}", srcDbName, srcTableName));
    IcebergTable destIcebergTable = destinationIcebergCatalog.openTable(destDbName, destTableName);
    Preconditions.checkArgument(destinationIcebergCatalog.tableAlreadyExists(destIcebergTable), String.format("Missing Destination Iceberg Table: {%s}.{%s}", destDbName, destTableName));
    Preconditions.checkArgument(validateSchema(srcIcebergTable, destIcebergTable),
        String.format("Schema Mismatch between Source {%s}.{%s} and Destination {%s}.{%s} Iceberg Tables", srcDbName, srcTableName, destDbName, destTableName));
    return new IcebergPartitionDataset(srcIcebergTable, destIcebergTable, properties, fs, getConfigShouldCopyMetadataPath(properties));
  }

  private boolean validateSchema(IcebergTable srcIcebergTable, IcebergTable destIcebergTable)
      throws IcebergTable.TableNotFoundException {
    TableMetadata srcTableMetadata = srcIcebergTable.accessTableMetadata();
    TableMetadata destTableMetadata = destIcebergTable.accessTableMetadata();

    Schema srchTableCurrentSchema = srcTableMetadata.schema();
    Schema destTableCurrentSchema = destTableMetadata.schema();

    log.info("Traversing Source Table Schema");
    Map<Integer, String> srcFieldIdToName = getFieldIdTOName(srchTableCurrentSchema);
    log.info("Traversing Destination Table Schema");
    Map<Integer, String> destFieldIdToName = getFieldIdTOName(destTableCurrentSchema);

    AtomicBoolean schemaMatched = new AtomicBoolean(true);

    destFieldIdToName.forEach((fieldId, columnName) -> {
      if (!srcFieldIdToName.containsKey(fieldId)) {
        log.error("Field ID: " + fieldId + " not found in Source Table Schema");
        schemaMatched.set(false);
      } else {
        String srcColumnName = srcFieldIdToName.get(fieldId);
        if (!srcColumnName.equals(columnName)) {
          log.error("Column Name Mismatch for Field ID: " + fieldId + ", Source Column Name: " + srcColumnName + ", Destination Column Name: " + columnName);
          schemaMatched.set(false);
        }
      }
    });

    return schemaMatched.get();
  }

  private Map<Integer, String> getFieldIdTOName(Schema schema) {
    Map<Integer, String> fieldIdToName = new ConcurrentHashMap<>();
    for (Types.NestedField field : schema.columns()) {
      traverse(field, "", fieldIdToName);
    }
    return fieldIdToName;
  }

  private void traverse(Types.NestedField field, String prefix, Map<Integer, String> fieldIdToName) {
    Integer curFieldId = field.fieldId();
    String curFieldName = field.name();
    /* prefix is the parent field name, adding parent name so that mapping of field id to column name is unique */
    String columnName = prefix.isEmpty() ? curFieldName : prefix + "." + curFieldName;
    log.info("Field ID: " + curFieldId + ", Column Name: " + columnName);
    fieldIdToName.put(curFieldId, columnName);

    List<Types.NestedField> childFields = new ArrayList<>();
    //If the field is a struct, list or map type, add all the child fields as they are nested
    if (field.type().isStructType()) {
      Types.StructType structType = field.type().asStructType();
      childFields.addAll(structType.fields());
    } else if (field.type().isListType()) {
      Types.ListType listType = field.type().asListType();
      childFields.addAll(listType.fields());
    } else if (field.type().isMapType()) {
      Types.MapType mapType = field.type().asMapType();
      childFields.addAll(mapType.fields());
    }
    //Recursively traverse all the child fields
    for (Types.NestedField childField : childFields) {
      traverse(childField, columnName, fieldIdToName);
    }
  }

//    private Map<String, Integer> getColumnNameWithFieldId(Schema schema) {
//
//    //TODO: Nested schema can be of various types, need to handle all types
//
//    Map<String, Integer> columnNameWithFieldId = new HashMap<>();
//    for (Types.NestedField field : schema.columns()) {
//      columnNameWithFieldId.put(field.name(), field.fieldId());
//      if (field.type().isStructType()) {
//        columnNameWithFieldId.putAll(getColumnNameWithFieldId(new Schema(field.type().asStructType().fields())));
//      }
//    }
//    return columnNameWithFieldId;
//  }

}
