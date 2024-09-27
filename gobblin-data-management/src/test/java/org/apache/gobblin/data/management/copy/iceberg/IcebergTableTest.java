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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionData;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hive.HiveMetastoreTest;
import org.apache.iceberg.shaded.org.apache.avro.SchemaBuilder;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;


/** Test {@link org.apache.gobblin.data.management.copy.iceberg.IcebergTable} */
public class IcebergTableTest extends HiveMetastoreTest {

  protected static final org.apache.iceberg.shaded.org.apache.avro.Schema avroDataSchema =
      SchemaBuilder.record("test")
          .fields()
          .name("id")
          .type()
          .stringType()
          .noDefault()
          .endRecord();
  protected static final Schema icebergSchema = AvroSchemaUtil.toIceberg(avroDataSchema);
  protected static final PartitionSpec icebergPartitionSpec = PartitionSpec.builderFor(icebergSchema)
      .identity("id")
      .build();

  private final String dbName = "myicebergdb";
  private final String tableName = "justtesting";
  private TableIdentifier tableId;
  private Table table;
  private String catalogUri;
  private String metadataBasePath;

  @BeforeClass
  public void setUp() throws Exception {
    startMetastore();
    catalog.createNamespace(Namespace.of(dbName));
  }

  @BeforeMethod
  public void setUpEachTest() {
    tableId = TableIdentifier.of(dbName, tableName);
    table = catalog.createTable(tableId, icebergSchema);
    catalogUri = catalog.getConf().get(CatalogProperties.URI);
    metadataBasePath = calcMetadataBasePath(tableId);
  }

  @AfterMethod
  public void cleanUpEachTest() {
    catalog.dropTable(tableId);
  }

  /** Verify info about the current snapshot only */
  @Test
  public void testGetCurrentSnapshotInfo() throws IOException {
    List<List<String>> perSnapshotFilesets = Lists.newArrayList(
        Lists.newArrayList("/path/to/data-a0.orc"),
        Lists.newArrayList("/path/to/data-b0.orc", "/path/to/data-b1.orc"),
        Lists.newArrayList("/path/to/data-c0.orc", "/path/to/data-c1.orc", "/path/to/data-c2.orc"),
        Lists.newArrayList("/path/to/data-d0.orc")
    );

    initializeSnapshots(table, perSnapshotFilesets);
    IcebergSnapshotInfo snapshotInfo = new IcebergTable(tableId, catalog.newTableOps(tableId), catalogUri).getCurrentSnapshotInfo();
    verifySnapshotInfo(snapshotInfo, perSnapshotFilesets, perSnapshotFilesets.size());
  }

  /** Verify failure when attempting to get current snapshot info for non-existent table */
  @Test(expectedExceptions = IcebergTable.TableNotFoundException.class)
  public void testGetCurrentSnapshotInfoOnBogusTable() throws IOException {
    TableIdentifier bogusTableId = TableIdentifier.of(dbName, tableName + "_BOGUS");
    IcebergSnapshotInfo snapshotInfo = new IcebergTable(bogusTableId, catalog.newTableOps(bogusTableId), catalogUri).getCurrentSnapshotInfo();
    Assert.fail("expected an exception when using table ID '" + bogusTableId + "'");
  }

  /** Verify info about all (full) snapshots */
  @Test
  public void testGetAllSnapshotInfosIterator() throws IOException {
    List<List<String>> perSnapshotFilesets = Lists.newArrayList(
        Lists.newArrayList("/path/to/data-a0.orc"),
        Lists.newArrayList("/path/to/data-b0.orc", "/path/to/data-b1.orc"),
        Lists.newArrayList("/path/to/data-c0.orc", "/path/to/data-c1.orc", "/path/to/data-c2.orc"),
        Lists.newArrayList("/path/to/data-d0.orc")
    );

    initializeSnapshots(table, perSnapshotFilesets);
    List<IcebergSnapshotInfo> snapshotInfos = Lists.newArrayList(new IcebergTable(tableId, catalog.newTableOps(tableId), catalogUri).getAllSnapshotInfosIterator());
    Assert.assertEquals(snapshotInfos.size(), perSnapshotFilesets.size(), "num snapshots");

    for (int i = 0; i < snapshotInfos.size(); ++i) {
      System.err.println("verifying snapshotInfo[" + i + "]");
      verifySnapshotInfo(snapshotInfos.get(i), perSnapshotFilesets.subList(0, i + 1), snapshotInfos.size());
    }
  }

  /** Verify info about all snapshots (incremental deltas) */
  @Test
  public void testGetIncrementalSnapshotInfosIterator() throws IOException {
    List<List<String>> perSnapshotFilesets = Lists.newArrayList(
        Lists.newArrayList("/path/to/data-a0.orc"),
        Lists.newArrayList("/path/to/data-b0.orc", "/path/to/data-b1.orc"),
        Lists.newArrayList("/path/to/data-c0.orc", "/path/to/data-c1.orc", "/path/to/data-c2.orc"),
        Lists.newArrayList("/path/to/data-d0.orc")
    );

    initializeSnapshots(table, perSnapshotFilesets);
    List<IcebergSnapshotInfo> snapshotInfos = Lists.newArrayList(new IcebergTable(tableId, catalog.newTableOps(tableId), catalogUri).getIncrementalSnapshotInfosIterator());
    Assert.assertEquals(snapshotInfos.size(), perSnapshotFilesets.size(), "num snapshots");

    for (int i = 0; i < snapshotInfos.size(); ++i) {
      System.err.println("verifying snapshotInfo[" + i + "]");
      verifySnapshotInfo(snapshotInfos.get(i), perSnapshotFilesets.subList(i, i + 1), snapshotInfos.size());
    }
  }

  /** Verify info about all snapshots (incremental deltas) correctly eliminates repeated data files */
  @Test
  public void testGetIncrementalSnapshotInfosIteratorRepeatedFiles() throws IOException {
    List<List<String>> perSnapshotFilesets = Lists.newArrayList(
        Lists.newArrayList("/path/to/data-a0.orc"),
        Lists.newArrayList("/path/to/data-b0.orc", "/path/to/data-b1.orc", "/path/to/data-a0.orc"),
        Lists.newArrayList("/path/to/data-a0.orc","/path/to/data-c0.orc", "/path/to/data-b1.orc", "/path/to/data-c1.orc", "/path/to/data-c2.orc"),
        Lists.newArrayList("/path/to/data-d0.orc")
    );

    initializeSnapshots(table, perSnapshotFilesets);
    List<IcebergSnapshotInfo> snapshotInfos = Lists.newArrayList(new IcebergTable(tableId, catalog.newTableOps(tableId), catalogUri).getIncrementalSnapshotInfosIterator());
    Assert.assertEquals(snapshotInfos.size(), perSnapshotFilesets.size(), "num snapshots");

    for (int i = 0; i < snapshotInfos.size(); ++i) {
      System.err.println("verifying snapshotInfo[" + i + "] - " + snapshotInfos.get(i));
      char initialChar = (char) ((int) 'a' + i);
      // adjust expectations to eliminate duplicate entries (i.e. those bearing letter not aligned with ordinal fileset)
      List<String> fileset = perSnapshotFilesets.get(i).stream().filter(name -> {
        String uniquePortion = name.substring("/path/to/data-".length());
        return uniquePortion.startsWith(Character.toString(initialChar));
      }).collect(Collectors.toList());
      verifySnapshotInfo(snapshotInfos.get(i), Arrays.asList(fileset), snapshotInfos.size());
    }
  }

  /** Verify that registerIcebergTable will update existing table properties */
  @Test
  public void testNewTablePropertiesAreRegistered() throws Exception {
    Map<String, String> srcTableProperties = Maps.newHashMap();
    Map<String, String> destTableProperties = Maps.newHashMap();

    srcTableProperties.put("newKey", "newValue");
    // Expect the old value to be overwritten by the new value
    srcTableProperties.put("testKey", "testValueNew");
    destTableProperties.put("testKey", "testValueOld");
    // Expect existing property values to be deleted if it does not exist on the source
    destTableProperties.put("deletedTableProperty", "deletedTablePropertyValue");

    TableIdentifier destTableId = TableIdentifier.of(dbName, "destTable");
    catalog.createTable(destTableId, icebergSchema, null, destTableProperties);

    IcebergTable destIcebergTable = new IcebergTable(destTableId, catalog.newTableOps(destTableId), catalogUri);
    // Mock a source table with the same table UUID copying new properties
    TableMetadata newSourceTableProperties = destIcebergTable.accessTableMetadata().replaceProperties(srcTableProperties);

    destIcebergTable.registerIcebergTable(newSourceTableProperties, destIcebergTable.accessTableMetadata());
    Assert.assertEquals(destIcebergTable.accessTableMetadata().properties().size(), 2);
    Assert.assertEquals(destIcebergTable.accessTableMetadata().properties().get("newKey"), "newValue");
    Assert.assertEquals(destIcebergTable.accessTableMetadata().properties().get("testKey"), "testValueNew");
    Assert.assertNull(destIcebergTable.accessTableMetadata().properties().get("deletedTableProperty"));
  }

  /** full validation for a particular {@link IcebergSnapshotInfo} */
  protected void verifySnapshotInfo(IcebergSnapshotInfo snapshotInfo, List<List<String>> perSnapshotFilesets, int overallNumSnapshots) {
    // verify metadata file
    snapshotInfo.getMetadataPath().ifPresent(metadataPath -> {
          Optional<File> optMetadataFile = extractSomeMetadataFilepath(metadataPath, metadataBasePath, IcebergTableTest::doesResembleMetadataFilename);
          Assert.assertTrue(optMetadataFile.isPresent(), "has metadata filepath");
          verifyMetadataFile(optMetadataFile.get(), Optional.of(overallNumSnapshots));
        }
    );
    // verify manifest list file
    Optional<File> optManifestListFile = extractSomeMetadataFilepath(snapshotInfo.getManifestListPath(), metadataBasePath, IcebergTableTest::doesResembleManifestListFilename);
    Assert.assertTrue(optManifestListFile.isPresent(), "has manifest list filepath");
    verifyManifestListFile(optManifestListFile.get(), Optional.of(snapshotInfo.getSnapshotId()));
    // verify manifest files and their listed data files
    List<IcebergSnapshotInfo.ManifestFileInfo> manifestFileInfos = snapshotInfo.getManifestFiles();
    verifyManifestFiles(manifestFileInfos, snapshotInfo.getManifestFilePaths(), perSnapshotFilesets);
    verifyAnyOrder(snapshotInfo.getAllDataFilePaths(), flatten(perSnapshotFilesets), "data filepaths");
    // verify all aforementioned paths collectively equal `getAllPaths()`
    boolean shouldIncludeMetadataPath = false;
    List<String> allPathsExpected = Lists.newArrayList(snapshotInfo.getManifestListPath());
    allPathsExpected.addAll(snapshotInfo.getManifestFilePaths());
    allPathsExpected.addAll(snapshotInfo.getAllDataFilePaths());
    verifyAnyOrder(snapshotInfo.getAllPaths(shouldIncludeMetadataPath), allPathsExpected, "all paths, metadata and data, except metadataPath itself");

    boolean shouldIncludeMetadataPathIfAvailable = true;
    snapshotInfo.getMetadataPath().ifPresent(allPathsExpected::add);
    verifyAnyOrder(snapshotInfo.getAllPaths(shouldIncludeMetadataPathIfAvailable), allPathsExpected, "all paths, metadata and data, including metadataPath");
  }

  protected String calcMetadataBasePath(TableIdentifier tableId) {
    return calcMetadataBasePath(tableId.namespace().toString(), tableId.name());
  }

  protected String calcMetadataBasePath(String theDbName, String theTableName) {
    String basePath = String.format("%s/%s/metadata", metastore.getDatabasePath(theDbName), theTableName);
    System.err.println("calculated metadata base path: '" + basePath + "'");
    return basePath;
  }

  /** Add one snapshot per sub-list of `perSnapshotFilesets`, in order, with the sub-list contents as the data files */
  protected static void initializeSnapshots(Table table, List<List<String>> perSnapshotFilesets) {
    for (List<String> snapshotFileset : perSnapshotFilesets) {
      AppendFiles append = table.newAppend();
      for (String fpath : snapshotFileset) {
        append.appendFile(createDataFile(fpath, 0, 1));
      }
      append.commit();
    }
  }

  /** Extract whatever kind of iceberg metadata file, iff recognized by `doesResemble` */
  protected static Optional<File> extractSomeMetadataFilepath(String candidatePath, String basePath, Predicate<String> doesResemble) {
    try {
      URI candidateUri = new URI(candidatePath);
      File file = new File(candidateUri.getPath());
      Assert.assertEquals(file.getParent(), basePath, "metadata base dirpath");
      return Optional.ofNullable(doesResemble.test(file.getName()) ? file : null);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e); // should not happen!
    }
  }

  protected void verifyMetadataFile(File file, Optional<Integer> optSnapshotSeqNum) {
    Assert.assertTrue(doesResembleMetadataFilename(file.getName()), "metadata filename resemblance");
    if (optSnapshotSeqNum.isPresent()) {
      Assert.assertEquals(Integer.valueOf(file.getName().split("-")[0]), optSnapshotSeqNum.get(),
          "snapshot sequence num");
    }
  }

  protected void verifyManifestListFile(File file, Optional<Long> optSnapshotId) {
    Assert.assertTrue(doesResembleManifestListFilename(file.getName()),"manifest list filename resemblance");
    if (optSnapshotId.isPresent()) {
      Assert.assertEquals(Long.valueOf(file.getName().split("-")[1]), optSnapshotId.get(), "snapshot id");
    }
  }

  protected void verifyManifestFiles(List<IcebergSnapshotInfo.ManifestFileInfo> manifestFileInfos,
      List<String> manifestFilePaths,
      List<List<String>> perSnapshotFilesets) {
    Assert.assertEquals(manifestFileInfos.size(), manifestFilePaths.size());
    Assert.assertEquals(manifestFileInfos.size(), perSnapshotFilesets.size());
    int numManifests = manifestFileInfos.size();
    for (int i = 0; i < numManifests; ++i) {
      IcebergSnapshotInfo.ManifestFileInfo mfi = manifestFileInfos.get(i);
      Assert.assertTrue(doesResembleManifestFilename(mfi.getManifestFilePath()), "manifest filename resemblance");
      Assert.assertEquals(mfi.getManifestFilePath(), manifestFilePaths.get(i));
      verifyAnyOrder(mfi.getListedFilePaths(), perSnapshotFilesets.get(numManifests - i - 1),
          "manifest contents of '" + mfi.getManifestFilePath() + "'");
    }
  }

  protected static boolean doesResembleMetadataFilename(String name) {
    return name.endsWith(".metadata.json");
  }

  protected static boolean doesResembleManifestListFilename(String name) {
    return name.startsWith("snap-") && name.endsWith(".avro");
  }

  protected static boolean doesResembleManifestFilename(String name) {
    return !name.startsWith("snap-") && name.endsWith(".avro");
  }

  /** doesn't actually create a physical file (on disk), merely a {@link org.apache.iceberg.DataFile} */
  protected static DataFile createDataFile(String path, long sizeBytes, long numRecords) {
    return DataFiles.builder(icebergPartitionSpec)
        .withPath(path)
        .withFileSizeInBytes(sizeBytes)
        .withRecordCount(numRecords)
        .build();
  }

  /** general utility: order-independent/set equality between collections */
  protected static <T> void verifyAnyOrder(Collection<T> actual, Collection<T> expected, String message) {
    Assert.assertEquals(Sets.newHashSet(actual), Sets.newHashSet(expected), message);
  }

  /** general utility: flatten a collection of collections into a single-level {@link List} */
  protected static <T, C extends Collection<T>> List<T> flatten(Collection<C> cc) {
    return cc.stream().flatMap(x -> x.stream()).collect(Collectors.toList());
  }

  @Test(enabled = false)
  public void testGetPartitionSpecificDataFiles() throws IOException {
    TableIdentifier testTableId = TableIdentifier.of(dbName, "testTable");
    Table testTable = catalog.createTable(testTableId, icebergSchema, icebergPartitionSpec);

    List<String> paths = Arrays.asList(
        "/path/tableName/data/id=1/file1.orc",
        "/path/tableName/data/id=1/file3.orc",
        "/path/tableName/data/id=1/file5.orc",
        "/path/tableName/data/id=1/file4.orc",
        "/path/tableName/data/id=1/file2.orc"
    );
    // Using the schema defined in start of this class
    PartitionData partitionData = new PartitionData(icebergPartitionSpec.partitionType());
    partitionData.set(0, "1");
    List<PartitionData> partitionDataList = Collections.nCopies(paths.size(), partitionData);

    addPartitionDataFiles(testTable, paths, partitionDataList);

    IcebergTable icebergTable = new IcebergTable(testTableId,
        catalog.newTableOps(testTableId),
        catalogUri,
        catalog.loadTable(testTableId));
    // Using AlwaysTrue & AlwaysFalse Predicate to avoid mocking of predicate class
    Predicate<StructLike> alwaysTruePredicate = partition -> true;
    Predicate<StructLike> alwaysFalsePredicate = partition -> false;
    Assert.assertEquals(icebergTable.getPartitionSpecificDataFiles(alwaysTruePredicate).size(), 5);
    Assert.assertEquals(icebergTable.getPartitionSpecificDataFiles(alwaysFalsePredicate).size(), 0);

    catalog.dropTable(testTableId);
  }

  @Test(enabled = false)
  public void testOverwritePartitions() throws IOException {
    TableIdentifier testTableId = TableIdentifier.of(dbName, "testTable");
    Table testTable = catalog.createTable(testTableId, icebergSchema, icebergPartitionSpec);

    List<String> paths = Arrays.asList(
        "/path/tableName/data/id=1/file1.orc",
        "/path/tableName/data/id=1/file2.orc"
    );
    // Using the schema defined in start of this class
    PartitionData partitionData = new PartitionData(icebergPartitionSpec.partitionType());
    partitionData.set(0, "1");
    PartitionData partitionData2 = new PartitionData(icebergPartitionSpec.partitionType());
    partitionData2.set(0, "1");
    List<PartitionData> partitionDataList = Arrays.asList(partitionData, partitionData2);

    addPartitionDataFiles(testTable, paths, partitionDataList);

    IcebergTable icebergTable = new IcebergTable(testTableId,
        catalog.newTableOps(testTableId),
        catalogUri,
        catalog.loadTable(testTableId));

    verifyAnyOrder(paths, icebergTable.getCurrentSnapshotInfo().getAllDataFilePaths(), "data filepaths should match");

    List<String> paths2 = Arrays.asList(
        "/path/tableName/data/id=2/file3.orc",
        "/path/tableName/data/id=2/file4.orc"
    );
    // Using the schema defined in start of this class
    PartitionData partitionData3 = new PartitionData(icebergPartitionSpec.partitionType());
    partitionData3.set(0, "2");
    PartitionData partitionData4 = new PartitionData(icebergPartitionSpec.partitionType());
    partitionData4.set(0, "2");
    List<PartitionData> partitionDataList2 = Arrays.asList(partitionData3, partitionData4);

    List<DataFile> dataFiles2 = getDataFiles(paths2, partitionDataList2);
    // here, since partition data with value 2 doesn't exist yet, we expect it to get added to the table
    icebergTable.overwritePartitions(dataFiles2, "id", "2");
    List<String> expectedPaths2 = new ArrayList<>(paths);
    expectedPaths2.addAll(paths2);
    verifyAnyOrder(expectedPaths2, icebergTable.getCurrentSnapshotInfo().getAllDataFilePaths(), "data filepaths should match");

    List<String> paths3 = Arrays.asList(
      "/path/tableName/data/id=1/file5.orc",
      "/path/tableName/data/id=1/file6.orc"
    );
    // Reusing same partition dats to create data file with different paths
    List<DataFile> dataFiles3 = getDataFiles(paths3, partitionDataList);
    // here, since partition data with value 1 already exists, we expect it to get updated in the table with newer path
    icebergTable.overwritePartitions(dataFiles3, "id", "1");
    List<String> expectedPaths3 = new ArrayList<>(paths2);
    expectedPaths3.addAll(paths3);
    verifyAnyOrder(expectedPaths3, icebergTable.getCurrentSnapshotInfo().getAllDataFilePaths(), "data filepaths should match");

    catalog.dropTable(testTableId);
  }

  private static void addPartitionDataFiles(Table table, List<String> paths, List<PartitionData> partitionDataList) {
    Assert.assertEquals(paths.size(), partitionDataList.size());
    getDataFiles(paths, partitionDataList).forEach(dataFile -> table.newAppend().appendFile(dataFile).commit());
  }

  private static List<DataFile> getDataFiles(List<String> paths, List<PartitionData> partitionDataList) {
    Assert.assertEquals(paths.size(), partitionDataList.size());
    List<DataFile> dataFiles = Lists.newArrayList();
    for (int i = 0; i < paths.size(); i++) {
      dataFiles.add(createDataFileWithPartition(paths.get(i), partitionDataList.get(i)));
    }
    return dataFiles;
  }

  private static DataFile createDataFileWithPartition(String path, PartitionData partitionData) {
    return DataFiles.builder(icebergPartitionSpec)
        .withPath(path)
        .withFileSizeInBytes(8)
        .withRecordCount(1)
        .withPartition(partitionData)
        .withFormat(FileFormat.ORC)
        .build();
  }

}
