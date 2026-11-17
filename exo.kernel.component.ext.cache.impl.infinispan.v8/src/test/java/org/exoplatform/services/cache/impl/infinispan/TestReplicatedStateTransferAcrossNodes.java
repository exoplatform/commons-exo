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
package org.exoplatform.services.cache.impl.infinispan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StateTransferConfiguration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.services.cache.CacheMode;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;

/**
 * Two nodes of a cluster, each with its own Infinispan 8 cache managers on the cluster templates of this module: a
 * node that creates a cache once the other holds entries in it receives, for a replicated cache, what no store holds
 * before its first read, as a verification code requested on one node and used on the other, while a cache
 * invalidated rather than replicated, or a local one, transfers nothing.
 */
public class TestReplicatedStateTransferAcrossNodes
{
   private static final String SYNC_TEMPLATE  = "jar:/conf/cache/infinispan/cluster/cache-config.xml";

   private static final String ASYNC_TEMPLATE = "jar:/conf/cache/infinispan/cluster/cache-async-config.xml";

   private static final String SYNC_TIMEOUT = "exo.cache.cluster.statetransfer.timeout";

   private static final String ASYNC_TIMEOUT = "exo.cache.async.cluster.statetransfer.timeout";

   private static final long SYNC_TIMEOUT_MILLIS = 230001;

   private static final long ASYNC_TIMEOUT_MILLIS = 230002;

   private static final long   TIMEOUT        = 30_000;

   /** How long the serialization of {@link SlowToSerialize} takes, as a node sends it in the state of a cache. */
   private static final long   SLOW_SERIALIZATION_MILLIS = 3_000;

   /** The state-transfer timeout the nodes give the caches they create, when a test sets one. */
   private volatile Long       stateTransferTimeout;

   private final Map<String, String> previousProperties = new HashMap<>();

   private final List<ExoCacheFactoryImpl> factories = new ArrayList<>();

   private ExoCacheFactoryImpl nodeA;

   private ExoCacheFactoryImpl nodeB;

   /**
    * Each node binds its own ports, far from the fixed ones a node running on the same host may hold, and the cluster
    * takes a name no other cluster has. Each node first creates an asynchronous cache that is not under test, which
    * starts its asynchronous cache manager, and the test goes on once the nodes of both managers see each other.
    */
   @Before
   public void startTwoNodes() throws Exception
   {
      int portA = freePorts();
      int portB = freePorts();
      setProperty("exo.cluster.partition.name", "state-transfer-" + portA);
      setProperty("exo.service.cluster.jgroups.tcpping.initial_hosts", "localhost[" + portA + "],localhost[" + portB + "]");
      setProperty("exo.service.cluster.jgroups.tcpping.port_range", "3");
      setProperty(SYNC_TIMEOUT, String.valueOf(SYNC_TIMEOUT_MILLIS));
      setProperty(ASYNC_TIMEOUT, String.valueOf(ASYNC_TIMEOUT_MILLIS));
      nodeA = startNode("node-a", portA);
      nodeB = startNode("node-b", portB);
      waitFor("the two nodes see each other", () -> factories.stream().allMatch(f -> members(f, "cacheManager") == 2
         && members(f, "asyncCacheManager") == 2));
   }

   @After
   public void stopTheNodes() throws Exception
   {
      try
      {
         for (ExoCacheFactoryImpl factory : factories)
         {
            EmbeddedCacheManager asyncManager = manager(factory, "asyncCacheManager");
            if (asyncManager != null)
            {
               asyncManager.stop();
            }
            factory.stop();
         }
      }
      finally
      {
         previousProperties.forEach((name, value) -> {
            if (value == null)
            {
               System.clearProperty(name);
            }
            else
            {
               System.setProperty(name, value);
            }
         });
      }
   }

   @Test
   public void testASynchronouslyReplicatedCacheTransfersItsEntriesToANodeThatCreatesItLater() throws Exception
   {
      assertTransferred("otp.sync", CacheMode.REPLICATION);
   }

   @Test
   public void testAnAsynchronouslyReplicatedCacheTransfersItsEntriesToANodeThatCreatesItLater() throws Exception
   {
      assertTransferred("otp.async", CacheMode.ASYNCREPLICATION);
   }

   @Test
   public void testEachClusterTemplateBoundsTheInitialStateTransferByItsOwnProperty() throws Exception
   {
      assertEquals(SYNC_TIMEOUT_MILLIS,
                   stateTransfer(nodeA.createCache(config("timeout.sync", CacheMode.REPLICATION))).timeout());
      assertEquals(ASYNC_TIMEOUT_MILLIS,
                   stateTransfer(nodeA.createCache(config("timeout.async", CacheMode.ASYNCREPLICATION))).timeout());
   }

   @Test
   public void testAnInvalidatedCacheTransfersNothing() throws Exception
   {
      for (CacheMode mode : new CacheMode[]{CacheMode.ASYNCINVALIDATION, CacheMode.SYNCINVALIDATION})
      {
         ExoCache<Serializable, Object> cache = nodeA.createCache(config("invalidated." + mode, mode));
         assertFalse(mode.name(), stateTransfer(cache).fetchInMemoryState());
      }
   }

   @Test
   public void testALocalCacheTransfersNothing() throws Exception
   {
      ExoCache<Serializable, Object> cache = nodeA.createCache(config("local", CacheMode.LOCAL));
      assertFalse(stateTransfer(cache).fetchInMemoryState());
   }

   @Test
   public void testAReplicatedCacheKeepsTheTemplateWhenTheTransferIsTurnedOff() throws Exception
   {
      System.setProperty(ExoCacheFactoryImpl.REPLICATED_STATE_TRANSFER, "false");
      PropertyManager.refresh();
      try
      {
         ExoCache<Serializable, Object> cache = nodeA.createCache(config("otp.off", CacheMode.REPLICATION));
         assertFalse(stateTransfer(cache).fetchInMemoryState());
      }
      finally
      {
         System.clearProperty(ExoCacheFactoryImpl.REPLICATED_STATE_TRANSFER);
         PropertyManager.refresh();
      }
   }

   @Test
   public void testANodeServesACacheOnlyOnceItsInitialStateArrived() throws Exception
   {
      ExoCache<Serializable, Object> first = nodeA.createCache(config("otp.wait", CacheMode.REPLICATION));
      first.put("slow", new SlowToSerialize());

      ExoCache<Serializable, Object> second = nodeB.createCache(config("otp.wait", CacheMode.REPLICATION));

      // The state takes SLOW_SERIALIZATION_MILLIS to leave node-a. The size is local: served before the state arrived,
      // the cache would count nothing (a read would not tell, it fetches a missing entry from node-a)
      assertEquals(1, second.getCacheSize());
   }

   @Test
   public void testANodeServesACacheWhoseInitialStateTransferTimesOutAndReceivesTheEntriesLater() throws Exception
   {
      stateTransferTimeout = 1_000L;
      ExoCache<Serializable, Object> first = nodeA.createCache(config("otp.timeout", CacheMode.REPLICATION));
      first.put("code", "123456");
      first.put("slow", new SlowToSerialize());

      // The transfer outlasts the timeout: the cache starts all the same, before the state arrived, and serves
      long start = System.nanoTime();
      ExoCache<Serializable, Object> second = nodeB.createCache(config("otp.timeout", CacheMode.REPLICATION));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < SLOW_SERIALIZATION_MILLIS);
      assertTrue(second.getCacheSize() < 2);
      second.put("new-code", "654321");
      assertEquals("654321", second.get("new-code"));

      // The transfer goes on in the background: node-a's two entries then count locally
      waitFor("the entries of node-a reach node-b", () -> second.getCacheSize() >= 3);
   }

   /**
    * A cached value whose serialization, as a node sends it in the state of a cache, takes
    * {@link #SLOW_SERIALIZATION_MILLIS}.
    */
   static final class SlowToSerialize implements Serializable
   {
      private static final long serialVersionUID = 1L;

      private void writeObject(ObjectOutputStream out) throws IOException
      {
         try
         {
            Thread.sleep(SLOW_SERIALIZATION_MILLIS);
         }
         catch (InterruptedException e)
         {
            Thread.currentThread().interrupt();
         }
         out.defaultWriteObject();
      }
   }

   private void assertTransferred(String name, CacheMode mode) throws Exception
   {
      ExoCache<Serializable, Object> first = nodeA.createCache(config(name, mode));
      assertTrue(name, stateTransfer(first).fetchInMemoryState());
      // The factory waits for the transfer, not Infinispan's start, which would fail and keep the cache past a timeout
      assertFalse(name, stateTransfer(first).awaitInitialTransfer());
      first.put("code", "123456");

      // The other node creates the cache after the entry exists, as at its first use of the cache
      ExoCache<Serializable, Object> second = nodeB.createCache(config(name, mode));

      assertEquals("123456", second.get("code"));
      assertNull(second.get("other"));
   }

   private static StateTransferConfiguration stateTransfer(ExoCache<Serializable, Object> cache) throws Exception
   {
      Field field = AbstractExoCache.class.getDeclaredField("cache");
      field.setAccessible(true);
      return ((org.infinispan.AdvancedCache<?, ?>)field.get(cache)).getCacheConfiguration().clustering().stateTransfer();
   }

   private static ExoCacheConfig config(String name, CacheMode mode)
   {
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName(name);
      config.setMaxSize(100);
      config.setLiveTime(600);
      config.setCacheMode(mode);
      return config;
   }

   private ExoCacheFactoryImpl startNode(String nodeName, int port) throws Exception
   {
      setProperty("exo.cluster.node.name", nodeName);
      // The channels of a node share one transport, which a second node in the same JVM must not share
      setProperty("exo.service.cluster.jgroups.tcp.singleton_name", nodeName + "-" + port);
      setProperty("exo.service.cluster.jgroups.tcp.bind_port", String.valueOf(port));
      setProperty("exo.service.cluster.jgroups.fd_sock.start_port", String.valueOf(freePorts()));
      PortalContainer container = PortalContainer.getInstance();
      // The same context name on both nodes: it is part of the name of the cluster
      ExoCacheFactoryImpl factory = new ExoCacheFactoryImpl(new ExoContainerContext(container, "state-transfer"),
                                                            SYNC_TEMPLATE,
                                                            ASYNC_TEMPLATE,
                                                            container.getComponentInstanceOfType(ConfigurationManager.class),
                                                            null)
      {
         @Override
         protected void configureStateTransfer(ConfigurationBuilder confBuilder)
         {
            super.configureStateTransfer(confBuilder);
            Long timeout = stateTransferTimeout;
            if (timeout != null)
            {
               confBuilder.clustering().stateTransfer().timeout(timeout);
            }
         }
      };
      factories.add(factory);
      // Starts the asynchronous cache manager of this node
      factory.createCache(config("warm-up", CacheMode.ASYNCINVALIDATION));
      return factory;
   }

   private static int members(ExoCacheFactoryImpl factory, String field)
   {
      try
      {
         EmbeddedCacheManager manager = manager(factory, field);
         return manager == null || manager.getMembers() == null ? 0 : manager.getMembers().size();
      }
      catch (Exception e)
      {
         throw new IllegalStateException(e);
      }
   }

   private static EmbeddedCacheManager manager(ExoCacheFactoryImpl factory, String field) throws Exception
   {
      Field manager = ExoCacheFactoryImpl.class.getDeclaredField(field);
      manager.setAccessible(true);
      return (EmbeddedCacheManager)manager.get(factory);
   }

   private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException
   {
      long deadline = System.currentTimeMillis() + TIMEOUT;
      while (!condition.getAsBoolean())
      {
         if (System.currentTimeMillis() > deadline)
         {
            fail("Not within " + TIMEOUT + " ms: " + what);
         }
         Thread.sleep(100);
      }
   }

   private void setProperty(String name, String value)
   {
      previousProperties.putIfAbsent(name, System.getProperty(name));
      System.setProperty(name, value);
   }

   /**
    * @return the first of four consecutive free ports
    */
   private static int freePorts() throws IOException
   {
      for (int attempt = 0; attempt < 50; attempt++)
      {
         int port;
         try (ServerSocket socket = new ServerSocket(0))
         {
            port = socket.getLocalPort();
         }
         if (port < 65000 && free(port + 1) && free(port + 2) && free(port + 3))
         {
            return port;
         }
      }
      throw new IOException("No four consecutive free ports");
   }

   private static boolean free(int port)
   {
      try (ServerSocket socket = new ServerSocket(port))
      {
         return true;
      }
      catch (IOException e)
      {
         return false;
      }
   }
}
