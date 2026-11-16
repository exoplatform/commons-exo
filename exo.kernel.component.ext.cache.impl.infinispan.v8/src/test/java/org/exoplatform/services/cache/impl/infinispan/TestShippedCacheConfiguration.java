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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.configuration.ConfigurationUnmarshaller;
import org.exoplatform.container.xml.ComponentPlugin;
import org.exoplatform.container.xml.Configuration;
import org.exoplatform.container.xml.ExternalComponentPlugins;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ObjectParameter;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheConfigPlugin;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.impl.CacheServiceImpl;
import org.exoplatform.services.cache.invalidation.AsyncInvalidationExoCache;
import org.exoplatform.services.cache.invalidation.InvalidationExoCache;

/**
 * Every cache the extension ships is created, with the cluster profile, by the factory and the creator the same file
 * declares, on the cluster templates of this module, and runs in the mode its declaration names: the cache service
 * only logs a cache it cannot create and hands its caller none, and the service that reads it then fails the start of
 * the portal container, so that every REST call answers 500. The default configuration of this test names no
 * implementation, so its local caches go through the Infinispan creator too, where the platform creates them as
 * {@code ConcurrentFIFOExoCache}.
 */
public class TestShippedCacheConfiguration
{
   private static final Path    COMMONS_EXO_CACHES = Path.of("..", "commons-exo-extension", "src", "main", "webapp",
      "WEB-INF", "conf", "commons-exo", "cache-configuration.xml");

   private static final Pattern PROFILES           = Pattern.compile("profiles=\"([^\"]+)\"");

   /**
    * The caches the file declares with every profile it names: a declaration the reading drops is not created either.
    */
   private static final int     DECLARED_CACHES    = 74;

   private final Map<String, String> previousProperties = new HashMap<>();

   private ExoCacheFactoryImpl factory;

   /**
    * The cluster templates bind JGroups to fixed local ports, which a node running on the same host may hold: this
    * node gets ports of its own and a cluster name no other node has, so that it neither joins nor disturbs one.
    */
   @Before
   public void isolateTheCluster() throws IOException
   {
      int bindPort = freePort();
      setProperty("exo.cluster.partition.name", "shipped-caches-" + bindPort);
      setProperty("exo.cluster.node.name", "shipped-caches-node");
      setProperty("exo.service.cluster.jgroups.tcp.bind_port", String.valueOf(bindPort));
      setProperty("exo.service.cluster.jgroups.tcpping.initial_hosts", "localhost[" + bindPort + "]");
      setProperty("exo.service.cluster.jgroups.fd_sock.start_port", String.valueOf(freePort()));
   }

   @After
   public void stopTheCluster() throws Exception
   {
      try
      {
         if (factory != null)
         {
            Field asyncCacheManager = ExoCacheFactoryImpl.class.getDeclaredField("asyncCacheManager");
            asyncCacheManager.setAccessible(true);
            EmbeddedCacheManager asyncManager = (EmbeddedCacheManager)asyncCacheManager.get(factory);
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
   public void testEveryShippedCacheIsCreatedWithItsClusterMode() throws Exception
   {
      Configuration configuration =
         new ConfigurationUnmarshaller(profiles()).unmarshall(COMMONS_EXO_CACHES.toUri().toURL());
      PortalContainer container = PortalContainer.getInstance();
      factory = new ExoCacheFactoryImpl(new ExoContainerContext(container, "shipped-caches"),
         configuration.getComponent(ExoCacheFactory.class.getName()).getInitParams(),
         container.getComponentInstanceOfType(ConfigurationManager.class));
      CacheServiceImpl service = new CacheServiceImpl(defaultConfiguration(), factory);
      List<ExoCacheConfig> configs = new ArrayList<>();
      Iterator<ExternalComponentPlugins> targets = configuration.getExternalComponentPluginsIterator();
      while (targets.hasNext())
      {
         ExternalComponentPlugins target = targets.next();
         for (ComponentPlugin plugin : target.getComponentPlugins())
         {
            if (ExoCacheFactory.class.getName().equals(target.getTargetComponent()))
            {
               factory.addCreator(new ExoCacheCreatorPlugin(plugin.getInitParams()));
            }
            else if (CacheService.class.getName().equals(target.getTargetComponent()))
            {
               ExoCacheConfigPlugin configPlugin = new ExoCacheConfigPlugin(plugin.getInitParams());
               service.addExoCacheConfig(configPlugin);
               configs.addAll(configPlugin.getConfigs());
            }
            else
            {
               fail("Unexpected target " + target.getTargetComponent() + " in " + COMMONS_EXO_CACHES);
            }
         }
      }
      assertEquals("Caches declared in " + COMMONS_EXO_CACHES.toAbsolutePath(), DECLARED_CACHES, configs.size());

      List<String> failures = new ArrayList<>();
      for (ExoCacheConfig config : configs)
      {
         String failure = checkMode(config, service.getCacheInstance(config.getName()));
         if (failure != null)
         {
            failures.add(failure);
         }
      }
      assertTrue((configs.size() - failures.size()) + " of " + configs.size()
         + " caches created with their mode, the others:\n" + String.join("\n", failures), failures.isEmpty());
   }

   private static String checkMode(ExoCacheConfig config, ExoCache<?, ?> cache)
   {
      String cacheName = config.getName() + " (" + config.getCacheMode() + ")";
      if (cache == null)
      {
         return cacheName + " is not created, see the error logged for it";
      }
      switch (config.getCacheMode())
      {
         case ASYNCINVALIDATION:
            return cache instanceof AsyncInvalidationExoCache ? null
               : cacheName + " is a " + cache.getClass().getName() + ", not an async invalidation cache";
         case SYNCINVALIDATION:
            return cache instanceof InvalidationExoCache ? null
               : cacheName + " is a " + cache.getClass().getName() + ", not an invalidation cache";
         default:
            if (!(cache instanceof AbstractExoCache))
            {
               return cacheName + " is a " + cache.getClass().getName() + ", not an Infinispan cache";
            }
            org.infinispan.configuration.cache.CacheMode mode =
               ((AbstractExoCache<?, ?>)cache).cache.getCacheConfiguration().clustering().cacheMode();
            return mode.isReplicated() == config.isRepicated() ? null : cacheName + " runs in " + mode + " mode";
      }
   }

   /**
    * The cluster profile, and every profile the file names, so that each declaration is read.
    */
   private static Set<String> profiles() throws IOException
   {
      Set<String> profiles = new HashSet<>(Set.of("cluster"));
      Matcher matcher = PROFILES.matcher(Files.readString(COMMONS_EXO_CACHES));
      while (matcher.find())
      {
         for (String profile : matcher.group(1).split(","))
         {
            profiles.add(profile.trim());
         }
      }
      return profiles;
   }

   private static InitParams defaultConfiguration()
   {
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName("default");
      config.setMaxSize(10);
      ObjectParameter parameter = new ObjectParameter();
      parameter.setName("cache.config.default");
      parameter.setObject(config);
      InitParams params = new InitParams();
      params.addParameter(parameter);
      return params;
   }

   private void setProperty(String name, String value)
   {
      previousProperties.put(name, System.setProperty(name, value));
   }

   private static int freePort() throws IOException
   {
      try (ServerSocket socket = new ServerSocket(0))
      {
         return socket.getLocalPort();
      }
   }
}
