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
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ObjectParameter;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheConfigPlugin;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.ExoCacheInitException;
import org.exoplatform.services.cache.impl.CacheServiceImpl;
import org.exoplatform.services.cache.impl.infinispan.TestExoCacheCreator.TestExoCache;
import org.exoplatform.services.cache.invalidation.AsyncInvalidationExoCache;
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

   public void testACacheConfiguredByItsModeOnlyIsInvalidatedOverAnAsyncReplicatedCache() throws Exception
   {
      PortalContainer container = PortalContainer.getInstance();
      ExoCacheFactoryImpl factory =
         new ExoCacheFactoryImpl(container.getComponentInstanceOfType(ExoContainerContext.class),
                                 "jar:/conf/portal/cache-configuration-template.xml",
                                 "jar:/conf/portal/cache-async-configuration-template.xml",
                                 container.getComponentInstanceOfType(ConfigurationManager.class));
      try
      {
         CacheServiceImpl cacheService = new CacheServiceImpl(new InitParams(), factory);
         // What the Spring cache manager adapter declares for a cache in cluster mode: no type, no implementation
         ExoCacheConfig config = new ExoCacheConfig();
         config.setName("springCache");
         config.setCacheMode(org.exoplatform.services.cache.CacheMode.ASYNCINVALIDATION);
         config.setMaxSize(300);
         config.setLiveTime(600);
         ObjectParameter parameter = new ObjectParameter();
         parameter.setName(config.getName());
         parameter.setObject(config);
         InitParams params = new InitParams();
         params.addParameter(parameter);
         cacheService.addExoCacheConfig(new ExoCacheConfigPlugin(params));

         ExoCache<?, ?> cache = cacheService.getCacheInstance("springCache");

         assertTrue(cache.getClass().getName(), cache instanceof AsyncInvalidationExoCache);
         Field field = AsyncInvalidationExoCache.class.getDeclaredField("replicatedCache");
         field.setAccessible(true);
         AbstractExoCache<?, ?> replicated = (AbstractExoCache<?, ?>)field.get(cache);
         assertEquals(CacheMode.REPL_ASYNC, replicated.cache.getCacheConfiguration().clustering().cacheMode());
         assertEquals(300, cache.getMaxSize());
         assertEquals(600, cache.getLiveTime());
      }
      finally
      {
         factory.stop();
      }
   }

   public void testCustomCacheManagersStartAndStopWithTheFactory() throws Exception
   {
      PortalContainer container = PortalContainer.getInstance();
      String template = "jar:/conf/cache/infinispan/local/cache-config.xml";
      ExoCacheFactoryImpl factory =
         new ExoCacheFactoryImpl(container.getComponentInstanceOfType(ExoContainerContext.class), template, null,
                                 container.getComponentInstanceOfType(ConfigurationManager.class));
      ValueParam config = new ValueParam();
      config.setName("eagerCustomCache");
      config.setValue("jar:/conf/portal/custom-cache-configuration-template.xml");
      InitParams params = new InitParams();
      params.addParameter(config);
      factory.addConfig(new ExoCacheFactoryConfigPlugin(params));
      Field field = ExoCacheFactoryImpl.class.getDeclaredField("mappingGlobalConfigCacheManager");
      field.setAccessible(true);
      Map<String, DefaultCacheManager> cacheManagers = (Map<String, DefaultCacheManager>)field.get(factory);

      factory.start();
      DefaultCacheManager customCacheManager = cacheManagers.values()
                                                            .stream()
                                                            .filter(manager -> manager.getName()
                                                                                      .contains("_eagerCustomCache_"))
                                                            .findFirst()
                                                            .orElseThrow();
      assertEquals(ComponentStatus.RUNNING, customCacheManager.getStatus());
      factory.stop();
      assertEquals(ComponentStatus.TERMINATED, customCacheManager.getStatus());
   }

   public void testMainCacheManagerStopsWhenTheAsyncOneFails() throws Exception
   {
      PortalContainer container = PortalContainer.getInstance();
      // The server the factory registers its cache managers in
      MBeanServer mbeanServer = ExoContainerContext.getTopContainer().getMBeanServer();
      ObjectName cacheManagers = new ObjectName("*:type=CacheManager,name=\"template_portal\",component=CacheManager");
      int registered = mbeanServer.queryNames(cacheManagers, null).size();
      String template = "jar:/conf/cache/infinispan/local/cache-config.xml";
      ExoCacheFactoryImpl factory =
         new ExoCacheFactoryImpl(container.getComponentInstanceOfType(ExoContainerContext.class), template, null,
                                 container.getComponentInstanceOfType(ConfigurationManager.class));
      assertEquals(registered + 1, mbeanServer.queryNames(cacheManagers, null).size());
      factory.stop();
      assertEquals(registered, mbeanServer.queryNames(cacheManagers, null).size());
      try
      {
         new ExoCacheFactoryImpl(container.getComponentInstanceOfType(ExoContainerContext.class),
                                 template, "jar:/conf/missing.xml",
                                 container.getComponentInstanceOfType(ConfigurationManager.class));
         fail("The asynchronous template does not exist");
      }
      catch (ExoCacheInitException e)
      {
         assertEquals(registered, mbeanServer.queryNames(cacheManagers, null).size());
      }
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
