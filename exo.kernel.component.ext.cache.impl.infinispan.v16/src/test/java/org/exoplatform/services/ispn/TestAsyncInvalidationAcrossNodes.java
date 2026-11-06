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
import static org.junit.Assert.assertNull;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.context.Flag;
import org.infinispan.distribution.ch.impl.HashFunctionPartitioner;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ClusterCachedObjectSelector;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ObjectCacheInfo;
import org.exoplatform.services.cache.impl.infinispan.generic.GenericExoCacheCreator.GenericExoCache;
import org.exoplatform.services.cache.invalidation.AsyncInvalidationExoCache;

/**
 * An asynchronously invalidated cache on two nodes, each over its own Infinispan cache replicated asynchronously, as
 * the cluster template builds it: what a node removes or clears goes from the entries the other node loaded itself.
 */
public class TestAsyncInvalidationAcrossNodes
{
   private static final String PROPERTY_PREFIX = "exo.service.cluster.jgroups.";

   private static final String KEY = "key";

   private static final String OTHER = "other";

   private static final String INVALIDATED = "invalidated";

   /**
    * A cache whose nodes place a key in different segments
    */
   private static final String SKEWED = "skewed";

   private static DefaultCacheManager node1;

   private static DefaultCacheManager node2;

   private static AsyncInvalidationExoCache<String, String> writer;

   private static AsyncInvalidationExoCache<String, String> reader;

   @BeforeClass
   public static void startNodes() throws Exception
   {
      int[] ports = TestSharedJGroupsTransport.freePorts(2);
      node1 = startNode(TestSharedJGroupsTransport.stack(ports[0], ports), "node1", false);
      node2 = startNode(TestSharedJGroupsTransport.stack(ports[1], ports), "node2", true);
      writer = invalidated(node1, INVALIDATED);
      reader = invalidated(node2, INVALIDATED);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(INVALIDATED), 2);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(SKEWED), 2);
   }

   @AfterClass
   public static void stopNodes()
   {
      if (node2 != null)
      {
         node2.stop();
      }
      if (node1 != null)
      {
         node1.stop();
      }
      for (String name : System.getProperties().stringPropertyNames())
      {
         if (name.startsWith(PROPERTY_PREFIX))
         {
            System.clearProperty(name);
         }
      }
   }

   @After
   public void clearNodes()
   {
      writer.removeLocal(KEY);
      reader.removeLocal(KEY);
      reader.removeLocal(OTHER);
   }

   @Test
   public void testClearOnANodeEvictsTheEntriesTheOtherLoaded() throws Exception
   {
      // A loader puts the entry in the local cache of the node that reads it only
      reader.putLocal(KEY, "former");
      assertEquals("former", reader.get(KEY));

      writer.clearCache();

      assertEvicted(KEY);
   }

   @Test
   public void testRemoveOnANodeEvictsTheEntryTheOtherLoaded() throws Exception
   {
      reader.putLocal(KEY, "former");

      writer.remove(KEY);

      assertEvicted(KEY);
   }

   @Test
   public void testRemoveAgainEvictsTheEntryOfANodeThatEvictedTheKey() throws Exception
   {
      writer.remove(KEY);
      Cache<Object, Object> replicated = node2.getCache("invalidated");
      for (int i = 0; i < 100 && !replicated.containsKey(KEY); i++)
      {
         Thread.sleep(100);
      }
      reader.putLocal(KEY, "former");
      // A bounded replicated cache evicts its keys node by node, whatever the local cache holds
      replicated.evict(KEY);
      assertEquals("former", reader.get(KEY));

      writer.remove(KEY);

      assertEvicted(KEY);
   }

   @Test
   public void testSelectionOnANodeEvictsTheSelectedEntriesTheOtherLoaded() throws Exception
   {
      reader.putLocal(KEY, "former");
      reader.putLocal(OTHER, "kept");

      // The selector reaches the other node serialized, through the allow-list of the cluster
      writer.select(new KeySelector(KEY));

      assertEvicted(KEY);
      assertEquals("kept", reader.get(OTHER));
   }

   @Test
   public void testAKeyTheNodesPlaceInDifferentSegmentsIsInvalidatedBothWays() throws Exception
   {
      AsyncInvalidationExoCache<String, String> skewedWriter = invalidated(node1, SKEWED);
      AsyncInvalidationExoCache<String, String> skewedReader = invalidated(node2, SKEWED);
      Cache<Object, Object> replicated = node2.getCache(SKEWED);
      skewedWriter.remove(KEY);
      for (int i = 0; i < 100 && replicated.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).isEmpty(); i++)
      {
         Thread.sleep(100);
      }
      // The other node holds the key in the segment of the node that wrote it, where its own lookup never goes, as
      // when a key's hash differs from JVM to JVM: an enum's hash is its identity hash
      assertEquals(1, replicated.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).size());
      assertFalse(replicated.containsKey(KEY));

      skewedReader.putLocal(KEY, "former");
      skewedWriter.remove(KEY);
      assertEvicted(skewedReader, KEY);

      skewedWriter.putLocal(KEY, "former");
      skewedReader.remove(KEY);
      assertEvicted(skewedWriter, KEY);
   }

   private static void assertEvicted(String key) throws InterruptedException
   {
      assertEvicted(reader, key);
   }

   private static void assertEvicted(AsyncInvalidationExoCache<String, String> cache, String key) throws InterruptedException
   {
      // The invalidation reaches the other node asynchronously
      for (int i = 0; i < 100 && cache.get(key) != null; i++)
      {
         Thread.sleep(100);
      }
      assertNull(cache.get(key));
   }

   @SuppressWarnings({"unchecked", "rawtypes"})
   private static AsyncInvalidationExoCache<String, String> invalidated(DefaultCacheManager node, String name)
   {
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName(name);
      config.setMaxSize(100);
      config.setLiveTime(-1);
      Cache cache = node.getCache(name);
      return new AsyncInvalidationExoCache<>((ExoCache) new GenericExoCache(config, cache));
   }

   private static DefaultCacheManager startNode(ProtocolStackConfigurator stack, String nodeName, boolean skewed)
      throws Exception
   {
      ConfigurationBuilderHolder holder = new ConfigurationBuilderHolder();
      GlobalConfigurationBuilder global = holder.getGlobalConfigurationBuilder();
      global.cacheManagerName(nodeName).jmx().enabled(false);
      global.transport()
            .defaultTransport()
            .clusterName("invalidation-test")
            .nodeName(nodeName)
            .addProperty(JGroupsTransport.CHANNEL_CONFIGURATOR, new SharedJGroupsChannelConfigurator(stack, nodeName));
      Utils.configureSerialization(global, "test");
      replicatedAsync(holder.newConfigurationBuilder(INVALIDATED));
      // Unbounded, as a cache whose size is not positive: each segment holds its entries in a map of its own
      ConfigurationBuilder skewedCache = holder.newConfigurationBuilder(SKEWED);
      skewedCache.clustering().cacheMode(CacheMode.REPL_ASYNC);
      if (skewed)
      {
         skewedCache.clustering().hash().keyPartitioner(new ShiftedPartitioner());
      }
      return new DefaultCacheManager(holder, true);
   }

   private static void replicatedAsync(ConfigurationBuilder cache)
   {
      // Bounded as GenericExoCacheCreator bounds it: the local cache takes its size from the replicated one
      cache.clustering().cacheMode(CacheMode.REPL_ASYNC).memory().maxCount(100).whenFull(EvictionStrategy.REMOVE);
   }

   /**
    * Places every key one segment further than the default partitioner, as a JVM that hashes the key differently does.
    */
   public static class ShiftedPartitioner extends HashFunctionPartitioner
   {
      @Override
      public int getSegment(Object key)
      {
         return (super.getSegment(key) + 1) % numSegments;
      }
   }

   private static class KeySelector extends ClusterCachedObjectSelector<String, String>
   {
      private static final long serialVersionUID = 1L;

      private final String key;

      private KeySelector(String key)
      {
         this.key = key;
      }

      @Override
      protected Object[] criteria()
      {
         return new Object[] {key};
      }

      @Override
      public boolean select(String cachedKey, ObjectCacheInfo<? extends String> ocinfo)
      {
         return key.equals(cachedKey);
      }
   }
}
