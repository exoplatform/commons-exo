/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.services.ispn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.awt.Point;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.topology.CacheTopology;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Two nodes whose allow-lists differ, over a real channel: a value the writing node accepts and the other node refuses
 * never enters the cache of the other node, and what the writing node sends after it still does.
 */
public class TestClusterAllowListAcrossNodes
{
   private static final String PROPERTY_PREFIX = "exo.service.cluster.jgroups.";

   private static final String CACHE = "replicated";

   /**
    * The node whose list accepts {@link Point}, which the platform's list refuses
    */
   private static DefaultCacheManager writer;

   private static DefaultCacheManager reader;

   @BeforeClass
   public static void startNodes() throws Exception
   {
      int[] ports = TestSharedJGroupsTransport.freePorts(2);
      writer = startNode(TestSharedJGroupsTransport.stack(ports[0], ports), "writer",
                         new ClusterAllowList(Point.class.getName()));
      reader = startNode(TestSharedJGroupsTransport.stack(ports[1], ports), "reader", new ClusterAllowList(null));
      TestSharedJGroupsTransport.waitForMembers(writer.getCache(CACHE), 2);
      // The primary owners of the segments are final once the join is rebalanced, on both nodes
      waitForRebalance(writer.getCache(CACHE));
      waitForRebalance(reader.getCache(CACHE));
   }

   private static void waitForRebalance(Cache<?, ?> cache) throws InterruptedException
   {
      for (int i = 0; i < 100 && cache.getAdvancedCache().getDistributionManager().getCacheTopology().getPhase()
                                      != CacheTopology.Phase.NO_REBALANCE; i++)
      {
         Thread.sleep(100);
      }
      assertEquals(CacheTopology.Phase.NO_REBALANCE,
                   cache.getAdvancedCache().getDistributionManager().getCacheTopology().getPhase());
   }

   @AfterClass
   public static void stopNodes()
   {
      if (reader != null)
      {
         reader.stop();
      }
      if (writer != null)
      {
         writer.stop();
      }
      for (String name : System.getProperties().stringPropertyNames())
      {
         if (name.startsWith(PROPERTY_PREFIX))
         {
            System.clearProperty(name);
         }
      }
   }

   @Test
   public void testAValueTheOtherNodeRefusesNeverEntersItsCache() throws Exception
   {
      Cache<String, Object> written = writer.getCache(CACHE);
      Cache<String, Object> read = reader.getCache(CACHE);

      // A write goes first to the primary owner of the key, which replicates it asynchronously: a node that is not the
      // primary owner waits for the answer of the primary owner, which fails when the primary owner refuses the value.
      // The keys are the ones this node owns, whichever segments the nodes took at their start
      String refused = keyOwnedBy(written, "refused");
      String accepted = keyOwnedBy(written, "accepted");

      written.put(refused, new Point(1, 2));
      // Sent after the refused value by the same node: the other node reads them in that order
      written.put(accepted, "after");
      for (int i = 0; i < 100 && !read.containsKey(accepted); i++)
      {
         Thread.sleep(100);
      }
      Thread.sleep(500);

      assertEquals("after", read.get(accepted));
      assertFalse(read.containsKey(refused));
      assertEquals(new Point(1, 2), written.get(refused));
   }

   /**
    * @return a key made of the prefix whose primary owner is the node of the cache
    */
   private static String keyOwnedBy(Cache<String, Object> cache, String prefix)
   {
      for (int i = 0; i < 1000; i++)
      {
         String key = prefix + i;
         if (cache.getAdvancedCache().getDistributionManager().getCacheTopology().getDistribution(key).isPrimary())
         {
            return key;
         }
      }
      throw new IllegalStateException("No key of " + prefix + " is owned by the node");
   }

   private static DefaultCacheManager startNode(ProtocolStackConfigurator stack, String nodeName,
                                                ClusterAllowList allowList)
   {
      ConfigurationBuilderHolder holder = new ConfigurationBuilderHolder();
      GlobalConfigurationBuilder global = holder.getGlobalConfigurationBuilder();
      global.cacheManagerName(nodeName).jmx().enabled(false);
      global.transport()
            .defaultTransport()
            .clusterName("allow-list-test")
            .nodeName(nodeName)
            .addProperty(JGroupsTransport.CHANNEL_CONFIGURATOR, new SharedJGroupsChannelConfigurator(stack, nodeName));
      global.serialization().marshaller(new ClusterJavaSerializationMarshaller(allowList.getInfinispanAllowList()));
      holder.newConfigurationBuilder(CACHE).clustering().cacheMode(CacheMode.REPL_ASYNC);
      return new DefaultCacheManager(holder, true);
   }
}
