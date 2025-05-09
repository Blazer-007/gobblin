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

package org.apache.gobblin.writer.initializer;

import java.util.Optional;
import java.util.List;

import lombok.ToString;

import org.apache.gobblin.initializer.Initializer;
import org.apache.gobblin.initializer.MultiInitializer;


@ToString
public class MultiWriterInitializer implements WriterInitializer {

  private final Initializer initializer;

  public MultiWriterInitializer(List<WriterInitializer> writerInitializers) {
    this.initializer = new MultiInitializer(writerInitializers);
  }

  @Override
  public void initialize() {
    this.initializer.initialize();
  }

  @Override
  public void close() {
    this.initializer.close();
  }

  @Override
  public Optional<AfterInitializeMemento> commemorate() {
    return this.initializer.commemorate();
  }

  @Override
  public void recall(AfterInitializeMemento memento) {
    this.initializer.recall(memento);
  }
}
