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

package org.apache.gobblin.temporal.ddm.work;

import java.net.URI;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.apache.gobblin.source.workunit.WorkUnit;
import org.apache.gobblin.temporal.workflows.metrics.EventSubmitterContext;
import org.apache.gobblin.util.Id;
import org.apache.gobblin.util.WorkUnitSizeInfo;


/**
 * {@link AbstractEagerFsDirBackedWorkload} for {@link WorkUnitClaimCheck} `WORK_ITEM`s, which uses {@link WorkUnitClaimCheck#getWorkUnitPath()}
 * for their total-ordering.
 */
@lombok.NoArgsConstructor // IMPORTANT: for jackson (de)serialization
@lombok.ToString(callSuper = true)
@Slf4j
public class EagerFsDirBackedWorkUnitClaimCheckWorkload extends AbstractEagerFsDirBackedWorkload<WorkUnitClaimCheck> {
  private EventSubmitterContext eventSubmitterContext;

  private Properties fileSystemProperties;

  public EagerFsDirBackedWorkUnitClaimCheckWorkload(URI fileSystemUri, String hdfsDir, EventSubmitterContext eventSubmitterContext, Properties fileSystemProperties) {
    super(fileSystemUri, hdfsDir);
    this.eventSubmitterContext = eventSubmitterContext;
    this.fileSystemProperties = fileSystemProperties;
  }

  @Override
  protected WorkUnitClaimCheck fromFileStatus(FileStatus fileStatus) {
    // begin by setting all correlators to empty string - later we'll `acknowledgeOrdering()`
    Path filePath = fileStatus.getPath();
    return new WorkUnitClaimCheck("", this.getFileSystemUri(), filePath.toString(), extractTunneledWorkUnitSizeInfo(filePath), this.eventSubmitterContext, fileSystemProperties);
  }

  @Override
  @JsonIgnore // (because no-arg method resembles 'java bean property')
  protected Comparator<WorkUnitClaimCheck> getWorkItemComparator() {
    return Comparator.comparing(WorkUnitClaimCheck::getWorkUnitPath);
  }

  @Override
  protected void acknowledgeOrdering(int index, WorkUnitClaimCheck item) {
    // later, after the post-total-ordering indices are know, use each item's index as its correlator
    item.setCorrelator(Integer.toString(index));
  }

  /**
   * @return the {@link WorkUnitSizeInfo}, when encoded in the filename; otherwise {@link WorkUnitSizeInfo#empty()} when no size info about {@link WorkUnit}
   * @see org.apache.gobblin.util.JobLauncherUtils.WorkUnitPathCalculator#calcNextPathWithTunneledSizeInfo(WorkUnit, String, Path)
   */
  protected static WorkUnitSizeInfo extractTunneledWorkUnitSizeInfo(Path filePath) {
    String fileName = filePath.getName();
    Optional<WorkUnitSizeInfo> optSizeInfo = Optional.empty();
    try {
      String maybeEncodedSizeInfo = Id.parse(fileName.substring(0, fileName.lastIndexOf('.'))).getName(); // strip extension
      optSizeInfo = WorkUnitSizeInfo.decode(maybeEncodedSizeInfo);
    } catch (Exception e) { // log, but swallow any `Id.parse` error
      log.warn("Filename NOT `Id.parse`able: '" + filePath + "' - " + e.getMessage());
    }
    return optSizeInfo.orElse(WorkUnitSizeInfo.empty());
  }
}
