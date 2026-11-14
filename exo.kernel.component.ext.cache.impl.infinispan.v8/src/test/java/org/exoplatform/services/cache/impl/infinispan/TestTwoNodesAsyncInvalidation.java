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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.configuration.ConfigurationUnmarshaller;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ObjectParameter;
import org.exoplatform.services.cache.CacheMode;
import org.exoplatform.services.cache.ClusterCachedObjectSelector;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheConfigPlugin;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.ObjectCacheInfo;
import org.exoplatform.services.cache.impl.CacheServiceImpl;
import org.exoplatform.services.cache.invalidation.AsyncInvalidationExoCache;

/**
 * Two nodes of a cluster, each with its own Infinispan 8 cache managers on the cluster templates of this module, share
 * an asynchronously invalidated cache: the entries a node loaded go when another node removes their key, which it
 * never loaded, clears its cache, or removes entries by a selection, so that the change reaches the other node as it
 * travels over JGroups, not through a test double.
 */
public class TestTwoNodesAsyncInvalidation
{
   private static final Path   COMMONS_EXO_CACHES = Path.of("..", "commons-exo-extension", "src", "main", "webapp",
      "WEB-INF", "conf", "commons-exo", "cache-configuration.xml");

   private static final String CACHE_NAME         = "two-nodes";

   private static final long   TIMEOUT            = 30_000;

   private final Map<String, String> previousProperties = new HashMap<>();

   private final List<ExoCacheFactoryImpl> factories = new ArrayList<>();

   private ExoCache<String, String> nodeA;

   private ExoCache<String, String> nodeB;

   /**
    * Each node binds its own ports, far from the fixed ones a node running on the same host may hold, and the cluster
    * takes a name no other cluster has. The asynchronous cache manager of a node binds the port after the one of its
    * main cache manager, which the discovery reaches through its port range.
    */
   @Before
   public void startTwoNodes() throws Exception
   {
      int portA = freePorts();
      int portB = freePorts();
      String hosts = "localhost[" + portA + "],localhost[" + portB + "]";
      setProperty("exo.cluster.partition.name", "two-nodes-" + portA);
      setProperty("exo.service.cluster.jgroups.tcpping.initial_hosts", hosts);
      setProperty("exo.service.cluster.jgroups.tcpping.port_range", "3");
      nodeA = startNode("node-a", portA);
      nodeB = startNode("node-b", portB);
      waitFor("the two nodes see each other", () -> factories.stream().allMatch(f -> members(f) == 2));
   }

   @After
   public void stopTheNodes() throws Exception
   {
      try
      {
         for (ExoCacheFactoryImpl factory : factories)
         {
            EmbeddedCacheManager asyncManager = asyncCacheManager(factory);
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
   public void testARemoveOfAKeyTheNodeNeverLoadedReachesTheOtherNode() throws Exception
   {
      assertTrue(nodeA instanceof AsyncInvalidationExoCache);
      nodeB.putLocal("key", "loaded by B");
      assertNull(nodeA.get("key"));

      nodeA.remove("key");

      waitFor("node B no longer holds the key node A removed", () -> nodeB.get("key") == null);
   }

   @Test
   public void testAClearReachesTheOtherNode() throws Exception
   {
      nodeB.putLocal("key", "loaded by B");

      nodeA.clearCache();

      waitFor("node B is cleared", () -> nodeB.get("key") == null);
   }

   @Test
   public void testASelectionReachesTheOtherNodeWhichRemovesTheEntriesItSelects() throws Exception
   {
      nodeB.putLocal("selected-1", "loaded by B");
      nodeB.putLocal("kept", "loaded by B");

      nodeA.select(new PrefixSelector("selected-"));

      waitFor("node B no longer holds the selected entry", () -> nodeB.get("selected-1") == null);
      assertEquals("loaded by B", nodeB.get("kept"));
   }

   private ExoCache<String, String> startNode(String nodeName, int port) throws Exception
   {
      setProperty("exo.cluster.node.name", nodeName);
      // The channels of a node share one transport, which a second node in the same JVM must not share
      setProperty("exo.service.cluster.jgroups.tcp.singleton_name", nodeName + "-" + port);
      setProperty("exo.service.cluster.jgroups.tcp.bind_port", String.valueOf(port));
      setProperty("exo.service.cluster.jgroups.fd_sock.start_port", String.valueOf(freePorts()));
      org.exoplatform.container.xml.Configuration configuration = new ConfigurationUnmarshaller(Set.of("cluster"))
         .unmarshall(COMMONS_EXO_CACHES.toUri().toURL());
      PortalContainer container = PortalContainer.getInstance();
      // The same context name on both nodes: it is part of the name of the cluster
      ExoCacheFactoryImpl factory = new ExoCacheFactoryImpl(new ExoContainerContext(container, "two-nodes"),
         configuration.getComponent(ExoCacheFactory.class.getName()).getInitParams(),
         container.getComponentInstanceOfType(ConfigurationManager.class));
      factories.add(factory);
      CacheServiceImpl service = new CacheServiceImpl(params(config("default", null)), factory);
      service.addExoCacheConfig(new ExoCacheConfigPlugin(params(config(CACHE_NAME, CacheMode.ASYNCINVALIDATION))));
      // The asynchronous cache manager starts with the first cache that needs it, on the properties of this node
      return service.getCacheInstance(CACHE_NAME);
   }

   private static ExoCacheConfig config(String name, CacheMode mode)
   {
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName(name);
      config.setMaxSize(100);
      config.setLiveTime(600);
      if (mode != null)
      {
         config.setCacheMode(mode);
      }
      return config;
   }

   private static InitParams params(ExoCacheConfig config)
   {
      ObjectParameter parameter = new ObjectParameter();
      parameter.setName(config.getName().equals("default") ? "cache.config.default" : config.getName());
      parameter.setObject(config);
      InitParams params = new InitParams();
      params.addParameter(parameter);
      return params;
   }

   private static int members(ExoCacheFactoryImpl factory)
   {
      try
      {
         EmbeddedCacheManager manager = asyncCacheManager(factory);
         return manager == null || manager.getMembers() == null ? 0 : manager.getMembers().size();
      }
      catch (Exception e)
      {
         throw new IllegalStateException(e);
      }
   }

   private static EmbeddedCacheManager asyncCacheManager(ExoCacheFactoryImpl factory) throws Exception
   {
      Field asyncCacheManager = ExoCacheFactoryImpl.class.getDeclaredField("asyncCacheManager");
      asyncCacheManager.setAccessible(true);
      return (EmbeddedCacheManager)asyncCacheManager.get(factory);
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

   /**
    * Selects the entries whose key starts with a prefix, on every node that receives it.
    */
   private static class PrefixSelector extends ClusterCachedObjectSelector<Serializable, Object>
   {
      private static final long serialVersionUID = 1L;

      private final String      prefix;

      PrefixSelector(String prefix)
      {
         this.prefix = prefix;
      }

      @Override
      protected Object[] criteria()
      {
         return new Object[]{prefix};
      }

      @Override
      public boolean select(Serializable key, ObjectCacheInfo<? extends Object> ocinfo)
      {
         return key.toString().startsWith(prefix);
      }
   }
}
