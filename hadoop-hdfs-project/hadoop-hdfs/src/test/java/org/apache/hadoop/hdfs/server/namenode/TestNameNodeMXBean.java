/**
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
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.CombinedHostFileManager;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeManager;
import org.apache.hadoop.hdfs.server.blockmanagement.HostConfigManager;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.top.TopConf;
import org.apache.hadoop.hdfs.util.HostsFileWriter;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.io.nativeio.NativeIO.POSIX.NoMlockCacheManipulator;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.VersionInfo;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;
import org.mortbay.util.ajax.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;

/**
 * Class for testing {@link NameNodeMXBean} implementation
 */
public class TestNameNodeMXBean {

  private static final Logger LOG =
      LoggerFactory.getLogger(TestNameNodeMXBean.class);

  /**
   * Used to assert equality between doubles
   */
  private static final double DELTA = 0.000001;

  static {
    NativeIO.POSIX.setCacheManipulator(new NoMlockCacheManipulator());
  }

  @SuppressWarnings({ "unchecked" })
  @Test
  public void testNameNodeMXBeanInfo() throws Exception {
    Configuration conf = new Configuration();
    conf.setLong(DFSConfigKeys.DFS_DATANODE_MAX_LOCKED_MEMORY_KEY,
        NativeIO.POSIX.getCacheManipulator().getMemlockLimit());
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY, 1);

    MiniDFSCluster cluster = null;

    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
      cluster.waitActive();

      // Set upgrade domain on the first DN.
      String upgradeDomain = "abcd";
      DatanodeManager dm = cluster.getNameNode().getNamesystem().
          getBlockManager().getDatanodeManager();
      DatanodeDescriptor dd = dm.getDatanode(
          cluster.getDataNodes().get(0).getDatanodeId());
      dd.setUpgradeDomain(upgradeDomain);
      String dnXferAddrWithUpgradeDomainSet = dd.getXferAddr();

      // Put the second DN to maintenance state.
      DatanodeDescriptor maintenanceNode = dm.getDatanode(
            cluster.getDataNodes().get(1).getDatanodeId());
      maintenanceNode.setInMaintenance();
      String dnXferAddrInMaintenance = maintenanceNode.getXferAddr();

      FSNamesystem fsn = cluster.getNameNode().namesystem;

      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanName = new ObjectName(
          "Hadoop:service=NameNode,name=NameNodeInfo");

      // get attribute "ClusterId"
      String clusterId = (String) mbs.getAttribute(mxbeanName, "ClusterId");
      assertEquals(fsn.getClusterId(), clusterId);
      // get attribute "BlockPoolId"
      String blockpoolId = (String) mbs.getAttribute(mxbeanName, 
          "BlockPoolId");
      assertEquals(fsn.getBlockPoolId(), blockpoolId);
      // get attribute "Version"
      String version = (String) mbs.getAttribute(mxbeanName, "Version");
      assertEquals(fsn.getVersion(), version);
      assertTrue(version.equals(VersionInfo.getVersion()
          + ", r" + VersionInfo.getRevision()));
      // get attribute "Used"
      Long used = (Long) mbs.getAttribute(mxbeanName, "Used");
      assertEquals(fsn.getUsed(), used.longValue());
      // get attribute "Total"
      Long total = (Long) mbs.getAttribute(mxbeanName, "Total");
      assertEquals(fsn.getTotal(), total.longValue());
      // get attribute "safemode"
      String safemode = (String) mbs.getAttribute(mxbeanName, "Safemode");
      assertEquals(fsn.getSafemode(), safemode);
      // get attribute nondfs
      Long nondfs = (Long) (mbs.getAttribute(mxbeanName, "NonDfsUsedSpace"));
      assertEquals(fsn.getNonDfsUsedSpace(), nondfs.longValue());
      // get attribute percentremaining
      Float percentremaining = (Float) (mbs.getAttribute(mxbeanName,
          "PercentRemaining"));
      assertEquals(fsn.getPercentRemaining(), percentremaining
          .floatValue(), DELTA);
      // get attribute Totalblocks
      Long totalblocks = (Long) (mbs.getAttribute(mxbeanName, "TotalBlocks"));
      assertEquals(fsn.getTotalBlocks(), totalblocks.longValue());
      // get attribute alivenodeinfo
      String alivenodeinfo = (String) (mbs.getAttribute(mxbeanName,
          "LiveNodes"));
      Map<String, Map<String, Object>> liveNodes =
          (Map<String, Map<String, Object>>) JSON.parse(alivenodeinfo);
      assertTrue(liveNodes.size() > 0);
      for (Map<String, Object> liveNode : liveNodes.values()) {
        assertTrue(liveNode.containsKey("nonDfsUsedSpace"));
        assertTrue(((Long)liveNode.get("nonDfsUsedSpace")) >= 0);
        assertTrue(liveNode.containsKey("capacity"));
        assertTrue(((Long)liveNode.get("capacity")) > 0);
        assertTrue(liveNode.containsKey("numBlocks"));
        assertTrue(((Long)liveNode.get("numBlocks")) == 0);
        // a. By default the upgrade domain isn't defined on any DN.
        // b. If the upgrade domain is set on a DN, JMX should have the same
        // value.
        String xferAddr = (String)liveNode.get("xferaddr");
        if (!xferAddr.equals(dnXferAddrWithUpgradeDomainSet)) {
          assertTrue(!liveNode.containsKey("upgradeDomain"));
        } else {
          assertTrue(liveNode.get("upgradeDomain").equals(upgradeDomain));
        }
        // "adminState" is set to maintenance only for the specific dn.
        boolean inMaintenance = liveNode.get("adminState").equals(
                DatanodeInfo.AdminStates.IN_MAINTENANCE.toString());
        assertFalse(xferAddr.equals(dnXferAddrInMaintenance) ^ inMaintenance);
      }
      assertEquals(fsn.getLiveNodes(), alivenodeinfo);
      // get attributes DeadNodes
      String deadNodeInfo = (String) (mbs.getAttribute(mxbeanName,
          "DeadNodes"));
      assertEquals(fsn.getDeadNodes(), deadNodeInfo);
      // get attribute NodeUsage
      String nodeUsage = (String) (mbs.getAttribute(mxbeanName,
          "NodeUsage"));
      assertEquals("Bad value for NodeUsage", fsn.getNodeUsage(), nodeUsage);
      // get attribute NameJournalStatus
      String nameJournalStatus = (String) (mbs.getAttribute(mxbeanName,
          "NameJournalStatus"));
      assertEquals("Bad value for NameJournalStatus",
          fsn.getNameJournalStatus(), nameJournalStatus);
      // get attribute JournalTransactionInfo
      String journalTxnInfo = (String) mbs.getAttribute(mxbeanName,
          "JournalTransactionInfo");
      assertEquals("Bad value for NameTxnIds", fsn.getJournalTransactionInfo(),
          journalTxnInfo);
      // get attribute "NNStarted"
      String nnStarted = (String) mbs.getAttribute(mxbeanName, "NNStarted");
      assertEquals("Bad value for NNStarted", fsn.getNNStarted(), nnStarted);
      // get attribute "CompileInfo"
      String compileInfo = (String) mbs.getAttribute(mxbeanName, "CompileInfo");
      assertEquals("Bad value for CompileInfo", fsn.getCompileInfo(),
          compileInfo);
      // get attribute CorruptFiles
      String corruptFiles = (String) (mbs.getAttribute(mxbeanName,
          "CorruptFiles"));
      assertEquals("Bad value for CorruptFiles", fsn.getCorruptFiles(),
          corruptFiles);
      // get attribute NameDirStatuses
      String nameDirStatuses = (String) (mbs.getAttribute(mxbeanName,
          "NameDirStatuses"));
      assertEquals(fsn.getNameDirStatuses(), nameDirStatuses);
      Map<String, Map<String, String>> statusMap =
        (Map<String, Map<String, String>>) JSON.parse(nameDirStatuses);
      Collection<URI> nameDirUris = cluster.getNameDirs(0);
      for (URI nameDirUri : nameDirUris) {
        File nameDir = new File(nameDirUri);
        System.out.println("Checking for the presence of " + nameDir +
            " in active name dirs.");
        assertTrue(statusMap.get("active").containsKey(
            nameDir.getAbsolutePath()));
      }
      assertEquals(2, statusMap.get("active").size());
      assertEquals(0, statusMap.get("failed").size());

      // This will cause the first dir to fail.
      File failedNameDir = new File(nameDirUris.iterator().next());
      assertEquals(0, FileUtil.chmod(
          new File(failedNameDir, "current").getAbsolutePath(), "000"));
      cluster.getNameNodeRpc().rollEditLog();
      
      nameDirStatuses = (String) (mbs.getAttribute(mxbeanName,
          "NameDirStatuses"));
      statusMap = (Map<String, Map<String, String>>) JSON.parse(nameDirStatuses);
      for (URI nameDirUri : nameDirUris) {
        File nameDir = new File(nameDirUri);
        String expectedStatus =
            nameDir.equals(failedNameDir) ? "failed" : "active";
        System.out.println("Checking for the presence of " + nameDir +
            " in " + expectedStatus + " name dirs.");
        assertTrue(statusMap.get(expectedStatus).containsKey(
            nameDir.getAbsolutePath()));
      }
      assertEquals(1, statusMap.get("active").size());
      assertEquals(1, statusMap.get("failed").size());
      assertEquals(0L, mbs.getAttribute(mxbeanName, "CacheUsed"));
      assertEquals(NativeIO.POSIX.getCacheManipulator().getMemlockLimit() *
          cluster.getDataNodes().size(),
              mbs.getAttribute(mxbeanName, "CacheCapacity"));
      assertNull("RollingUpgradeInfo should be null when there is no rolling"
          + " upgrade", mbs.getAttribute(mxbeanName, "RollingUpgradeStatus"));
    } finally {
      if (cluster != null) {
        for (URI dir : cluster.getNameDirs(0)) {
          FileUtil.chmod(
            new File(new File(dir), "current").getAbsolutePath(), "755");
        }
        cluster.shutdown();
      }
    }
  }

  @SuppressWarnings({ "unchecked" })
  @Test
  public void testLastContactTime() throws Exception {
    Configuration conf = new Configuration();
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY, 1);
    MiniDFSCluster cluster = null;
    HostsFileWriter hostsFileWriter = new HostsFileWriter();
    hostsFileWriter.initialize(conf, "temp/TestNameNodeMXBean");

    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
      cluster.waitActive();

      FSNamesystem fsn = cluster.getNameNode().namesystem;

      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanName = new ObjectName(
        "Hadoop:service=NameNode,name=NameNodeInfo");

      List<String> hosts = new ArrayList<>();
      for(DataNode dn : cluster.getDataNodes()) {
        hosts.add(dn.getDisplayName());
      }
      hostsFileWriter.initIncludeHosts(hosts.toArray(
          new String[hosts.size()]));
      fsn.getBlockManager().getDatanodeManager().refreshNodes(conf);

      cluster.stopDataNode(0);
      while (fsn.getBlockManager().getDatanodeManager().getNumLiveDataNodes()
        != 2 ) {
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
      }

      // get attribute DeadNodes
      String deadNodeInfo = (String) (mbs.getAttribute(mxbeanName,
        "DeadNodes"));
      assertEquals(fsn.getDeadNodes(), deadNodeInfo);
      Map<String, Map<String, Object>> deadNodes =
          (Map<String, Map<String, Object>>) JSON.parse(deadNodeInfo);
      assertTrue(deadNodes.size() > 0);
      for (Map<String, Object> deadNode : deadNodes.values()) {
        assertTrue(deadNode.containsKey("lastContact"));
        assertTrue(deadNode.containsKey("adminState"));
        assertTrue(deadNode.containsKey("xferaddr"));
      }
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
      hostsFileWriter.cleanup();
    }
  }

  @Test (timeout = 120000)
  public void testMaintenanceNodes() throws Exception {
    LOG.info("Starting testMaintenanceNodes");
    int expirationInMs = 30 * 1000;
    Configuration conf = new Configuration();
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY,
        expirationInMs);
    conf.setClass(DFSConfigKeys.DFS_NAMENODE_HOSTS_PROVIDER_CLASSNAME_KEY,
        CombinedHostFileManager.class, HostConfigManager.class);
    MiniDFSCluster cluster = null;
    HostsFileWriter hostsFileWriter = new HostsFileWriter();
    hostsFileWriter.initialize(conf, "temp/TestNameNodeMXBean");

    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
      cluster.waitActive();

      FSNamesystem fsn = cluster.getNameNode().namesystem;
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanName = new ObjectName(
          "Hadoop:service=NameNode,name=NameNodeInfo");

      List<String> hosts = new ArrayList<>();
      for(DataNode dn : cluster.getDataNodes()) {
        hosts.add(dn.getDisplayName());
      }
      hostsFileWriter.initIncludeHosts(hosts.toArray(
          new String[hosts.size()]));
      fsn.getBlockManager().getDatanodeManager().refreshNodes(conf);

      // 1. Verify nodes for DatanodeReportType.LIVE state
      String liveNodesInfo = (String) (mbs.getAttribute(mxbeanName,
          "LiveNodes"));
      LOG.info("Live Nodes: " + liveNodesInfo);
      Map<String, Map<String, Object>> liveNodes =
          (Map<String, Map<String, Object>>) JSON.parse(liveNodesInfo);
      assertEquals(fsn.getLiveNodes(), liveNodesInfo);

      for (Map<String, Object> liveNode : liveNodes.values()) {
        assertTrue(liveNode.containsKey("lastContact"));
        assertTrue(liveNode.containsKey("xferaddr"));
      }

      // Add the 1st DataNode to Maintenance list
      Map<String, Long> maintenanceNodes = new HashMap<>();
      maintenanceNodes.put(cluster.getDataNodes().get(0).getDisplayName(),
          Time.now() + expirationInMs);
      hostsFileWriter.initOutOfServiceHosts(null, maintenanceNodes);
      fsn.getBlockManager().getDatanodeManager().refreshNodes(conf);

      boolean recheck = true;
      while (recheck) {
        // 2. Verify nodes for DatanodeReportType.ENTERING_MAINTENANCE state
        String enteringMaintenanceNodesInfo =
            (String) (mbs.getAttribute(mxbeanName, "EnteringMaintenanceNodes"));
        Map<String, Map<String, Object>> enteringMaintenanceNodes =
            (Map<String, Map<String, Object>>) JSON.parse(
                enteringMaintenanceNodesInfo);
        if (enteringMaintenanceNodes.size() <= 0) {
          LOG.info("Waiting for a node to Enter Maintenance state!");
          Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
          continue;
        }
        LOG.info("Nodes entering Maintenance: " + enteringMaintenanceNodesInfo);
        recheck = false;
        assertEquals(fsn.getEnteringMaintenanceNodes(),
            enteringMaintenanceNodesInfo);
        assertEquals(fsn.getNumEnteringMaintenanceDataNodes(),
            enteringMaintenanceNodes.size());
        assertEquals(0, fsn.getNumInMaintenanceLiveDataNodes());
        assertEquals(0, fsn.getNumInMaintenanceDeadDataNodes());
      }

      // Wait for the DecommissionManager to complete check
      // and perform state transition
      while (fsn.getNumInMaintenanceLiveDataNodes() != 1) {
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
      }

      // 3. Verify nodes for AdminStates.IN_MAINTENANCE state
      String enteringMaintenanceNodesInfo =
          (String) (mbs.getAttribute(mxbeanName, "EnteringMaintenanceNodes"));
      Map<String, Map<String, Object>> enteringMaintenanceNodes =
          (Map<String, Map<String, Object>>) JSON.parse(
              enteringMaintenanceNodesInfo);
      assertEquals(0, enteringMaintenanceNodes.size());
      assertEquals(fsn.getEnteringMaintenanceNodes(),
          enteringMaintenanceNodesInfo);
      assertEquals(1, fsn.getNumInMaintenanceLiveDataNodes());
      assertEquals(0, fsn.getNumInMaintenanceDeadDataNodes());
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
      hostsFileWriter.cleanup();
    }
  }

  @Test(timeout=120000)
  @SuppressWarnings("unchecked")
  public void testTopUsers() throws Exception {
    final Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanNameFsns = new ObjectName(
          "Hadoop:service=NameNode,name=FSNamesystemState");
      FileSystem fs = cluster.getFileSystem();
      final Path path = new Path("/");
      final int NUM_OPS = 10;
      for (int i=0; i< NUM_OPS; i++) {
        fs.listStatus(path);
        fs.setTimes(path, 0, 1);
      }
      String topUsers =
          (String) (mbs.getAttribute(mxbeanNameFsns, "TopUserOpCounts"));
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> map = mapper.readValue(topUsers, Map.class);
      assertTrue("Could not find map key timestamp", 
          map.containsKey("timestamp"));
      assertTrue("Could not find map key windows", map.containsKey("windows"));
      List<Map<String, List<Map<String, Object>>>> windows =
          (List<Map<String, List<Map<String, Object>>>>) map.get("windows");
      assertEquals("Unexpected num windows", 3, windows.size());
      for (Map<String, List<Map<String, Object>>> window : windows) {
        final List<Map<String, Object>> ops = window.get("ops");
        assertEquals("Unexpected num ops", 3, ops.size());
        for (Map<String, Object> op: ops) {
          final long count = Long.parseLong(op.get("totalCount").toString());
          final String opType = op.get("opType").toString();
          final int expected;
          if (opType.equals(TopConf.ALL_CMDS)) {
            expected = 2*NUM_OPS;
          } else {
            expected = NUM_OPS;
          }
          assertEquals("Unexpected total count", expected, count);
        }
      }
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test(timeout=120000)
  public void testTopUsersDisabled() throws Exception {
    final Configuration conf = new Configuration();
    // Disable nntop
    conf.setBoolean(DFSConfigKeys.NNTOP_ENABLED_KEY, false);
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanNameFsns = new ObjectName(
          "Hadoop:service=NameNode,name=FSNamesystemState");
      FileSystem fs = cluster.getFileSystem();
      final Path path = new Path("/");
      final int NUM_OPS = 10;
      for (int i=0; i< NUM_OPS; i++) {
        fs.listStatus(path);
        fs.setTimes(path, 0, 1);
      }
      String topUsers =
          (String) (mbs.getAttribute(mxbeanNameFsns, "TopUserOpCounts"));
      assertNull("Did not expect to find TopUserOpCounts bean!", topUsers);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test(timeout=120000)
  public void testTopUsersNoPeriods() throws Exception {
    final Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.NNTOP_ENABLED_KEY, true);
    conf.set(DFSConfigKeys.NNTOP_WINDOWS_MINUTES_KEY, "");
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanNameFsns = new ObjectName(
          "Hadoop:service=NameNode,name=FSNamesystemState");
      FileSystem fs = cluster.getFileSystem();
      final Path path = new Path("/");
      final int NUM_OPS = 10;
      for (int i=0; i< NUM_OPS; i++) {
        fs.listStatus(path);
        fs.setTimes(path, 0, 1);
      }
      String topUsers =
          (String) (mbs.getAttribute(mxbeanNameFsns, "TopUserOpCounts"));
      assertNotNull("Expected TopUserOpCounts bean!", topUsers);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test(timeout = 120000)
  public void testQueueLength() throws Exception {
    final Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName mxbeanNameFs =
          new ObjectName("Hadoop:service=NameNode,name=FSNamesystem");
      int queueLength = (int) mbs.getAttribute(mxbeanNameFs, "LockQueueLength");
      assertEquals(0, queueLength);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
}
