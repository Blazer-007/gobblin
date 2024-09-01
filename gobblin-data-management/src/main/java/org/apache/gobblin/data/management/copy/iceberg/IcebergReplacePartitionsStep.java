package org.apache.gobblin.data.management.copy.iceberg;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.gobblin.commit.CommitStep;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.catalog.TableIdentifier;


@Slf4j
public class IcebergReplacePartitionsStep implements CommitStep {

  private static final String ICEBERG_REPLACE_PARTITIONS_STEP = "IcebergReplacePartitionsStep";
  private final String destTableIdStr;
  private List<DataFile> destTableDataFiles;
  private final Properties properties;

  public IcebergReplacePartitionsStep(String destTableIdStr, List<DataFile> dataFiles, Properties properties) {
    this.destTableIdStr = destTableIdStr;
    this.destTableDataFiles = dataFiles;
    this.properties = properties;
  }

  @Override
  public boolean isCompleted() throws IOException {
    return false;
  }

  @Override
  public void execute() throws IOException {
    IcebergTable destTable = createDestinationCatalog().openTable(TableIdentifier.parse(destTableIdStr));
    try {
      System.out.println("Replacing partitions for table " + destTableIdStr);
      destTable.replacePartitions(this.destTableDataFiles);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected IcebergCatalog createDestinationCatalog() throws IOException {
    return IcebergDatasetFinder.createIcebergCatalog(this.properties, IcebergDatasetFinder.CatalogLocation.DESTINATION);
  }
}
