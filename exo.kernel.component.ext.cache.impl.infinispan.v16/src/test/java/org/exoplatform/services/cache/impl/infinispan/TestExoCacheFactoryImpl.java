/*
 * Copyright (C) 2010 eXo Platform SAS.
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

import junit.framework.TestCase;

import java.lang.reflect.Field;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.ExoCacheInitException;
import org.exoplatform.services.cache.impl.infinispan.TestExoCacheCreator.TestExoCache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.CacheContainer;

/**
 * @author <a href="mailto:nicolas.filotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
@SuppressWarnings("rawtypes")
public class TestExoCacheFactoryImpl extends TestCase
{

   CacheService service_;

   public TestExoCacheFactoryImpl(String name)
   {
      super(name);
   }

   public void setUp() throws Exception
   {
      service_ = (CacheService)PortalContainer.getInstance().getComponentInstanceOfType(CacheService.class);
   }

   public void testCacheFactory()
   {
      ExoCache cache = service_.getCacheInstance("myCache");
      assertTrue("expect an instance of AbstractExoCache", cache instanceof AbstractExoCache);
      AbstractExoCache aCache = (AbstractExoCache)cache;
      assertTrue("expect a local cache",
         aCache.cache.getCacheConfiguration().clustering().cacheMode() == CacheMode.LOCAL);
      aCache.cache.stop();
      cache = service_.getCacheInstance("cacheReplicated");
      assertTrue("expect an instance of AbstractExoCache", cache instanceof AbstractExoCache);
      aCache = (AbstractExoCache)cache;
      assertTrue("expect a replicated cache",
         aCache.cache.getCacheConfiguration().clustering().cacheMode() == CacheMode.REPL_SYNC);
      aCache.cache.stop();
      cache = service_.getCacheInstance("myCustomCache");
      assertTrue("expect an instance of AbstractExoCache", cache instanceof AbstractExoCache);
      aCache = (AbstractExoCache)cache;
      assertTrue("expect a replicated cache",
         aCache.cache.getCacheConfiguration().clustering().cacheMode() == CacheMode.REPL_SYNC);
      aCache.cache.stop();
   }

   public void testDistributedCacheIsRefused()
   {
      ExoCacheFactory factory = PortalContainer.getInstance().getComponentInstanceOfType(ExoCacheFactory.class);
      ExoCacheConfig config = new ExoCacheConfig();
      config.setName("cache-distributed");
      config.setDistributed(true);
      try
      {
         factory.createCache(config);
         fail("A distributed cache must be refused");
      }
      catch (ExoCacheInitException e)
      {
         assertTrue(e.getMessage(), e.getMessage().contains("distributed"));
      }
   }

   public void testAsyncCacheManagerStartsAndStopsWithTheFactory() throws Exception
   {
      PortalContainer container = PortalContainer.getInstance();
      String template = "jar:/conf/cache/infinispan/local/cache-config.xml";
      ExoCacheFactoryImpl factory =
         new ExoCacheFactoryImpl(container.getComponentInstanceOfType(ExoContainerContext.class), template, template,
                                 container.getComponentInstanceOfType(ConfigurationManager.class));
      Field field = ExoCacheFactoryImpl.class.getDeclaredField("asyncCacheManager");
      field.setAccessible(true);
      DefaultCacheManager asyncCacheManager = (DefaultCacheManager)field.get(factory);
      assertEquals(ComponentStatus.RUNNING, asyncCacheManager.getStatus());
      factory.stop();
      assertEquals(ComponentStatus.TERMINATED, asyncCacheManager.getStatus());
   }

   public void testExoCacheCreator()
   {
      ExoCache cache = service_.getCacheInstance("test-default-impl");
      assertTrue("expect an instance of AbstractExoCache", cache instanceof AbstractExoCache);
      AbstractExoCache aCache = (AbstractExoCache)cache;
      aCache.cache.stop();
      cache = service_.getCacheInstance("test-custom-impl-with-old-config");
      assertTrue("expect an instance of TestExoCache", cache instanceof TestExoCache);
      cache = service_.getCacheInstance("test-custom-impl-with-new-config");
      assertTrue("expect an instance of TestExoCache", cache instanceof TestExoCache);
   }

   public void testSameCacheManager()
   {
      ExoCache cache1 = service_.getCacheInstance("myCustomCache");
      assertTrue("expect an instance of AbstractExoCache", cache1 instanceof AbstractExoCache);
      AbstractExoCache aCache1 = (AbstractExoCache)cache1;
      CacheContainer cacheContainer1 = aCache1.cache.getCacheManager();

      ExoCache cache2 = service_.getCacheInstance("myCustomCache-Bis");
      assertTrue("expect an instance of AbstractExoCache", cache2 instanceof AbstractExoCache);
      AbstractExoCache aCache2 = (AbstractExoCache)cache2;
      CacheContainer cacheContainer2 = aCache2.cache.getCacheManager();
      assertTrue("The CacheContainer should be the same", cacheContainer1 == cacheContainer2);

      ExoCache cache3 = service_.getCacheInstance("myCustomCache-Bis2");
      assertTrue("expect an instance of AbstractExoCache", cache3 instanceof AbstractExoCache);
      AbstractExoCache aCache3 = (AbstractExoCache)cache3;
      CacheContainer cacheContainer3 = aCache3.cache.getCacheManager();
      assertTrue("The CacheContainer should be the same", cacheContainer1 == cacheContainer3);

      aCache1.cache.stop();
      aCache2.cache.stop();
   }
}
