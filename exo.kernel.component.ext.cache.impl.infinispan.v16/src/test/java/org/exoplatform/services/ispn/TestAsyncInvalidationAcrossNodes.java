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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.Cache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.marshall.MarshallingException;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.context.Flag;
import org.infinispan.distribution.ch.impl.HashFunctionPartitioner;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.exoplatform.services.cache.CacheListener;
import org.exoplatform.services.cache.CacheListenerContext;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ClusterCachedObjectSelector;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ObjectCacheInfo;
import org.exoplatform.services.cache.impl.infinispan.generic.GenericExoCacheCreator.GenericExoCache;
import org.exoplatform.services.cache.invalidation.AsyncInvalidationExoCache;
import org.exoplatform.services.cache.invalidation.InvalidationExoCache;

/**
 * An asynchronously invalidated cache on two nodes, each over its own Infinispan cache replicated asynchronously, as
 * the cluster template builds it: what a node removes or clears goes from the entries the other node loaded itself.
 * The {@code testSync} cases check the same on a synchronously invalidated cache, over the cache the synchronous
 * cluster template declares.
 */
public class TestAsyncInvalidationAcrossNodes
{
   private static final String PROPERTY_PREFIX = "exo.service.cluster.jgroups.";

   private static final String KEY = "key";

   private static final String OTHER = "other";

   private static final String INVALIDATED = "invalidated";

   /**
    * A cache whose writer has a listener that clears the cache of its lists on every put
    */
   private static final String LISTENED = "listened";

   /**
    * The cache of the lists of the entries of {@link #LISTENED}
    */
   private static final String LISTS = "lists";

   /**
    * A cache whose writer has a listener that counts the puts and removes it is notified of
    */
   private static final String COUNTED = "counted";

   /**
    * A cache whose nodes place a key in different segments
    */
   private static final String SKEWED = "skewed";

   /**
    * A cache whose nodes place a clear or a selection in different segments
    */
   private static final String SKEWED_MARKERS = "skewed-markers";

   /**
    * A synchronously invalidated cache, over the replicated cache the synchronous cluster template declares
    */
   private static final String SYNC_INVALIDATED = "sync-invalidated";

   private static final String SYNC_TEMPLATE = "conf/cache/infinispan/cluster/cache-config.xml";

   private static DefaultCacheManager node1;

   private static DefaultCacheManager node2;

   private static AsyncInvalidationExoCache<String, String> writer;

   private static AsyncInvalidationExoCache<String, String> reader;

   private static InvalidationExoCache<String, String> syncWriter;

   private static InvalidationExoCache<String, String> syncReader;

   @BeforeClass
   public static void startNodes() throws Exception
   {
      int[] ports = TestSharedJGroupsTransport.freePorts(2);
      node1 = startNode(TestSharedJGroupsTransport.stack(ports[0], ports), "node1", false);
      node2 = startNode(TestSharedJGroupsTransport.stack(ports[1], ports), "node2", true);
      writer = invalidated(node1, INVALIDATED);
      reader = invalidated(node2, INVALIDATED);
      syncWriter = syncInvalidated(node1);
      syncReader = syncInvalidated(node2);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(INVALIDATED), 2);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(SYNC_INVALIDATED), 2);
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
      // A remove leaves its hash in the replicated cache of every node: each test starts without it
      node1.getCache(INVALIDATED).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).clear();
      node2.getCache(INVALIDATED).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).clear();
      syncWriter.removeLocal(KEY);
      syncReader.removeLocal(KEY);
      syncReader.removeLocal(OTHER);
      // A clear or a selection leaves its marker in the replicated cache of every node
      node1.getCache(SYNC_INVALIDATED).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).clear();
      node2.getCache(SYNC_INVALIDATED).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).clear();
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
      reader.putLocal(KEY, "probe");
      writer.remove(KEY);
      // The other node applies the first remove before it loads the entry again
      assertEvicted(KEY);
      Cache<Object, Object> replicated = node2.getCache(INVALIDATED);
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
   public void testASecondRemoveEvictsTheEntryTheOtherNodeLoadedAgain() throws Exception
   {
      reader.putLocal(KEY, "probe");
      writer.remove(KEY);
      // The other node applies the first remove before it loads the entry again
      assertEvicted(KEY);
      Cache<Object, Object> replicated = node2.getCache(INVALIDATED);
      for (int i = 0; i < 100 && !replicated.containsKey(KEY); i++)
      {
         Thread.sleep(100);
      }
      // Every remove puts the same hash: the other node already holds the one this remove puts
      reader.putLocal(KEY, "former");
      assertEquals("former", reader.get(KEY));

      writer.remove(KEY);

      assertEvicted(KEY);
   }

   @Test
   public void testARemoveOfAKeyOffTheAllowListFailsAfterTheLocalRemoval()
   {
      @SuppressWarnings({"unchecked", "rawtypes"})
      AsyncInvalidationExoCache<Serializable, String> offList = (AsyncInvalidationExoCache) writer;
      Serializable key = new Point(1, 2);
      offList.putLocal(key, "former");

      // The caller learns that the other nodes keep the entry, and the message names the property to set
      MarshallingException refusal = assertThrows(MarshallingException.class, () -> offList.remove(key));

      assertTrue(refusal.getMessage(), refusal.getMessage().contains(ClusterAllowList.ALLOW_LIST_PROPERTY));
      assertNull(offList.get(key));
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

   @Test
   public void testSyncRemoveEvictsTheEntryOnlyTheOtherNodeLoaded()
   {
      syncReader.putLocal(KEY, "former");
      assertEquals("former", syncReader.get(KEY));

      // The replicated cache is transactional: the remove of a key the writing node does not hold reaches the others
      syncWriter.remove(KEY);

      // and is applied there before it returns
      assertNull(syncReader.get(KEY));
   }

   @Test
   public void testSyncClearEmptiesTheOtherNodeOfTheEntriesItLoaded()
   {
      syncReader.putLocal(KEY, "former");
      syncReader.putLocal(OTHER, "former");
      assertEquals(2, syncReader.getCacheSize());

      syncWriter.clearCache();

      // The clear of the replicated cache stays on the writing node: the marker it puts empties the other node
      assertNull(syncReader.get(KEY));
      assertEquals(0, syncReader.getCacheSize());
   }

   @Test
   public void testSyncSelectionEvictsTheSelectedEntriesOnlyTheOtherNodeLoaded() throws Exception
   {
      syncReader.putLocal(KEY, "former");
      syncReader.putLocal(OTHER, "kept");

      // The writing node holds none of them: the selector itself goes to the other node
      syncWriter.select(new KeySelector(KEY));

      assertNull(syncReader.get(KEY));
      assertEquals("kept", syncReader.get(OTHER));
   }

   private static void assertEvicted(String key) throws InterruptedException
   {
      assertEvicted(reader, key);
   }

   @Test
   public void testAClearAndASelectionReachANodeThatPlacesTheirKeysInOtherSegments() throws Exception
   {
      AsyncInvalidationExoCache<String, String> skewedWriter = invalidated(node1, SKEWED_MARKERS);
      AsyncInvalidationExoCache<String, String> skewedReader = invalidated(node2, SKEWED_MARKERS);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(SKEWED_MARKERS), 2);
      // The marker of a clear is an enum, whose hash is its identity hash: each JVM places it in a segment of its own
      skewedReader.putLocal(KEY, "former");
      skewedWriter.clearCache();
      assertEvicted(skewedReader, KEY);
      // A second clear finds its marker where the first one left it
      skewedReader.putLocal(KEY, "former");
      skewedWriter.clearCache();
      assertEvicted(skewedReader, KEY);

      skewedReader.putLocal(KEY, "former");
      skewedReader.putLocal(OTHER, "kept");
      skewedWriter.select(new KeySelector(KEY));
      assertEvicted(skewedReader, KEY);
      assertEquals("kept", skewedReader.get(OTHER));
      skewedReader.putLocal(KEY, "former");
      skewedWriter.select(new KeySelector(KEY));
      assertEvicted(skewedReader, KEY);
   }

   @Test
   public void testLoadsSendNoClearAndEachWriteSendsOne() throws Exception
   {
      ClearsSent clears = new ClearsSent(node1, LISTS);
      @SuppressWarnings({"unchecked", "rawtypes"})
      AsyncInvalidationExoCache<String, String> lists = new AsyncInvalidationExoCache<>((ExoCache) clears);
      AsyncInvalidationExoCache<String, String> otherLists = invalidated(node2, LISTS);
      AsyncInvalidationExoCache<String, String> entries = invalidated(node1, LISTENED);
      invalidated(node2, LISTENED);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(LISTS), 2);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(LISTENED), 2);
      // A listener that clears the lists on every put of an entry and ignores a load, as social's activity cache does
      entries.addCacheListener(new ClearOnPut(lists));
      otherLists.putLocal(KEY, "kept");

      for (int i = 0; i < 50; i++)
      {
         entries.putLocal(KEY + i, "loaded");
      }
      assertEquals("kept", otherLists.get(KEY));
      // Each write clears the lists, whichever node owns the segment of its key and applies it first
      for (int i = 0; i < 20; i++)
      {
         entries.put(OTHER + i, "written");
      }

      assertEvicted(otherLists, KEY);
      // The replicated cache of an asynchronous invalidation writes asynchronously, and a write comes back to its node
      // when the other node owns the segment of its key
      Cache<Object, Object> replicated = node1.getCache(LISTENED);
      for (int i = 0; i < 100 && replicated.size() < 20; i++)
      {
         Thread.sleep(100);
      }
      Thread.sleep(500);
      assertEquals("The writes send one clear each, the loads none", 20, clears.sent.get());
   }

   @Test
   public void testRemoveNotifiesTheListenersOfItsNodeOfARemoveOnly() throws Exception
   {
      AsyncInvalidationExoCache<String, String> countedWriter = invalidated(node1, COUNTED);
      AsyncInvalidationExoCache<String, String> countedReader = invalidated(node2, COUNTED);
      TestSharedJGroupsTransport.waitForMembers(node1.getCache(COUNTED), 2);
      countedWriter.putLocal(KEY, "loaded");
      countedReader.putLocal(KEY, "former");
      AtomicInteger puts = new AtomicInteger();
      AtomicInteger removes = new AtomicInteger();
      countedWriter.addCacheListener(new Counting(puts, removes));

      countedWriter.remove(KEY);

      assertEvicted(countedReader, KEY);
      // The put of the invalidation hash notifies the removing node too, asynchronously
      Cache<Object, Object> replicated = node1.getCache(COUNTED);
      for (int i = 0; i < 100 && !replicated.containsKey(KEY); i++)
      {
         Thread.sleep(100);
      }
      Thread.sleep(500);
      assertEquals(0, puts.get());
      assertEquals(1, removes.get());
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
      Cache cache = node.getCache(name);
      return new AsyncInvalidationExoCache<>((ExoCache) new GenericExoCache(config(name), cache));
   }

   @SuppressWarnings({"unchecked", "rawtypes"})
   private static InvalidationExoCache<String, String> syncInvalidated(DefaultCacheManager node)
   {
      Cache cache = node.getCache(SYNC_INVALIDATED);
      return new InvalidationExoCache<>((ExoCache) new GenericExoCache(config(SYNC_INVALIDATED), cache));
   }

   private static ExoCacheConfig config(String name)
   {
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName(name);
      config.setMaxSize(100);
      config.setLiveTime(-1);
      return config;
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
      replicatedAsync(holder.newConfigurationBuilder(LISTENED));
      replicatedAsync(holder.newConfigurationBuilder(LISTS));
      replicatedAsync(holder.newConfigurationBuilder(COUNTED));
      holder.newConfigurationBuilder(SYNC_INVALIDATED).read(syncTemplate());
      // Unbounded, as a cache whose size is not positive: each segment holds its entries in a map of its own
      for (String name : new String[] {SKEWED, SKEWED_MARKERS})
      {
         ConfigurationBuilder skewedCache = holder.newConfigurationBuilder(name);
         skewedCache.clustering().cacheMode(CacheMode.REPL_ASYNC);
         if (skewed)
         {
            skewedCache.clustering().hash().keyPartitioner(new ShiftedPartitioner());
         }
      }
      return new DefaultCacheManager(holder, true);
   }

   private static Configuration syncTemplate() throws IOException
   {
      try (InputStream template =
         TestAsyncInvalidationAcrossNodes.class.getClassLoader().getResourceAsStream(SYNC_TEMPLATE))
      {
         return new ParserRegistry(TestAsyncInvalidationAcrossNodes.class.getClassLoader())
            .parse(template, MediaType.APPLICATION_XML)
            .getNamedConfigurationBuilders()
            .get("default")
            .build();
      }
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

   private static class ClearOnPut implements CacheListener<String, String>
   {
      private final ExoCache<String, String> cache;

      private ClearOnPut(ExoCache<String, String> cache)
      {
         this.cache = cache;
      }

      @Override
      public void onPut(CacheListenerContext context, String key, String obj)
      {
         cache.clearCache();
      }

      @Override
      public void onPutLocal(CacheListenerContext context, String key, String obj)
      {
         // A load changes nothing to clear
      }

      @Override
      public void onExpire(CacheListenerContext context, String key, String obj)
      {
         // Not observed
      }

      @Override
      public void onRemove(CacheListenerContext context, String key, String obj)
      {
         // Not observed
      }

      @Override
      public void onGet(CacheListenerContext context, String key, String obj)
      {
         // Not observed
      }

      @Override
      public void onClearCache(CacheListenerContext context)
      {
         // Not observed
      }
   }

   /**
    * The replicated cache of a node, which counts the clears its node sends: the puts of their marker, a kernel enum the
    * invalidation package keeps to itself
    */
   private static class ClearsSent extends GenericExoCache
   {
      private final AtomicInteger sent = new AtomicInteger();

      @SuppressWarnings({"unchecked", "rawtypes"})
      private ClearsSent(DefaultCacheManager node, String name)
      {
         super(config(name), (Cache) node.getCache(name));
      }

      @Override
      public void put(Serializable key, Object value)
      {
         if ("org.exoplatform.services.cache.invalidation.ClearMarker".equals(key.getClass().getName()))
         {
            sent.incrementAndGet();
         }
         super.put(key, value);
      }
   }

   private static class Counting extends ClearOnPut
   {
      private final AtomicInteger puts;

      private final AtomicInteger removes;

      private Counting(AtomicInteger puts, AtomicInteger removes)
      {
         super(null);
         this.puts = puts;
         this.removes = removes;
      }

      @Override
      public void onPut(CacheListenerContext context, String key, String obj)
      {
         puts.incrementAndGet();
      }

      @Override
      public void onRemove(CacheListenerContext context, String key, String obj)
      {
         removes.incrementAndGet();
      }
   }
}
