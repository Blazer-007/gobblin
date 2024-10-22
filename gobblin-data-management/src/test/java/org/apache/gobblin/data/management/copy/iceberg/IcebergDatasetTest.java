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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.collections.Sets;

import com.google.api.client.util.Maps;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import lombok.Data;

import org.apache.gobblin.data.management.copy.CopyConfiguration;
import org.apache.gobblin.data.management.copy.CopyContext;
import org.apache.gobblin.data.management.copy.CopyEntity;
import org.apache.gobblin.data.management.copy.PreserveAttributes;
import org.apache.gobblin.dataset.DatasetConstants;
import org.apache.gobblin.dataset.DatasetDescriptor;

import static org.mockito.Mockito.any;


/** Tests for {@link org.apache.gobblin.data.management.copy.iceberg.IcebergDataset} */
public class IcebergDatasetTest {

  private static final URI SRC_FS_URI;
  private static final URI DEST_FS_URI;

  static {
    try {
      SRC_FS_URI = new URI("abc", "the.source.org", "/", null);
      DEST_FS_URI = new URI("xyz", "the.dest.org", "/", null);
    } catch (URISyntaxException e) {
      throw new RuntimeException("should not occur!", e);
    }
  }

  private static final String ROOT_PATH = "/root/iceberg/test/";
  private static final String METADATA_PATH = ROOT_PATH + "metadata/metadata.json";
  private static final String MANIFEST_LIST_PATH_0 = ROOT_PATH + "metadata/manifest_list.x";
  private static final String MANIFEST_PATH_0 = ROOT_PATH + "metadata/manifest.a";
  private static final String MANIFEST_DATA_PATH_0A = ROOT_PATH + "data/p0/a";
  private static final String MANIFEST_DATA_PATH_0B = ROOT_PATH + "data/p0/b";
  private static final String REGISTER_COMMIT_STEP = "org.apache.gobblin.data.management.copy.iceberg.IcebergRegisterStep";
  private static final MockIcebergTable.SnapshotPaths SNAPSHOT_PATHS_0 =
      new MockIcebergTable.SnapshotPaths(Optional.of(METADATA_PATH), MANIFEST_LIST_PATH_0, Arrays.asList(
          new IcebergSnapshotInfo.ManifestFileInfo(MANIFEST_PATH_0,
              Arrays.asList(MANIFEST_DATA_PATH_0A, MANIFEST_DATA_PATH_0B))));
  private static final String MANIFEST_LIST_PATH_1 = MANIFEST_LIST_PATH_0.replaceAll("\\.x$", ".y");
  private static final String MANIFEST_PATH_1 = MANIFEST_PATH_0.replaceAll("\\.a$", ".b");
  private static final String MANIFEST_DATA_PATH_1A = MANIFEST_DATA_PATH_0A.replaceAll("/p0/", "/p1/");
  private static final String MANIFEST_DATA_PATH_1B = MANIFEST_DATA_PATH_0B.replaceAll("/p0/", "/p1/");
  private static final MockIcebergTable.SnapshotPaths SNAPSHOT_PATHS_1 =
      new MockIcebergTable.SnapshotPaths(Optional.empty(), MANIFEST_LIST_PATH_1, Arrays.asList(
          new IcebergSnapshotInfo.ManifestFileInfo(MANIFEST_PATH_1,
              Arrays.asList(MANIFEST_DATA_PATH_1A, MANIFEST_DATA_PATH_1B))));

  private final String testDbName = "test_db_name";
  private final String testTblName = "test_tbl_name";
  public static final String SRC_CATALOG_URI = "abc://the.source.org/catalog";
  private final Properties copyConfigProperties = new Properties();

  @BeforeClass
  public void setUp() throws Exception {
    copyConfigProperties.setProperty("data.publisher.final.dir", "/test");
  }

  @Test
  public void testGetDatasetDescriptor() throws URISyntaxException {
    TableIdentifier tableId = TableIdentifier.of(testDbName, testTblName);
    String qualifiedTableName = "foo_prefix." + tableId.toString();
    String platformName = "Floe";
    IcebergTable table = new IcebergTable(tableId, qualifiedTableName, platformName,
        Mockito.mock(TableOperations.class),
        SRC_CATALOG_URI,
        Mockito.mock(Table.class));
    FileSystem mockFs = Mockito.mock(FileSystem.class);
    Mockito.when(mockFs.getUri()).thenReturn(SRC_FS_URI);
    DatasetDescriptor expected = new DatasetDescriptor(platformName, URI.create(SRC_CATALOG_URI), qualifiedTableName);
    expected.addMetadata(DatasetConstants.FS_URI, SRC_FS_URI.toString());
    Assert.assertEquals(table.getDatasetDescriptor(mockFs), expected);
  }

  @Test
  public void testGetFilePathsWhenDestEmpty() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList();
    boolean shouldIncludeMetadataPath = true;
    Set<Path> expectedResultPaths = withAllSnapshotPaths(Sets.newHashSet(), shouldIncludeMetadataPath, SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWhenOneManifestListAtDest() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList(MANIFEST_LIST_PATH_1);
    boolean shouldIncludeMetadataPath = true;
    Set<Path> expectedResultPaths = withAllSnapshotPaths(Sets.newHashSet(), shouldIncludeMetadataPath, SNAPSHOT_PATHS_0);
    validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWhenOneManifestAtDest() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList(MANIFEST_PATH_1);
    boolean shouldIncludeMetadataPath = false;
    Set<Path> expectedResultPaths = withAllSnapshotPaths(Sets.newHashSet(), shouldIncludeMetadataPath, SNAPSHOT_PATHS_0);
    expectedResultPaths.add(new Path(MANIFEST_LIST_PATH_1)); // expect manifest's parent, despite manifest subtree skip
    validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWhenSomeDataFilesAtDest() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList(MANIFEST_DATA_PATH_1B, MANIFEST_DATA_PATH_0A);
    boolean shouldIncludeMetadataPath = true;
    Set<Path> expectedResultPaths = withAllSnapshotPaths(Sets.newHashSet(), shouldIncludeMetadataPath, SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    // despite already existing on target, expect anyway: per-file check skipped for optimization's sake
    // expectedResultPaths.remove(new Path(MANIFEST_DATA_PATH_1B));
    // expectedResultPaths.remove(new Path(MANIFEST_DATA_PATH_0A));
    validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWillSkipMissingSourceFile() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    // pretend this path doesn't exist on source:
    Path missingPath = new Path(MANIFEST_DATA_PATH_0A);
    boolean shouldIncludeMetadataPath = false;
    Set<Path> existingSourcePaths = withAllSnapshotPaths(Sets.newHashSet(), shouldIncludeMetadataPath, SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    existingSourcePaths.remove(missingPath);
    List<String> existingDestPaths = Lists.newArrayList(MANIFEST_LIST_PATH_1);
    Set<Path> expectedResultPaths = withAllSnapshotPaths(Sets.newHashSet(), shouldIncludeMetadataPath, SNAPSHOT_PATHS_0);
    expectedResultPaths.remove(missingPath);
    validateGetFilePathsGivenDestState(icebergSnapshots,
        Optional.of(existingSourcePaths.stream().map(Path::toString).collect(Collectors.toList())), existingDestPaths,
        expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWhenManifestListsAtDestButNotMetadata() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList(MANIFEST_LIST_PATH_1, MANIFEST_LIST_PATH_0);
    boolean shouldIncludeMetadataPath = true;
    Set<Path> expectedResultPaths = Sets.newHashSet();
    expectedResultPaths.add(new Path(METADATA_PATH));
    validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWhenManifestListsAtDestButNotMetadataYetThatIgnored() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList(MANIFEST_LIST_PATH_1, MANIFEST_LIST_PATH_0);
    boolean shouldIncludeMetadataPath = false;
    Set<Path> expectedResultPaths = Sets.newHashSet(); // nothing expected!
    validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
  }

  @Test
  public void testGetFilePathsWhenAllAtDest() throws IOException {
    List<MockIcebergTable.SnapshotPaths> icebergSnapshots = Lists.newArrayList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0);
    List<String> existingDestPaths = Lists.newArrayList(METADATA_PATH, MANIFEST_LIST_PATH_1, MANIFEST_LIST_PATH_0);
    boolean shouldIncludeMetadataPath = true;
    Set<Path> expectedResultPaths = Sets.newHashSet(); // not expecting any delta
    IcebergTable mockTable =
        validateGetFilePathsGivenDestState(icebergSnapshots, existingDestPaths, expectedResultPaths, shouldIncludeMetadataPath);
    // ensure short-circuiting was able to avert iceberg manifests scan
    Mockito.verify(mockTable, Mockito.times(1)).getCurrentSnapshotInfoOverviewOnly();
    Mockito.verify(mockTable, Mockito.times(1)).getTableId();
    Mockito.verifyNoMoreInteractions(mockTable);
  }

  /** Exception wrapping is used internally--ensure that doesn't lapse into silently swallowing errors */
  @Test(expectedExceptions = IOException.class)
  public void testGetFilePathsDoesNotSwallowDestFileSystemException() throws IOException {
    IcebergTable srcIcebergTable = MockIcebergTable.withSnapshots(TableIdentifier.of(testDbName, testTblName), Lists.newArrayList(SNAPSHOT_PATHS_0));

    MockFileSystemBuilder sourceFsBuilder = new MockFileSystemBuilder(SRC_FS_URI);
    FileSystem sourceFs = sourceFsBuilder.build();
    boolean shouldIncludeMetadataPathMakesNoDifference = true;
    IcebergDataset icebergDataset = new IcebergDataset(srcIcebergTable, null, new Properties(), sourceFs, shouldIncludeMetadataPathMakesNoDifference);

    MockFileSystemBuilder destFsBuilder = new MockFileSystemBuilder(DEST_FS_URI);
    FileSystem destFs = destFsBuilder.build();
    Mockito.doThrow(new IOException("Ha - not so fast!")).when(destFs).getFileStatus(new Path(SNAPSHOT_PATHS_0.manifestListPath));
    CopyConfiguration copyConfiguration = createEmptyCopyConfiguration(destFs);
    icebergDataset.getFilePathsToFileStatus(destFs, copyConfiguration, shouldIncludeMetadataPathMakesNoDifference);
  }

  /** Validate error consolidation used to streamline logging. */
  @Test
  public void testPathErrorConsolidator() {
    IcebergDataset.PathErrorConsolidator pec = IcebergDataset.createPathErrorConsolidator();
    Optional<String> msg0 = pec.prepLogMsg(new Path("/a/b/c/file0"));
    Assert.assertTrue(msg0.isPresent());
    Assert.assertEquals(msg0.get(), "path  not found: '/a/b/c/file0'");
    Optional<String> msg1 = pec.prepLogMsg(new Path("/a/b/c/file1"));
    Assert.assertTrue(msg1.isPresent());
    Assert.assertEquals(msg1.get(), "paths not found: '/a/b/c/...'");
    Optional<String> msg2 = pec.prepLogMsg(new Path("/a/b/c/file2"));
    Assert.assertFalse(msg2.isPresent());
    Optional<String> msg3 = pec.prepLogMsg(new Path("/a/b/c-other/file0"));
    Assert.assertTrue(msg3.isPresent());
  }

  /**
   * Test case to generate copy entities for all the file paths for a mocked iceberg table.
   * The assumption here is that we create copy entities for all the matching file paths,
   * without calculating any difference between the source and destination
   */
  @Test
  public void testGenerateCopyEntitiesWhenDestEmpty() throws IOException {
    List<String> expectedPaths = Arrays.asList(METADATA_PATH, MANIFEST_LIST_PATH_0,
        MANIFEST_PATH_0, MANIFEST_DATA_PATH_0A, MANIFEST_DATA_PATH_0B);
    MockFileSystemBuilder sourceBuilder = new MockFileSystemBuilder(SRC_FS_URI);
    sourceBuilder.addPaths(expectedPaths);
    FileSystem sourceFs = sourceBuilder.build();

    TableIdentifier tableIdInCommon = TableIdentifier.of(testDbName, testTblName);
    IcebergTable srcIcebergTbl = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_0));
    IcebergTable destIcebergTbl = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_1));
    boolean shouldIncludeManifestPath = true;
    IcebergDataset icebergDataset = new TrickIcebergDataset(srcIcebergTbl, destIcebergTbl, new Properties(), sourceFs, shouldIncludeManifestPath);

    MockFileSystemBuilder destBuilder = new MockFileSystemBuilder(DEST_FS_URI);
    FileSystem destFs = destBuilder.build();

    CopyConfiguration copyConfiguration =
        CopyConfiguration.builder(destFs, copyConfigProperties).preserve(PreserveAttributes.fromMnemonicString(""))
            .copyContext(new CopyContext()).build();
    Collection<CopyEntity> copyEntities = icebergDataset.generateCopyEntities(destFs, copyConfiguration);
    verifyCopyEntities(copyEntities, expectedPaths);
  }

  /** Test generating copy entities for a multi-snapshot iceberg; given empty dest, src-dest delta will be entirety */
  @Test
  public void testGenerateCopyEntitiesMultiSnapshotWhenDestEmpty() throws IOException {
    List<String> expectedPaths = Arrays.asList( // METADATA_PATH,
        MANIFEST_LIST_PATH_0, MANIFEST_PATH_0, MANIFEST_DATA_PATH_0A, MANIFEST_DATA_PATH_0B,
        MANIFEST_LIST_PATH_1, MANIFEST_PATH_1, MANIFEST_DATA_PATH_1A, MANIFEST_DATA_PATH_1B);

    MockFileSystemBuilder sourceBuilder = new MockFileSystemBuilder(SRC_FS_URI);
    sourceBuilder.addPaths(expectedPaths);
    FileSystem sourceFs = sourceBuilder.build();

    TableIdentifier tableIdInCommon = TableIdentifier.of(testDbName, testTblName);
    IcebergTable srcIcebergTable = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_1, SNAPSHOT_PATHS_0));
    IcebergTable destIcebergTable = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_1));
    boolean shouldIncludeManifestPath = false;
    IcebergDataset icebergDataset = new TrickIcebergDataset(srcIcebergTable, destIcebergTable, new Properties(), sourceFs, shouldIncludeManifestPath);

    MockFileSystemBuilder destBuilder = new MockFileSystemBuilder(DEST_FS_URI);
    FileSystem destFs = destBuilder.build();

    CopyConfiguration copyConfiguration =
        CopyConfiguration.builder(destFs, copyConfigProperties).preserve(PreserveAttributes.fromMnemonicString(""))
            .copyContext(new CopyContext()).build();
    Collection<CopyEntity> copyEntities = icebergDataset.generateCopyEntities(destFs, copyConfiguration);
    verifyCopyEntities(copyEntities, expectedPaths);
  }

  @Test
  public void testFsOwnershipAndPermissionPreservationWhenDestEmpty() throws IOException {
    FileStatus metadataFileStatus = new FileStatus(0, false, 0, 0, 0, 0, new FsPermission(FsAction.WRITE, FsAction.READ, FsAction.NONE), "metadata_owner", "metadata_group", null);
    FileStatus manifestFileStatus = new FileStatus(0, false, 0, 0, 0, 0, new FsPermission(FsAction.WRITE, FsAction.READ, FsAction.NONE), "manifest_list_owner", "manifest_list_group", null);
    FileStatus manifestDataFileStatus = new FileStatus(0, false, 0, 0, 0, 0, new FsPermission(FsAction.WRITE_EXECUTE, FsAction.READ_EXECUTE, FsAction.NONE), "manifest_data_owner", "manifest_data_group", null);
    Map<String, FileStatus> expectedPathsAndFileStatuses = Maps.newHashMap();
    expectedPathsAndFileStatuses.put(METADATA_PATH, metadataFileStatus);
    expectedPathsAndFileStatuses.put(MANIFEST_PATH_0, manifestFileStatus);
    expectedPathsAndFileStatuses.put(MANIFEST_LIST_PATH_0, manifestFileStatus);
    expectedPathsAndFileStatuses.put(MANIFEST_DATA_PATH_0A, manifestDataFileStatus);
    expectedPathsAndFileStatuses.put(MANIFEST_DATA_PATH_0B, manifestDataFileStatus);

    MockFileSystemBuilder sourceBuilder = new MockFileSystemBuilder(SRC_FS_URI);
    sourceBuilder.addPathsAndFileStatuses(expectedPathsAndFileStatuses);
    FileSystem sourceFs = sourceBuilder.build();

    TableIdentifier tableIdInCommon = TableIdentifier.of(testDbName, testTblName);
    IcebergTable srcIcebergTable = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_0));
    IcebergTable destIcebergTable = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_1));
    boolean shouldIncludeManifestPath = true;
    IcebergDataset icebergDataset = new TrickIcebergDataset(srcIcebergTable, destIcebergTable, new Properties(), sourceFs, shouldIncludeManifestPath);

    MockFileSystemBuilder destBuilder = new MockFileSystemBuilder(DEST_FS_URI);
    FileSystem destFs = destBuilder.build();

    CopyConfiguration copyConfiguration =
        CopyConfiguration.builder(destFs, copyConfigProperties)
            // preserving attributes for owner, group and permissions respectively
            .preserve(PreserveAttributes.fromMnemonicString("ugp"))
            .copyContext(new CopyContext()).build();
    Collection<CopyEntity> copyEntities = icebergDataset.generateCopyEntities(destFs, copyConfiguration);
    verifyFsOwnershipAndPermissionPreservation(copyEntities, sourceBuilder.getPathsAndFileStatuses());
  }

  @Test
  public void testFsOwnershipAndPermissionWithoutPreservationWhenDestEmpty() throws IOException {
    List<String> expectedPaths = Arrays.asList(METADATA_PATH, MANIFEST_LIST_PATH_0,
        MANIFEST_PATH_0, MANIFEST_DATA_PATH_0A, MANIFEST_DATA_PATH_0B);
    Map<Path, FileStatus> expectedPathsAndFileStatuses = Maps.newHashMap();
    for (String expectedPath : expectedPaths) {
      expectedPathsAndFileStatuses.putIfAbsent(new Path(expectedPath), new FileStatus());
    }
    MockFileSystemBuilder sourceBuilder = new MockFileSystemBuilder(SRC_FS_URI);
    sourceBuilder.addPaths(expectedPaths);
    FileSystem sourceFs = sourceBuilder.build();

    TableIdentifier tableIdInCommon = TableIdentifier.of(testDbName, testTblName);
    IcebergTable srcIcebergTable = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_0));
    IcebergTable destIcebergTable = MockIcebergTable.withSnapshots(tableIdInCommon, Arrays.asList(SNAPSHOT_PATHS_1));
    boolean shouldIncludeManifestPath = true;
    IcebergDataset icebergDataset = new TrickIcebergDataset(srcIcebergTable, destIcebergTable, new Properties(), sourceFs, shouldIncludeManifestPath);

    MockFileSystemBuilder destBuilder = new MockFileSystemBuilder(DEST_FS_URI);
    FileSystem destFs = destBuilder.build();

    CopyConfiguration copyConfiguration =
        CopyConfiguration.builder(destFs, copyConfigProperties)
            // without preserving attributes for owner, group and permissions
            .preserve(PreserveAttributes.fromMnemonicString(""))
            .copyContext(new CopyContext()).build();
    Collection<CopyEntity> copyEntities = icebergDataset.generateCopyEntities(destFs, copyConfiguration);
    verifyFsOwnershipAndPermissionPreservation(copyEntities, expectedPathsAndFileStatuses);
  }

  /**
   *  exercise {@link IcebergDataset::getFilePaths} and validate the result
   *  @return {@link IcebergTable} (mock!), for behavioral verification
   */
  protected IcebergTable validateGetFilePathsGivenDestState(List<MockIcebergTable.SnapshotPaths> sourceSnapshotPathSets,
      List<String> existingDestPaths, Set<Path> expectedResultPaths, boolean shouldIncludeMetadataPath) throws IOException {
    return validateGetFilePathsGivenDestState(sourceSnapshotPathSets, Optional.empty(), existingDestPaths,
        expectedResultPaths, shouldIncludeMetadataPath);
  }

  /**
   *  exercise {@link IcebergDataset::getFilePaths} and validate the result
   *  @return {@link IcebergTable} (mock!), for behavioral verification
   */
  protected IcebergTable validateGetFilePathsGivenDestState(List<MockIcebergTable.SnapshotPaths> sourceSnapshotPathSets,
      Optional<List<String>> optExistingSourcePaths, List<String> existingDestPaths, Set<Path> expectedResultPaths,
      boolean shouldIncludeMetadataPath) throws IOException {
    IcebergTable srcIcebergTable = MockIcebergTable.withSnapshots(TableIdentifier.of(testDbName, testTblName), sourceSnapshotPathSets);

    MockFileSystemBuilder sourceFsBuilder = new MockFileSystemBuilder(SRC_FS_URI, !optExistingSourcePaths.isPresent());
    optExistingSourcePaths.ifPresent(sourceFsBuilder::addPaths);
    FileSystem sourceFs = sourceFsBuilder.build();
    IcebergDataset icebergDataset = new IcebergDataset(srcIcebergTable, null, new Properties(), sourceFs, shouldIncludeMetadataPath);

    MockFileSystemBuilder destFsBuilder = new MockFileSystemBuilder(DEST_FS_URI);
    destFsBuilder.addPaths(existingDestPaths);
    FileSystem destFs = destFsBuilder.build();
    CopyConfiguration copyConfiguration = createEmptyCopyConfiguration(destFs);

    IcebergDataset.GetFilePathsToFileStatusResult pathsResult = icebergDataset.getFilePathsToFileStatus(destFs, copyConfiguration, shouldIncludeMetadataPath);
    Map<Path, FileStatus> filePathsToFileStatus = pathsResult.getPathsToFileStatus();
    Assert.assertEquals(filePathsToFileStatus.keySet(), expectedResultPaths);
    // verify solely the path portion of the `FileStatus`, since that's all mock sets up
    Assert.assertEquals(
        filePathsToFileStatus.values().stream().map(FileStatus::getPath).collect(Collectors.toSet()),
        expectedResultPaths);
    return srcIcebergTable;
  }

  /** @return `paths` after adding to it all paths of every one of `snapshotDefs` */
  protected static Set<Path> withAllSnapshotPaths(Set<Path> paths, boolean shouldIncludeMetadataPath, MockIcebergTable.SnapshotPaths... snapshotDefs) {
    Arrays.stream(snapshotDefs).flatMap(snapshotDef ->
            snapshotDef.asSnapshotInfo().getAllPaths(shouldIncludeMetadataPath).stream())
        .forEach(p ->
            paths.add(new Path(p))
        );
    return paths;
  }

  private CopyConfiguration createEmptyCopyConfiguration(FileSystem fs) {
    return CopyConfiguration.builder(fs, copyConfigProperties).copyContext(new CopyContext()).build();
  }

  private static void verifyCopyEntities(Collection<CopyEntity> copyEntities, List<String> expected) {
    List<String> actual = new ArrayList<>();
    for (CopyEntity copyEntity : copyEntities) {
      String json = copyEntity.toString();
      if (isCopyableFile(json)) {
        String filepath = CopyEntityDeserializer.getOriginFilePathAsStringFromJson(json);
        actual.add(filepath);
      } else{
        verifyPostPublishStep(json, REGISTER_COMMIT_STEP);
      }
    }
    Assert.assertEquals(actual.size(), expected.size(), "Set" + actual.toString() + " vs Set" + expected.toString());
    Assert.assertEqualsNoOrder(actual.toArray(), expected.toArray());
  }

  public static boolean isCopyableFile(String json) {
    String objectType = new Gson().fromJson(json, JsonObject.class)
        .getAsJsonPrimitive("object-type")
        .getAsString();
    return objectType.equals("org.apache.gobblin.data.management.copy.CopyableFile");
  }

  private static void verifyFsOwnershipAndPermissionPreservation(Collection<CopyEntity> copyEntities, Map<Path, FileStatus> expectedPathsAndFileStatuses) {
    for (CopyEntity copyEntity : copyEntities) {
      String copyEntityJson = copyEntity.toString();
      if (isCopyableFile(copyEntityJson)) {
        List<CopyEntityDeserializer.FileOwnerAndPermissions> ancestorFileOwnerAndPermissionsList =
            CopyEntityDeserializer.getAncestorOwnerAndPermissions(copyEntityJson);
        CopyEntityDeserializer.FileOwnerAndPermissions destinationFileOwnerAndPermissions = CopyEntityDeserializer.getDestinationOwnerAndPermissions(copyEntityJson);
        Path filePath = new Path(CopyEntityDeserializer.getOriginFilePathAsStringFromJson(copyEntityJson));
        FileStatus fileStatus = expectedPathsAndFileStatuses.get(filePath);
        verifyFileStatus(destinationFileOwnerAndPermissions, fileStatus);
        // providing path's parent to verify ancestor owner and permissions
        verifyAncestorPermissions(ancestorFileOwnerAndPermissionsList, filePath.getParent(),
            expectedPathsAndFileStatuses);
      } else {
        verifyPostPublishStep(copyEntityJson, REGISTER_COMMIT_STEP);
      }
    }
  }

  private static void verifyFileStatus(CopyEntityDeserializer.FileOwnerAndPermissions actual, FileStatus expected) {
    Assert.assertEquals(actual.owner, expected.getOwner());
    Assert.assertEquals(actual.group, expected.getGroup());
    Assert.assertEquals(actual.userActionPermission, expected.getPermission().getUserAction().toString());
    Assert.assertEquals(actual.groupActionPermission, expected.getPermission().getGroupAction().toString());
    Assert.assertEquals(actual.otherActionPermission, expected.getPermission().getOtherAction().toString());
  }

  private static void verifyAncestorPermissions(List<CopyEntityDeserializer.FileOwnerAndPermissions> actualList, Path path, Map<Path, FileStatus> pathFileStatusMap) {

    for (CopyEntityDeserializer.FileOwnerAndPermissions actual : actualList) {
      FileStatus expected = pathFileStatusMap.getOrDefault(path, new FileStatus());
      verifyFileStatus(actual, expected);
      path = path.getParent();
    }
  }

  public static void verifyPostPublishStep(String json, String expectedCommitStep) {
    String actualCommitStep = new Gson().fromJson(json, JsonObject.class)
        .getAsJsonObject("object-data").getAsJsonObject("step").getAsJsonPrimitive("object-type").getAsString();
    Assert.assertEquals(actualCommitStep, expectedCommitStep);
  }

  /**
   *  Without this, our {@link FileSystem} mock would be lost by replacement from the {@link FileSystem#get} static, and
   *  that would prevent tests from effectively setting up certain source paths as existing.
   *  Instead override {@link IcebergDataset#getSourceFileSystemFromFileStatus(FileStatus, Configuration)} so that static
   *  is avoided entirely.
   */
  protected static class TrickIcebergDataset extends IcebergDataset {
    public TrickIcebergDataset(IcebergTable srcIcebergTable, IcebergTable destIcebergTable, Properties properties,
        FileSystem sourceFs, boolean shouldIncludeManifestPath) {
      super(srcIcebergTable, destIcebergTable, properties, sourceFs, shouldIncludeManifestPath);
    }

    @Override // as the `static` itself is not directly mock-able
    protected FileSystem getSourceFileSystemFromFileStatus(FileStatus fileStatus, Configuration hadoopConfig) throws IOException {
      return this.sourceFs;
    }
  }

  ;

  /** Builds a {@link FileSystem} mock */
  protected static class MockFileSystemBuilder {
    private final URI fsURI;
    /** when not `.isPresent()`, all paths exist; when `.get().isEmpty()`, none exist; else only those indicated do */
    private final Optional<Map<Path, FileStatus>> optPathsWithFileStatuses;

    public MockFileSystemBuilder(URI fsURI) {
      this(fsURI, false);
    }

    public MockFileSystemBuilder(URI fsURI, boolean shouldRepresentEveryPath) {
      this.fsURI = fsURI;
      this.optPathsWithFileStatuses = shouldRepresentEveryPath ? Optional.empty() : Optional.of(Maps.newHashMap());
    }

    public void addPaths(List<String> pathStrings) {
      Map<String, FileStatus> map = Maps.newHashMap();
      for (String pathString : pathStrings) {
        map.putIfAbsent(pathString, null);
      }
      addPathsAndFileStatuses(map);
    }

    public void addPathsAndFileStatuses(Map<String, FileStatus> pathAndFileStatuses) {
      for (Map.Entry<String, FileStatus> entry : pathAndFileStatuses.entrySet()) {
        String pathString = entry.getKey();
        FileStatus fileStatus = entry.getValue();
        addPathsAndFileStatuses(pathString, fileStatus);
      }
    }

    public void addPathsAndFileStatuses(String pathString, FileStatus fileStatus) {
      Path path = new Path(pathString);
      if(fileStatus != null) { fileStatus.setPath(path);}
      addPathAndFileStatus(path, fileStatus);
    }

    public void addPathAndFileStatus(Path path, FileStatus fileStatus) {
      if (!this.optPathsWithFileStatuses.isPresent()) {
        throw new IllegalStateException("unable to add paths and file statuses when constructed");
      }
      optPathsWithFileStatuses.get().putIfAbsent(path, fileStatus);
      if (!path.isRoot()) { // recursively add ancestors of a previously unknown path
        addPathAndFileStatus(path.getParent(), fileStatus);
      }
    }

    public Map<Path, FileStatus> getPathsAndFileStatuses() {
      return optPathsWithFileStatuses.get();
    }

    public FileSystem build()
        throws IOException {
      FileSystem fs = Mockito.mock(FileSystem.class);
      Mockito.when(fs.getUri()).thenReturn(fsURI);
      Mockito.when(fs.makeQualified(any(Path.class)))
          .thenAnswer(invocation -> invocation.getArgument(0, Path.class).makeQualified(fsURI, new Path("/")));

      if (!this.optPathsWithFileStatuses.isPresent()) {
        Mockito.when(fs.getFileStatus(any(Path.class)))
            .thenAnswer(invocation -> createEmptyFileStatus(invocation.getArgument(0, Path.class).toString()));
      } else {
        // WARNING: order is critical--specific paths *after* `any(Path)`; in addition, since mocking further
        // an already-mocked instance, `.doReturn/.when` is needed (vs. `.when/.thenReturn`)
        Mockito.when(fs.getFileStatus(any(Path.class))).thenThrow(new FileNotFoundException());
        for (Map.Entry<Path, FileStatus> entry : this.optPathsWithFileStatuses.get().entrySet()) {
          Path p = entry.getKey();
          FileStatus fileStatus = entry.getValue();
          Mockito.doReturn(fileStatus != null ? fileStatus : createEmptyFileStatus(p.toString())).when(fs).getFileStatus(p);
        }
      }
      return fs;
    }

    public static FileStatus createEmptyFileStatus(String pathString) throws IOException {
      Path path = new Path(pathString);
      FileStatus fileStatus = new FileStatus();
      fileStatus.setPath(path);
      return fileStatus;
    }
  }

  private static class MockIcebergTable {

    @Data
    public static class SnapshotPaths {
      private final Optional<String> metadataPath;
      private final String manifestListPath;
      private final List<IcebergSnapshotInfo.ManifestFileInfo> manifestFiles;

      private static final TableMetadata unusedStubMetadata = Mockito.mock(TableMetadata.class);

      public IcebergSnapshotInfo asSnapshotInfo() {
        return asSnapshotInfo(0L);
      }

      /** @param snapshotIdIndex used both as snapshot ID and as snapshot (epoch) timestamp */
      public IcebergSnapshotInfo asSnapshotInfo(long snapshotIdIndex) {
        return asSnapshotInfo(snapshotIdIndex, Instant.ofEpochMilli(snapshotIdIndex));
      }

      public IcebergSnapshotInfo asSnapshotInfo(Long snapshotId, Instant timestamp) {
        return new IcebergSnapshotInfo(snapshotId, timestamp, this.metadataPath,
            this.metadataPath.map(ignore -> unusedStubMetadata), // only set when `metadataPath.isPresent()`
            this.manifestListPath, this.manifestFiles);
      }
    }

    public static IcebergTable withSnapshots(TableIdentifier tableId, List<SnapshotPaths> snapshotPathSets) throws IOException {
      IcebergTable table = Mockito.mock(IcebergTable.class);
      Mockito.when(table.getTableId()).thenReturn(tableId);
      int lastIndex = snapshotPathSets.size() - 1;
      Mockito.when(table.getCurrentSnapshotInfoOverviewOnly())
          .thenReturn(snapshotPathSets.get(lastIndex).asSnapshotInfo(lastIndex));
      // ADMISSION: this is strictly more analogous to `IcebergTable.getAllSnapshotInfosIterator()`, as it doesn't
      // filter only the delta... nonetheless, it should work fine for the tests herein
      Mockito.when(table.getIncrementalSnapshotInfosIterator()).thenReturn(
          IndexingStreams.transformWithIndex(snapshotPathSets.stream(),
              (pathSet, i) -> pathSet.asSnapshotInfo(i)).iterator());
      return table;
    }
  }

  public static class IndexingStreams {
    /** @return {@link Stream} equivalent of `inputs.zipWithIndex.map(f)` in scala */
    public static <T, R> Stream<R> transformWithIndex(Stream<T> inputs, BiFunction<T, Integer, R> f) {
      // given sketchy import, sequester for now within enclosing test class, rather than adding to `gobblin-utility`
      return Streams.zip(
          inputs, IntStream.iterate(0, i -> i + 1).boxed(), f);
    }
  }

  protected static class CopyEntityDeserializer {

    @Data
    public static class FileOwnerAndPermissions {
      String owner;
      String group;
      // assigning default values
      String userActionPermission = FsAction.valueOf("READ_WRITE").toString();
      String groupActionPermission = FsAction.valueOf("READ_WRITE").toString();
      String otherActionPermission = FsAction.valueOf("READ_WRITE").toString();
    }

    public static String getOriginFilePathAsStringFromJson(String json) {
      return new Gson().fromJson(json, JsonObject.class)
          .getAsJsonObject("object-data")
          .getAsJsonObject("origin")
          .getAsJsonObject("object-data").getAsJsonObject("path").getAsJsonObject("object-data")
          .getAsJsonObject("uri").getAsJsonPrimitive("object-data").getAsString();
    }

    public static String getDestinationFilePathAsStringFromJson(String json) {
      return new Gson().fromJson(json, JsonObject.class)
          .getAsJsonObject("object-data")
          .getAsJsonObject("destination")
          .getAsJsonObject("object-data")
          .getAsJsonObject("uri").getAsJsonPrimitive("object-data").getAsString();
    }

    public static List<FileOwnerAndPermissions> getAncestorOwnerAndPermissions(String json) {
      JsonArray ancestorsOwnerAndPermissions = new Gson().fromJson(json, JsonObject.class)
              .getAsJsonObject("object-data")
              .getAsJsonArray("ancestorsOwnerAndPermission");
      List<FileOwnerAndPermissions> fileOwnerAndPermissionsList = Lists.newArrayList();
      for (JsonElement jsonElement : ancestorsOwnerAndPermissions) {
        fileOwnerAndPermissionsList.add(getFileOwnerAndPermissions(jsonElement.getAsJsonObject()));
      }
      return fileOwnerAndPermissionsList;
    }

    public static FileOwnerAndPermissions getDestinationOwnerAndPermissions(String json) {
      JsonObject destinationOwnerAndPermissionsJsonObject = new Gson().fromJson(json, JsonObject.class)
          .getAsJsonObject("object-data")
          .getAsJsonObject("destinationOwnerAndPermission");
      FileOwnerAndPermissions fileOwnerAndPermissions = getFileOwnerAndPermissions(destinationOwnerAndPermissionsJsonObject);
      return fileOwnerAndPermissions;
    }

    private static FileOwnerAndPermissions getFileOwnerAndPermissions(JsonObject jsonObject) {
      FileOwnerAndPermissions fileOwnerAndPermissions = new FileOwnerAndPermissions();
      JsonObject objData = jsonObject.getAsJsonObject("object-data");
      fileOwnerAndPermissions.owner = objData.has("owner") ? objData.getAsJsonPrimitive("owner").getAsString() : "";
      fileOwnerAndPermissions.group = objData.has("group") ? objData.getAsJsonPrimitive("group").getAsString() : "";

      JsonObject fsPermission = objData.has("fsPermission") ? objData.getAsJsonObject("fsPermission") : null;
      if (fsPermission != null) {
        JsonObject objectData = fsPermission.getAsJsonObject("object-data");
        fileOwnerAndPermissions.userActionPermission =
            objectData.getAsJsonObject("useraction").getAsJsonPrimitive("object-data").getAsString();
        fileOwnerAndPermissions.groupActionPermission =
            objectData.getAsJsonObject("groupaction").getAsJsonPrimitive("object-data").getAsString();
        fileOwnerAndPermissions.otherActionPermission =
            objectData.getAsJsonObject("otheraction").getAsJsonPrimitive("object-data").getAsString();
      }
      return fileOwnerAndPermissions;
    }
  }
}

