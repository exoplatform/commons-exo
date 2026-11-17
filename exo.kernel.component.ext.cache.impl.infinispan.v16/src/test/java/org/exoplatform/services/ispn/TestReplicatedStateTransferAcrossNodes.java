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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.services.cache.CacheMode;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.impl.infinispan.AbstractExoCache;
import org.exoplatform.services.cache.impl.infinispan.ExoCacheFactoryImpl;
import org.infinispan.AdvancedCache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StateTransferConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A node that creates a cache on the cluster templates the extension ships, once another node holds entries in it:
 * what no store holds, in a replicated cache, reaches the new node before its first read, as a verification code
 * requested on one node and used on the other, while a cache invalidated rather than replicated transfers nothing.
 */
public class TestReplicatedStateTransferAcrossNodes
{
   private static final String PROPERTY_PREFIX = "exo.service.cluster.jgroups.";

   private static final String SYNC_TEMPLATE = "jar:/conf/cache/infinispan/cluster/cache-config.xml";

   private static final String ASYNC_TEMPLATE = "jar:/conf/cache/infinispan/cluster/cache-async-config.xml";

   /** The state-transfer and remote timeouts of the node that joins late: a join request is answered well within it. */
   private static final long STATE_TRANSFER_TIMEOUT_MILLIS = 1000L;

   /** How long the serialization of {@link SlowToSerialize} takes: three times the timeout it must outlast. */
   private static final long SLOW_SERIALIZATION_MILLIS = 3 * STATE_TRANSFER_TIMEOUT_MILLIS;

   private static ExoCacheFactoryImpl node1;

   private static ExoCacheFactoryImpl node2;

   private static int[] ports;

   @BeforeClass
   public static void startNodes() throws Exception
   {
      // The third port is the one of a node that a test starts itself
      ports = TestSharedJGroupsTransport.freePorts(3);
      System.setProperty("exo.cluster.partition.name", "state-transfer-test");
      node1 = startNode(ports, 0, "node1");
      node2 = startNode(ports, 1, "node2");
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
      System.clearProperty("exo.cluster.partition.name");
      System.clearProperty("exo.cluster.node.name");
      for (String name : System.getProperties().stringPropertyNames())
      {
         if (name.startsWith(PROPERTY_PREFIX))
         {
            System.clearProperty(name);
         }
      }
      // The partition name is read through the property cache: drop the values set here for the tests that follow
      PropertyManager.refresh();
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
   public void testAnInvalidatedCacheTransfersNothing() throws Exception
   {
      for (CacheMode mode : new CacheMode[] {CacheMode.ASYNCINVALIDATION, CacheMode.SYNCINVALIDATION})
      {
         ExoCache<Serializable, Object> cache = node1.createCache(config("invalidated." + mode, mode));
         StateTransferConfiguration stateTransfer = stateTransfer(cache);
         assertFalse(mode.name(), stateTransfer.fetchInMemoryState());
      }
   }

   @Test
   public void testALocalCacheTransfersNothing() throws Exception
   {
      ExoCache<Serializable, Object> cache = node1.createCache(config("local", CacheMode.LOCAL));
      assertFalse(stateTransfer(cache).fetchInMemoryState());
   }

   @Test
   public void testAReplicatedCacheKeepsTheTemplateWhenTheTransferIsTurnedOff() throws Exception
   {
      System.setProperty(ExoCacheFactoryImpl.REPLICATED_STATE_TRANSFER, "false");
      PropertyManager.refresh();
      try
      {
         ExoCache<Serializable, Object> cache = node1.createCache(config("otp.off", CacheMode.REPLICATION));
         assertFalse(stateTransfer(cache).fetchInMemoryState());
      }
      finally
      {
         System.clearProperty(ExoCacheFactoryImpl.REPLICATED_STATE_TRANSFER);
         PropertyManager.refresh();
      }
   }

   @Test
   public void testAReplicatedCacheStartsWithoutTheTransferredEntriesWhenTheStateTransferTimesOut() throws Exception
   {
      ExoCache<Serializable, Object> first = node1.createCache(config("otp.timeout", CacheMode.REPLICATION));
      first.put("code", "123456");
      // The state that node1 sends holds an entry whose serialization outlasts the timeout of the joining node, while
      // its join request is answered well within it: the cache must still start, and serve
      first.put("slow", new SlowToSerialize());

      ExoCacheFactoryImpl slowNode = startNode(ports, 2, "node3", STATE_TRANSFER_TIMEOUT_MILLIS);
      try
      {
         ExoCache<Serializable, Object> late = slowNode.createCache(config("otp.timeout", CacheMode.REPLICATION));
         assertNotNull(late);
         assertTrue(stateTransfer(late).fetchInMemoryState());
         assertFalse(stateTransfer(late).awaitInitialTransfer());
         // A read is local on a replicated cache: it does not fail whether or not the entry arrived yet
         late.get("code");
         // The started cache serves: what this node writes, it reads back
         late.put("new-code", "654321");
         assertEquals("654321", late.get("new-code"));
         // A new transfer fills the restarted cache in the background: the entries of node1 arrive once their state is sent
         long deadline = System.currentTimeMillis() + 5 * SLOW_SERIALIZATION_MILLIS;
         while (late.get("code") == null && System.currentTimeMillis() < deadline)
         {
            Thread.sleep(100);
         }
         assertEquals("123456", late.get("code"));
      }
      finally
      {
         slowNode.stop();
      }
   }

   private static void assertTransferred(String name, CacheMode mode) throws Exception
   {
      ExoCache<Serializable, Object> first = node1.createCache(config(name, mode));
      assertTrue(name, stateTransfer(first).fetchInMemoryState());
      assertTrue(name, stateTransfer(first).awaitInitialTransfer());
      first.put("code", "123456");

      // The other node creates the cache after the entry exists, as at its first use of the cache
      ExoCache<Serializable, Object> second = node2.createCache(config(name, mode));

      assertEquals("123456", second.get("code"));
      assertNull(second.get("other"));
   }

   private static StateTransferConfiguration stateTransfer(ExoCache<Serializable, Object> cache) throws Exception
   {
      Field field = AbstractExoCache.class.getDeclaredField("cache");
      field.setAccessible(true);
      return ((AdvancedCache<?, ?>)field.get(cache)).getCacheConfiguration().clustering().stateTransfer();
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

   /**
    * A cached value whose serialization, as a node sends it in the state of a cache, outlasts the state-transfer
    * timeout of the node that receives it. A put on a cache that no other node holds yet does not serialize it.
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

   private static ExoCacheFactoryImpl startNode(int[] ports, int index, String nodeName) throws Exception
   {
      return startNode(ports, index, nodeName, null);
   }

   private static ExoCacheFactoryImpl startNode(int[] ports, int index, String nodeName, Long stateTransferTimeout)
      throws Exception
   {
      // The stack and the node name are read as the factory starts its cache managers
      TestSharedJGroupsTransport.stack(ports[index], ports);
      System.setProperty("exo.cluster.node.name", nodeName);
      PortalContainer container = PortalContainer.getInstance();
      return new ExoCacheFactoryImpl(container.getComponentInstanceOfType(ExoContainerContext.class),
                                     SYNC_TEMPLATE,
                                     ASYNC_TEMPLATE,
                                     container.getComponentInstanceOfType(ConfigurationManager.class))
      {
         @Override
         protected void configureStateTransfer(ConfigurationBuilder confBuilder)
         {
            super.configureStateTransfer(confBuilder);
            if (stateTransferTimeout != null)
            {
               // The state-transfer timeout may not be lower than the remote timeout
               confBuilder.clustering().remoteTimeout(stateTransferTimeout).stateTransfer().timeout(stateTransferTimeout);
            }
         }
      };
   }
}
