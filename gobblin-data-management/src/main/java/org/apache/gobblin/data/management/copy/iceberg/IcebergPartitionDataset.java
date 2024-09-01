package org.apache.gobblin.data.management.copy.iceberg;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.apache.gobblin.data.management.copy.CopyConfiguration;
import org.apache.gobblin.data.management.copy.CopyEntity;
import org.apache.gobblin.data.management.copy.CopyableFile;
import org.apache.gobblin.data.management.copy.entities.PostPublishStep;
import org.apache.gobblin.data.management.copy.iceberg.predicates.IcebergPartitionFilterPredicate;
import org.apache.gobblin.util.PathUtils;
import org.apache.gobblin.util.filesystem.OwnerAndPermission;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.StructLike;


@Slf4j
public class IcebergPartitionDataset extends IcebergDataset {

  private final Predicate<StructLike> partitionFilterPredicate;
  public IcebergPartitionDataset(IcebergTable srcIcebergTable, IcebergTable destIcebergTable, Properties properties,
      FileSystem sourceFs, boolean shouldIncludeMetadataPath) throws IcebergTable.TableNotFoundException {
    super(srcIcebergTable, destIcebergTable, properties, sourceFs, shouldIncludeMetadataPath);
    this.partitionFilterPredicate = new IcebergPartitionFilterPredicate(getSrcIcebergTable().accessTableMetadata(), properties);
  }

  @Override
  Collection<CopyEntity> generateCopyEntities(FileSystem targetFs, CopyConfiguration copyConfig) throws IOException {
    String fileSet = this.getFileSetId();
    List<CopyEntity> copyEntities = Lists.newArrayList();
    IcebergTable icebergTable = getSrcIcebergTable();
    List<DataFile> fileInfos = icebergTable.getPartitionSpecificDataFiles(this.partitionFilterPredicate);

    log.info("~{}~ found {} data files for copying", fileSet, fileInfos.size());
//    log.info("Data Files");
//    for (DataFile fileInfo : fileInfos) {
//      log.info("Data File : {}", fileInfo);
//    }

    Configuration defaultHadoopConfiguration = new Configuration();
    for (Map.Entry<Path, FileStatus> entry : getPathToFileStatus(fileInfos, this.sourceFs).entrySet()) {
      Path srcPath = entry.getKey();
      FileStatus srcFileStatus = entry.getValue();
      // TODO: should be the same FS each time; try creating once, reusing thereafter, to not recreate wastefully
      FileSystem actualSourceFs = getSourceFileSystemFromFileStatus(srcFileStatus, defaultHadoopConfiguration);
      Path greatestAncestorPath = PathUtils.getRootPathChild(srcPath);

      // preserving ancestor permissions till root path's child between src and dest
      List<OwnerAndPermission> ancestorOwnerAndPermissionList =
          CopyableFile.resolveReplicatedOwnerAndPermissionsRecursively(actualSourceFs,
              srcPath.getParent(), greatestAncestorPath, copyConfig);
      CopyableFile fileEntity = CopyableFile.fromOriginAndDestination(
              actualSourceFs, srcFileStatus, targetFs.makeQualified(srcPath), copyConfig)
          .fileSet(fileSet)
          .datasetOutputPath(targetFs.getUri().getPath())
          .ancestorsOwnerAndPermission(ancestorOwnerAndPermissionList)
          .build();
      fileEntity.setSourceData(getSourceDataset(this.sourceFs));
      fileEntity.setDestinationData(getDestinationDataset(targetFs));
      copyEntities.add(fileEntity);
    }

    if (this.properties.getProperty("iceberg.replace.partitions.commit", "false").equals("true")) {
      copyEntities.add(createPostPublishStep(fileInfos));
    }
    log.info("----");
    log.info("~{}~ generated {} copy--entities", fileSet, copyEntities.size());
    log.info("Copy Entities : {}", copyEntities);

    return copyEntities;
  }

  private Map<Path, FileStatus> getPathToFileStatus(List<DataFile> fileInfos, FileSystem fs) {
    Map<Path, FileStatus> pathToFileStatus = new HashMap<>();
    for (DataFile fileInfo : fileInfos) {
      Path path = new Path(fileInfo.path().toString());
      try {
        FileStatus fileStatus = fs.getFileStatus(path);
        pathToFileStatus.put(path, fileStatus);
      } catch (IOException e) {
        log.error("Failed to get file status for path: " + path, e);
      }
    }
    return pathToFileStatus;
  }

  private PostPublishStep createPostPublishStep(List<DataFile> fileInfos) {
    IcebergReplacePartitionsStep icebergReplacePartitionsStep = new IcebergReplacePartitionsStep(
        this.getDestIcebergTable().getTableId().toString(),
        fileInfos,
        this.properties);
    return new PostPublishStep(this.getFileSetId(), Maps.newHashMap(), icebergReplacePartitionsStep, 0);
  }

}
