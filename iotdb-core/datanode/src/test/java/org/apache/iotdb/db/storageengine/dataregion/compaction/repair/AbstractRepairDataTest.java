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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.storageengine.dataregion.compaction.repair;

import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.storageengine.dataregion.compaction.AbstractCompactionTest;
import org.apache.iotdb.db.utils.constant.TestConstant;

import org.apache.tsfile.exception.write.WriteProcessException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class AbstractRepairDataTest extends AbstractCompactionTest {

  private File repairDataLogDir;

  @Override
  public void setUp()
      throws IOException, WriteProcessException, MetadataException, InterruptedException {
    super.setUp();
    repairDataLogDir = new File(TestConstant.BASE_OUTPUT_PATH + File.separator + "repair");
  }

  @Override
  public void tearDown() throws IOException, StorageEngineException {
    super.tearDown();
    deleteRepairDataLogDir();
  }

  public File getEmptyRepairDataLogDir() throws IOException {
    deleteRepairDataLogDir();
    Files.createDirectory(repairDataLogDir.toPath());
    return repairDataLogDir;
  }

  private void deleteRepairDataLogDir() throws IOException {
    if (repairDataLogDir.exists()) {
      if (repairDataLogDir.isDirectory()) {
        File[] files = repairDataLogDir.listFiles();
        for (File file : files == null ? new File[] {} : files) {
          Files.deleteIfExists(file.toPath());
        }
      }
      Files.deleteIfExists(repairDataLogDir.toPath());
    }
  }
}
