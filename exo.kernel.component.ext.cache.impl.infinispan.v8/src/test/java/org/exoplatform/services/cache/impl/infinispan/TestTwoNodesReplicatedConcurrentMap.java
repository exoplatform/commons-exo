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
import java.util.concurrent.ConcurrentHashMap;
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
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheConfigPlugin;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.impl.CacheServiceImpl;

/**
 * Two nodes of a cluster share a replicated cache whose values hold a {@link ConcurrentHashMap}, as the coediting locks
 * and the viewer configurations of the add-ons do. Serializing such a value reaches into
 * <code>java.util.concurrent.locks</code>, which the JVM of the bundle opens (<code>setenv.sh</code> of the platform
 * packaging, <code>JDK_JAVA_OPTIONS</code>): without it, the write fails and nothing is stored.
 */
public class TestTwoNodesReplicatedConcurrentMap
{
   private static final Path   COMMONS_EXO_CACHES = Path.of("..", "commons-exo-extension", "src", "main", "webapp",
      "WEB-INF", "conf", "commons-exo", "cache-configuration.xml");

   private static final String CACHE_NAME         = "two-nodes-replicated";

   private static final long   TIMEOUT            = 30_000;

   private final Map<String, String> previousProperties = new HashMap<>();

   private final List<ExoCacheFactoryImpl> factories = new ArrayList<>();

   private ExoCache<String, Holder> nodeA;

   private ExoCache<String, Holder> nodeB;

   @Before
   public void startTwoNodes() throws Exception
   {
      int portA = freePorts();
      int portB = freePorts();
      String hosts = "localhost[" + portA + "],localhost[" + portB + "]";
      setProperty("exo.cluster.partition.name", "two-nodes-repl-" + portA);
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
   public void testAValueHoldingAConcurrentHashMapReachesTheOtherNode() throws Exception
   {
      Holder holder = new Holder();
      holder.entries.put("user", "lock owner");

      nodeA.put("key", holder);

      waitFor("node B holds the value node A put", () -> nodeB.get("key") != null);
      assertEquals("lock owner", nodeB.get("key").entries.get("user"));
   }

   private ExoCache<String, Holder> startNode(String nodeName, int port) throws Exception
   {
      setProperty("exo.cluster.node.name", nodeName);
      setProperty("exo.service.cluster.jgroups.tcp.singleton_name", nodeName + "-" + port);
      setProperty("exo.service.cluster.jgroups.tcp.bind_port", String.valueOf(port));
      setProperty("exo.service.cluster.jgroups.fd_sock.start_port", String.valueOf(freePorts()));
      org.exoplatform.container.xml.Configuration configuration = new ConfigurationUnmarshaller(Set.of("cluster"))
         .unmarshall(COMMONS_EXO_CACHES.toUri().toURL());
      PortalContainer container = PortalContainer.getInstance();
      ExoCacheFactoryImpl factory = new ExoCacheFactoryImpl(new ExoContainerContext(container, "two-nodes-repl"),
         configuration.getComponent(ExoCacheFactory.class.getName()).getInitParams(),
         container.getComponentInstanceOfType(ConfigurationManager.class));
      factories.add(factory);
      CacheServiceImpl service = new CacheServiceImpl(params(config("default", null)), factory);
      service.addExoCacheConfig(new ExoCacheConfigPlugin(params(config(CACHE_NAME, CacheMode.REPLICATION))));
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
         Field cacheManager = ExoCacheFactoryImpl.class.getDeclaredField("cacheManager");
         cacheManager.setAccessible(true);
         EmbeddedCacheManager manager = (EmbeddedCacheManager)cacheManager.get(factory);
         return manager == null || manager.getMembers() == null ? 0 : manager.getMembers().size();
      }
      catch (Exception e)
      {
         throw new IllegalStateException(e);
      }
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
    * A value that holds a {@link ConcurrentHashMap}, as the coediting lock does.
    */
   private static class Holder implements Serializable
   {
      private static final long serialVersionUID = 1L;

      private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();
   }
}
