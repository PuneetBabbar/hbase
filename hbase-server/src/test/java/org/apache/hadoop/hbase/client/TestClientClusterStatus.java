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
package org.apache.hadoop.hbase.client;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.ClusterStatus.Option;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.JVMClusterUtil.MasterThread;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test the ClusterStatus.
 */
@Category(SmallTests.class)
public class TestClientClusterStatus {
  private static HBaseTestingUtility UTIL;
  private static Admin ADMIN;
  private final static int SLAVES = 5;
  private final static int MASTERS = 3;
  private static MiniHBaseCluster CLUSTER;
  private static HRegionServer DEAD;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    Configuration conf = HBaseConfiguration.create();
    UTIL = new HBaseTestingUtility(conf);
    UTIL.startMiniCluster(MASTERS, SLAVES);
    CLUSTER = UTIL.getHBaseCluster();
    CLUSTER.waitForActiveAndReadyMaster();
    ADMIN = UTIL.getAdmin();
    // Kill one region server
    List<RegionServerThread> rsts = CLUSTER.getLiveRegionServerThreads();
    RegionServerThread rst = rsts.get(rsts.size() - 1);
    DEAD = rst.getRegionServer();
    DEAD.stop("Test dead servers status");
    while (rst.isAlive()) {
      Thread.sleep(500);
    }
  }

  @Test
  public void testDefaults() throws Exception {
    ClusterStatus origin = ADMIN.getClusterStatus();
    ClusterStatus defaults = ADMIN.getClusterStatus(EnumSet.allOf(Option.class));
    Assert.assertEquals(origin.getHBaseVersion(), defaults.getHBaseVersion());
    Assert.assertEquals(origin.getClusterId(), defaults.getClusterId());
    Assert.assertTrue(origin.getAverageLoad() == defaults.getAverageLoad());
    Assert.assertTrue(origin.getBackupMastersSize() == defaults.getBackupMastersSize());
    Assert.assertTrue(origin.getDeadServersSize() == defaults.getDeadServersSize());
    Assert.assertTrue(origin.getRegionsCount() == defaults.getRegionsCount());
    Assert.assertTrue(origin.getServersSize() == defaults.getServersSize());
    Assert.assertTrue(origin.getMasterInfoPort() == defaults.getMasterInfoPort());
    Assert.assertTrue(origin.equals(defaults));
  }

  @Test
  public void testNone() throws Exception {
    ClusterStatus status0 = ADMIN.getClusterStatus(EnumSet.allOf(Option.class));
    ClusterStatus status1 = ADMIN.getClusterStatus(EnumSet.noneOf(Option.class));
    Assert.assertEquals(status0, status1);
  }

  @Test
  public void testAsyncClient() throws Exception {
    AsyncRegistry registry = AsyncRegistryFactory.getRegistry(UTIL.getConfiguration());
    AsyncConnectionImpl asyncConnect = new AsyncConnectionImpl(UTIL.getConfiguration(), registry,
      registry.getClusterId().get(), User.getCurrent());
    AsyncAdmin asyncAdmin = asyncConnect.getAdmin();
    CompletableFuture<ClusterStatus> originFuture =
        asyncAdmin.getClusterStatus();
    CompletableFuture<ClusterStatus> defaultsFuture =
        asyncAdmin.getClusterStatus(EnumSet.allOf(Option.class));
    ClusterStatus origin = originFuture.get();
    ClusterStatus defaults = defaultsFuture.get();
    Assert.assertEquals(origin.getHBaseVersion(), defaults.getHBaseVersion());
    Assert.assertEquals(origin.getClusterId(), defaults.getClusterId());
    Assert.assertTrue(origin.getAverageLoad() == defaults.getAverageLoad());
    Assert.assertTrue(origin.getBackupMastersSize() == defaults.getBackupMastersSize());
    Assert.assertTrue(origin.getDeadServersSize() == defaults.getDeadServersSize());
    Assert.assertTrue(origin.getRegionsCount() == defaults.getRegionsCount());
    Assert.assertTrue(origin.getServersSize() == defaults.getServersSize());
    if (asyncConnect != null) {
      asyncConnect.close();
    }
  }

  @Test
  public void testLiveAndDeadServersStatus() throws Exception {
    List<RegionServerThread> regionserverThreads = CLUSTER.getLiveRegionServerThreads();
    int numRs = 0;
    int len = regionserverThreads.size();
    for (int i = 0; i < len; i++) {
      if (regionserverThreads.get(i).isAlive()) {
        numRs++;
      }
    }
    // Retrieve live servers and dead servers info.
    EnumSet<Option> options = EnumSet.of(Option.LIVE_SERVERS, Option.DEAD_SERVERS);
    ClusterStatus status = ADMIN.getClusterStatus(options);
    Assert.assertNotNull(status);
    Assert.assertNotNull(status.getServers());
    // exclude a dead region server
    Assert.assertEquals(SLAVES -1, numRs);
    // live servers = nums of regionservers
    // By default, HMaster don't carry any regions so it won't report its load.
    // Hence, it won't be in the server list.
    Assert.assertEquals(status.getServers().size(), numRs);
    Assert.assertTrue(status.getRegionsCount() > 0);
    Assert.assertNotNull(status.getDeadServerNames());
    Assert.assertEquals(1, status.getDeadServersSize());
    ServerName deadServerName = status.getDeadServerNames().iterator().next();
    Assert.assertEquals(DEAD.getServerName(), deadServerName);
  }

  @Test
  public void testMasterAndBackupMastersStatus() throws Exception {
    // get all the master threads
    List<MasterThread> masterThreads = CLUSTER.getMasterThreads();
    int numActive = 0;
    int activeIndex = 0;
    ServerName activeName = null;
    HMaster active = null;
    for (int i = 0; i < masterThreads.size(); i++) {
      if (masterThreads.get(i).getMaster().isActiveMaster()) {
        numActive++;
        activeIndex = i;
        active = masterThreads.get(activeIndex).getMaster();
        activeName = active.getServerName();
      }
    }
    Assert.assertNotNull(active);
    Assert.assertEquals(1, numActive);
    Assert.assertEquals(MASTERS, masterThreads.size());
    // Retrieve master and backup masters infos only.
    EnumSet<Option> options = EnumSet.of(Option.MASTER, Option.BACKUP_MASTERS);
    ClusterStatus status = ADMIN.getClusterStatus(options);
    Assert.assertTrue(status.getMaster().equals(activeName));
    Assert.assertEquals(MASTERS - 1, status.getBackupMastersSize());
  }

  @Test
  public void testOtherStatusInfos() throws Exception {
    EnumSet<Option> options =
        EnumSet.of(Option.MASTER_COPROCESSORS, Option.HBASE_VERSION,
                   Option.CLUSTER_ID, Option.BALANCER_ON);
    ClusterStatus status = ADMIN.getClusterStatus(options);
    Assert.assertTrue(status.getMasterCoprocessors().length == 0);
    Assert.assertNotNull(status.getHBaseVersion());
    Assert.assertNotNull(status.getClusterId());
    Assert.assertTrue(status.getAverageLoad() == 0.0);
    Assert.assertNotNull(status.getBalancerOn() && !status.getBalancerOn());
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    if (ADMIN != null) ADMIN.close();
    UTIL.shutdownMiniCluster();
  }
}