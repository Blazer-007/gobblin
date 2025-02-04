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

package org.apache.gobblin.temporal.ddm.activity;

import java.time.Duration;
import java.util.Properties;

import org.apache.gobblin.temporal.GobblinTemporalConfigurationKeys;
import org.apache.gobblin.util.PropertiesUtils;


/**
 * Interface for defining activity configuration strategies for different Temporal activities.
 * Each strategy provides a method to retrieve configuration details, such as timeout duration, based on the provided properties.
 */
public interface ActivityConfigurationStrategy {
  /** Default start to close timeout duration for any activity if not specified. */
  Duration defaultStartToCloseTimeout = Duration.ofMinutes(180);
  int DEFAULT_GENERATE_WORKUNITS_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES = 120;
  int DEFAULT_RECOMMEND_SCALING_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES = 5;
  int DEFAULT_DELETE_WORK_DIRS_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES = 10;
  int DEFAULT_PROCESS_WORKUNIT_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES = 180;
  int DEFAULT_COMMIT_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES = 180;

  /**
   * Retrieves the start to close timeout duration for an activity based on the provided properties.
   *
   * @param props the properties to be used for configuring the timeout.
   * @return the timeout duration for the activity.
   */
  Duration getStartToCloseTimeout(Properties props);

  /**
   * Configuration strategy for the Generate Workunits activity.
   */
  class GenerateWorkunitsActivityConfigurationStrategy implements ActivityConfigurationStrategy {
    @Override
    public Duration getStartToCloseTimeout(Properties props) {
      return Duration.ofMinutes(PropertiesUtils.getPropAsInt(
          props,
          GobblinTemporalConfigurationKeys.GENERATE_WORKUNITS_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES,
          DEFAULT_GENERATE_WORKUNITS_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES
      ));
    }
  }

  /**
   * Configuration strategy for the Recommend Scaling activity.
   */
  class RecommendScalingActivityConfigurationStrategy implements ActivityConfigurationStrategy {
    @Override
    public Duration getStartToCloseTimeout(Properties props) {
      return Duration.ofMinutes(PropertiesUtils.getPropAsInt(
          props,
          GobblinTemporalConfigurationKeys.RECOMMEND_SCALING_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES,
          DEFAULT_RECOMMEND_SCALING_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES
      ));
    }
  }

  /**
   * Configuration strategy for the Delete Work Dirs activity.
   */
  class DeleteWorkDirsActivityConfigurationStrategy implements ActivityConfigurationStrategy {
    @Override
    public Duration getStartToCloseTimeout(Properties props) {
      return Duration.ofMinutes(PropertiesUtils.getPropAsInt(
          props,
          GobblinTemporalConfigurationKeys.DELETE_WORK_DIRS_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES,
          DEFAULT_DELETE_WORK_DIRS_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES
      ));
    }
  }

  /**
   * Configuration strategy for the Process Workunit activity.
   */
  class ProcessWorkunitActivityConfigurationStrategy implements ActivityConfigurationStrategy {
    @Override
    public Duration getStartToCloseTimeout(Properties props) {
      return Duration.ofMinutes(PropertiesUtils.getPropAsInt(
          props,
          GobblinTemporalConfigurationKeys.PROCESS_WORKUNIT_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES,
          DEFAULT_PROCESS_WORKUNIT_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES
      ));
    }
  }

  /**
   * Configuration strategy for the Commit activity.
   */
  class CommitActivityConfigurationStrategy implements ActivityConfigurationStrategy {
    @Override
    public Duration getStartToCloseTimeout(Properties props) {
      return Duration.ofMinutes(PropertiesUtils.getPropAsInt(
          props,
          GobblinTemporalConfigurationKeys.COMMIT_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES,
          DEFAULT_COMMIT_ACTIVITY_STARTTOCLOSE_TIMEOUT_MINUTES
      ));
    }
  }
}
