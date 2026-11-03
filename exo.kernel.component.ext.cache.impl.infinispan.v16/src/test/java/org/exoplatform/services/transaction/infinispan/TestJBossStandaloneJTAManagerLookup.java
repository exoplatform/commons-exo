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
package org.exoplatform.services.transaction.infinispan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.Cache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.Test;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionManager;

/**
 * Runs the standalone transaction manager the platform's cache templates look up.
 */
public class TestJBossStandaloneJTAManagerLookup
{
   private static final String LOCAL_TEMPLATE = "conf/cache/infinispan/local/cache-config.xml";

   @Test
   public void testTransactionManagerCompletesATransaction() throws Exception
   {
      TransactionManager tm = new JBossStandaloneJTAManagerLookup().getTransactionManager();
      AtomicInteger completion = new AtomicInteger(-1);
      tm.begin();
      tm.getTransaction().registerSynchronization(new Synchronization()
      {
         public void beforeCompletion()
         {
            // Nothing to do
         }

         public void afterCompletion(int status)
         {
            completion.set(status);
         }
      });
      tm.commit();
      assertEquals(Status.STATUS_COMMITTED, completion.get());
   }

   @Test
   public void testCacheOfTheLocalTemplateIsTransactional() throws Exception
   {
      ConfigurationBuilderHolder holder;
      try (InputStream template = getClass().getClassLoader().getResourceAsStream(LOCAL_TEMPLATE))
      {
         holder = new ParserRegistry(getClass().getClassLoader()).parse(template, MediaType.APPLICATION_XML);
      }
      holder.getGlobalConfigurationBuilder().jmx().enabled(false);
      try (DefaultCacheManager manager = new DefaultCacheManager(holder, true))
      {
         Cache<String, String> cache = manager.getCache();
         TransactionManager tm = cache.getAdvancedCache().getTransactionManager();
         tm.begin();
         cache.put("rolledBack", "value");
         tm.rollback();
         tm.begin();
         cache.put("committed", "value");
         tm.commit();
         assertNull(cache.get("rolledBack"));
         assertEquals("value", cache.get("committed"));
      }
   }
}
