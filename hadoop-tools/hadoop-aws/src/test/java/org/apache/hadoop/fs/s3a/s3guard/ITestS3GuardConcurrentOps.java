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

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;

import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.apache.hadoop.fs.s3a.AbstractS3ATestBase;
import org.apache.hadoop.fs.s3a.Constants;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests concurrent operations on S3Guard.
 */
public class ITestS3GuardConcurrentOps extends AbstractS3ATestBase {

  @Rule
  public final Timeout timeout = new Timeout(5 * 60 * 1000);

  private void failIfTableExists(DynamoDB db, String tableName) {
    boolean tableExists = true;
    try {
      Table table = db.getTable(tableName);
      table.describe();
    } catch (ResourceNotFoundException e) {
      tableExists = false;
    }
    if (tableExists) {
      fail("Table already exists: " + tableName);
    }
  }

  private void deleteTable(DynamoDB db, String tableName) throws
      InterruptedException {
    Table table = db.getTable(tableName);
    table.waitForActive();
    table.delete();
    table.waitForDelete();
  }

  @Test
  public void testConcurrentTableCreations() throws Exception {
    final Configuration conf = getConfiguration();
    Assume.assumeTrue("Test only applies when DynamoDB is used for S3Guard",
        conf.get(Constants.S3_METADATA_STORE_IMPL).equals(
            Constants.S3GUARD_METASTORE_DYNAMO));

    DynamoDBMetadataStore ms = new DynamoDBMetadataStore();
    ms.initialize(getFileSystem());
    DynamoDB db = ms.getDynamoDB();

    String tableName = "testConcurrentTableCreations" + new Random().nextInt();
    conf.setBoolean(Constants.S3GUARD_DDB_TABLE_CREATE_KEY, true);
    conf.set(Constants.S3GUARD_DDB_TABLE_NAME_KEY, tableName);
    int concurrentOps = 16;
    int iterations = 4;

    failIfTableExists(db, tableName);

    for (int i = 0; i < iterations; i++) {
      ExecutorService executor = Executors.newFixedThreadPool(
          concurrentOps, new ThreadFactory() {
            private AtomicInteger count = new AtomicInteger(0);

            public Thread newThread(Runnable r) {
              return new Thread(r,
                  "testConcurrentTableCreations" + count.getAndIncrement());
            }
          });
      ((ThreadPoolExecutor) executor).prestartAllCoreThreads();
      Future<Boolean>[] futures = new Future[concurrentOps];
      int exceptionsThrown = 0;
      for (int f = 0; f < concurrentOps; f++) {
        final int index = f;
        futures[f] = executor.submit(new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            ContractTestUtils.NanoTimer timer = new ContractTestUtils.NanoTimer();

            boolean result = false;
            try {
              new DynamoDBMetadataStore().initialize(conf);
            } catch (Exception e) {
              LOG.error(e.getClass() + ": " + e.getMessage());
              result = true;
            }

            timer.end("parallel DynamoDB client creation %d", index);
            LOG.info("Parallel DynamoDB client creation {} ran from {} to {}",
                index, timer.getStartTime(), timer.getEndTime());
            return result;
          }
        });
      }
      for (int f = 0; f < concurrentOps; f++) {
        if (futures[f].get()) {
          exceptionsThrown++;
        }
      }
      deleteTable(db, tableName);
      if (exceptionsThrown > 0) {
        fail(exceptionsThrown + "/" + concurrentOps +
            " threads threw exceptions while initializing on iteration " + i);
      }
    }
  }
}
