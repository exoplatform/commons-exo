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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.exoplatform.services.ispn.TestClusterAllowList.PlatformState;
import org.exoplatform.services.ispn.TestClusterAllowList.PlatformValue;
import org.gatein.cluster.GateInValue;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.cache.interceptor.SimpleKey;

import io.meeds.cluster.MeedsValue;

/**
 * The values of the cluster allow-list, put on a replicated cache of one node and read on the other, with the platform
 * marshaller. Infinispan marshals by itself, around the platform marshaller, the values its own marshalling knows,
 * among which the collections whose first element it knows: such a collection arrives as the class it rebuilds, an
 * immutable one as a mutable one, and one of a class it has no marshaller for is refused.
 */
public class TestClusterCacheValues
{
   private static final String PROPERTY_PREFIX = "exo.service.cluster.jgroups.";

   private static DefaultCacheManager node1;

   private static DefaultCacheManager node2;

   private static Cache<Object, Object> cache1;

   private static Cache<Object, Object> cache2;

   @BeforeClass
   public static void startNodes() throws Exception
   {
      int[] ports = TestSharedJGroupsTransport.freePorts(2);
      node1 = startNode(TestSharedJGroupsTransport.stack(ports[0], ports), "node1");
      node2 = startNode(TestSharedJGroupsTransport.stack(ports[1], ports), "node2");
      cache1 = node1.getCache("replicated");
      cache2 = node2.getCache("replicated");
      TestSharedJGroupsTransport.waitForMembers(cache1, 2);
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

   @Test
   public void testValuesArriveWithTheirClass() throws Exception
   {
      Map<String, String> map = new HashMap<>(Map.of("a", "b"));
      List<Object> values = List.of(1L,
                                    1,
                                    (short)1,
                                    (byte)1,
                                    2.0f,
                                    3.0d,
                                    true,
                                    'c',
                                    "text",
                                    new BigDecimal("1.5"),
                                    new int[] {3},
                                    new String[] {"a", "b"},
                                    new Date(1000),
                                    new GregorianCalendar(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE),
                                    Instant.ofEpochSecond(1000),
                                    LocalDate.of(2026, 11, 3),
                                    LocalDateTime.of(2026, 11, 3, 12, 0),
                                    ZonedDateTime.of(2026, 11, 3, 12, 0, 0, 0, ZoneId.of("Europe/Paris")),
                                    Duration.ofSeconds(30),
                                    ZoneOffset.ofHours(1),
                                    URI.create("https://example.org/explorer"),
                                    Locale.FRANCE,
                                    UUID.randomUUID(),
                                    new ArrayList<>(List.of("a")),
                                    new LinkedList<>(List.of("a")),
                                    new HashSet<>(Set.of("a")),
                                    new LinkedHashSet<>(Set.of("a")),
                                    new TreeSet<>(Set.of("a")),
                                    map,
                                    new LinkedHashMap<>(map),
                                    new TreeMap<>(map),
                                    new ConcurrentHashMap<>(map),
                                    Collections.synchronizedMap(new HashMap<>(map)),
                                    new CopyOnWriteArrayList<>(List.of(platformValue())),
                                    new CopyOnWriteArraySet<>(Set.of(platformValue())),
                                    new ArrayList<>(List.of(platformValue())),
                                    new HashMap<>(Map.of("a", platformValue())),
                                    // The parameter of the filter of web notifications, a key of their list cache
                                    ImmutablePair.of("name", "value"),
                                    platformValue(),
                                    new MeedsValue("name"),
                                    new GateInValue("name"));
      for (Object value : values)
      {
         Object copy = replicate(value);
         assertTrue(value.getClass().getName(), Arrays.deepEquals(new Object[] {value}, new Object[] {copy}));
         assertEquals(value.getClass(), copy.getClass());
      }
   }

   @Test
   public void testASpringCacheKeyFindsItsEntryOnTheOtherNode()
   {
      // The keys a Spring cache builds from the arguments of a method, each built again by the reading node
      Map<SimpleKey, SimpleKey> rebuiltKeys = Map.of(new SimpleKey("name", 1L), new SimpleKey("name", 1L),
                                                     SimpleKey.EMPTY, new SimpleKey());
      for (Map.Entry<SimpleKey, SimpleKey> keys : rebuiltKeys.entrySet())
      {
         cache1.put(keys.getKey(), "value");
         assertEquals(keys.getKey().toString(), "value", cache2.get(keys.getValue()));
         cache2.remove(keys.getValue());
         assertNull(keys.getKey().toString(), cache1.get(keys.getKey()));
      }
   }

   @Test
   public void testImmutableCollectionsArriveMutable() throws Exception
   {
      Map<Object, Class<?>> arrivals = new LinkedHashMap<>();
      arrivals.put(List.of("a", "b"), ArrayList.class);
      arrivals.put(Set.of("a"), HashSet.class);
      arrivals.put(Map.of("a", "b"), HashMap.class);
      arrivals.put(Arrays.asList("a", "b"), ArrayList.class);
      arrivals.put(Collections.emptyList(), ArrayList.class);
      arrivals.put(Collections.singletonMap("a", "b"), HashMap.class);
      arrivals.put(Collections.unmodifiableList(new ArrayList<>(List.of("a"))), ArrayList.class);
      for (Map.Entry<Object, Class<?>> arrival : arrivals.entrySet())
      {
         Object copy = replicate(arrival.getKey());
         assertEquals(arrival.getKey(), copy);
         assertEquals(arrival.getKey().getClass().getName(), arrival.getValue(), copy.getClass());
      }
   }

   @Test
   public void testCollectionOfAClassInfinispanCannotRebuildIsRefused()
   {
      for (Object value : List.of(new CopyOnWriteArrayList<>(List.of("a")),
                                  new CopyOnWriteArraySet<>(Set.of("a")),
                                  new ArrayList<>(List.of("a", platformValue()))))
      {
         Object key = UUID.randomUUID().toString();
         Exception refusal = assertThrows(value.toString(), Exception.class, () -> cache1.put(key, value));
         assertTrue(String.valueOf(refusal), String.valueOf(refusal).contains("No marshaller registered"));
         assertNull(cache1.get(key));
         assertNull(cache2.get(key));
      }
   }

   private static Object replicate(Object value)
   {
      String key = UUID.randomUUID().toString();
      cache1.put(key, value);
      return cache2.get(key);
   }

   private static PlatformValue platformValue()
   {
      return new PlatformValue("name", PlatformState.ACTIVE, List.of("a"));
   }

   private static DefaultCacheManager startNode(ProtocolStackConfigurator stack, String nodeName) throws Exception
   {
      ConfigurationBuilderHolder holder = new ConfigurationBuilderHolder();
      GlobalConfigurationBuilder global = holder.getGlobalConfigurationBuilder();
      global.cacheManagerName(nodeName).jmx().enabled(false);
      global.transport()
            .defaultTransport()
            .clusterName("values-test")
            .nodeName(nodeName)
            .addProperty(JGroupsTransport.CHANNEL_CONFIGURATOR, new SharedJGroupsChannelConfigurator(stack, nodeName));
      Utils.configureSerialization(global, "test");
      holder.newConfigurationBuilder("replicated").clustering().cacheMode(CacheMode.REPL_SYNC);
      return new DefaultCacheManager(holder, true);
   }
}
