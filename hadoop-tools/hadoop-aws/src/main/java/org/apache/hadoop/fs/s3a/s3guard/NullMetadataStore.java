/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.s3guard;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.util.Collection;

/**
 * A no-op implementation of MetadataStore.  Clients that use this
 * implementation should behave the same as they would without any
 * MetadataStore.
 */
public class NullMetadataStore implements MetadataStore {

  @Override
  public void initialize(FileSystem fs) throws IOException {
    return;
  }

  @Override
  public void initialize(Configuration conf) throws IOException {
  }

  @Override
  public void close() throws IOException {
    return;
  }

  @Override
  public void delete(Path path) throws IOException {
    return;
  }

  @Override
  public void forgetMetadata(Path path) throws IOException {
    return;
  }

  @Override
  public void deleteSubtree(Path path) throws IOException {
    return;
  }

  @Override
  public PathMetadata get(Path path) throws IOException {
    return null;
  }

  @Override
  public PathMetadata get(Path path, boolean wantEmptyDirectoryFlag)
      throws IOException {
    return null;
  }

  @Override
  public DirListingMetadata listChildren(Path path) throws IOException {
    return null;
  }

  @Override
  public void move(Collection<Path> pathsToDelete,
      Collection<PathMetadata> pathsToCreate) throws IOException {
    return;
  }

  @Override
  public void put(PathMetadata meta) throws IOException {
    return;
  }

  @Override
  public void put(DirListingMetadata meta) throws IOException {
    return;
  }

  @Override
  public void destroy() throws IOException {
  }

  @Override
  public void prune(long modTime) {
    return;
  }

  @Override
  public String toString() {
    return "NullMetadataStore";
  }
}
