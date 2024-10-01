package org.apache.gobblin.data.management.copy.iceberg.predicates;

import com.google.common.base.Splitter;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.TableMetadata;


public class IcebergPartitionFilterPredicate implements Predicate<StructLike> {

  private int partitionColumnIndex;
  private final List<String> partitionValues;

  private static final Splitter LIST_SPLITTER = Splitter.on(",").trimResults().omitEmptyStrings();

  public IcebergPartitionFilterPredicate(TableMetadata tableMetadata, Properties properties) {
    String colName = properties.getProperty("iceberg.partition.name");
    List<PartitionField> partitionFields = tableMetadata.spec().fields();
    for (int idx = 0; idx < partitionFields.size(); idx++) {
      PartitionField partitionField = partitionFields.get(idx);
      if (partitionField.name().equals(colName)
          || tableMetadata.schema().findColumnName(partitionField.sourceId()).equals(colName)) {
        this.partitionColumnIndex = idx;
        break;
      }
    }
    this.partitionValues = LIST_SPLITTER.splitToList(properties.getProperty("iceberg.partition.values"));
  }

  @Override
  public boolean test(StructLike partition) {
    //TODO: Add Null Check for partition explicitly

    String partitionVal = partition.get(this.partitionColumnIndex, String.class);
    return this.partitionValues.contains(partitionVal);
  }
}
