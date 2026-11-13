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
package org.exoplatform.services.cache.impl.infinispan.generic;

import static org.junit.Assert.assertEquals;

import java.io.Serializable;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.invalidation.AsyncInvalidationExoCache;

/**
 * The live time of a cache is given in seconds whatever its configuration, and Infinispan takes it in milliseconds.
 */
public class TestGenericExoCacheCreator
{
   private static DefaultCacheManager manager;

   @BeforeClass
   public static void startManager()
   {
      GlobalConfigurationBuilder global = new GlobalConfigurationBuilder().nonClusteredDefault();
      global.cacheManagerName("generic-creator-test").jmx().enabled(false);
      manager = new DefaultCacheManager(global.build());
   }

   @AfterClass
   public static void stopManager()
   {
      if (manager != null)
      {
         manager.stop();
      }
   }

   @Test
   public void testLiveTimeOfAGenericConfigurationIsInSeconds() throws Exception
   {
      GenericExoCacheConfig config = new GenericExoCacheConfig();
      config.setName("generic");
      config.setMaxSize(10);
      config.setLiveTime(20);

      ExoCache<Serializable, Object> cache = create(config);

      assertEquals(20_000, getLifespan("generic"));
      assertEquals(20, cache.getLiveTime());
   }

   @Test
   public void testLiveTimeOfAConfigurationIsInSeconds() throws Exception
   {
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName("plain");
      config.setMaxSize(10);
      config.setLiveTime(600);

      ExoCache<Serializable, Object> cache = create(config);

      assertEquals(600_000, getLifespan("plain"));
      assertEquals(600, cache.getLiveTime());
   }

   @Test
   public void testCacheWithoutLiveTimeNeverExpires() throws Exception
   {
      GenericExoCacheConfig config = new GenericExoCacheConfig();
      config.setName("eternal");
      config.setMaxSize(10);

      ExoCache<Serializable, Object> cache = create(config);

      assertEquals(-1, getLifespan("eternal"));
      assertEquals(-1, cache.getLiveTime());
   }

   @Test
   @SuppressWarnings({"unchecked", "rawtypes"})
   public void testLocalCacheOfAnInvalidatedCacheLivesAsLong() throws Exception
   {
      GenericExoCacheConfig config = new GenericExoCacheConfig();
      config.setName("invalidated");
      config.setMaxSize(10);
      config.setLiveTime(20);

      AsyncInvalidationExoCache<Serializable, Object> cache = new AsyncInvalidationExoCache<>((ExoCache)create(config));

      assertEquals(20, cache.getLiveTime());
   }

   private static ExoCache<Serializable, Object> create(ExoCacheConfig config) throws Exception
   {
      ConfigurationBuilder builder = new ConfigurationBuilder();
      return new GenericExoCacheCreator().create(config, builder, () -> {
         manager.defineConfiguration(config.getName(), builder.build());
         return manager.getCache(config.getName());
      });
   }

   private static long getLifespan(String name)
   {
      return manager.getCache(name).getCacheConfiguration().expiration().lifespan();
   }
}
