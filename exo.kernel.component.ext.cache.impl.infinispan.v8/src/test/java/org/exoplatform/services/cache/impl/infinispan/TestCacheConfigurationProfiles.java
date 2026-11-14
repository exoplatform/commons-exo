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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import org.exoplatform.container.configuration.ConfigurationUnmarshaller;
import org.exoplatform.container.xml.Component;
import org.exoplatform.container.xml.Deserializer;
import org.exoplatform.container.xml.Property;
import org.exoplatform.services.cache.CacheMode;

/**
 * The cache configuration the extension ships, read with every profile it names, the cluster one included: an object
 * that cannot be built makes the container drop the whole file, and every cache it configures runs on the defaults.
 */
public class TestCacheConfigurationProfiles
{
   private static final Path CONFIGURATION = Path.of("..", "commons-exo-extension", "src", "main", "webapp", "WEB-INF", "conf");

   private static final Path EXTENSION_CONFIGURATION =
      Path.of("..", "commons-exo-extension", "src", "main", "webapp", "META-INF", "exo-conf", "configuration.xml");

   private static final Pattern PROFILES = Pattern.compile("profiles=\"([^\"]+)\"");

   private static final Path   COMMONS_EXO_CACHES = CONFIGURATION.resolve(Path.of("commons-exo", "cache-configuration.xml"));

   /**
    * Caches holding data a shared store holds, which the cluster fixes evict on one node and expect evicted on every
    * node: replicated, or left local, they serve stale entries on the other nodes.
    */
   private static final List<String> INVALIDATED_CACHES = List.of("portal.User", "portal.Profile", "portal.Membership",
      "portal.Group", "portal.Role", "portal.UIPortal", "portal.tokens", "portal.IdentityRegistry",
      "commons.SettingService", "commons.WebNotificationCache", "commons.WebNotificationsCache", "social.translation",
      "gamification.administrators", "gamification.connectors", "gamification.domain", "gamification.rule",
      "gamification.realization", "perkstore.globalSettings", "perkstore.product", "perkstore.order",
      "wallet.rewardSettings", "wallet.addressLabels");

   /**
    * Caches holding state no store holds, which every node must read: the co-editing locks.
    */
   private static final List<String> REPLICATED_CACHES = List.of("portal.coediting");

   private static final Pattern CACHE_MODE = Pattern.compile("name=\"cacheMode\"[^>]*>\\s*<string>\\$\\{([^:}]+):([^}]*)\\}</string>");

   @Test
   public void testCacheConfigurationIsReadWithEveryProfile() throws Exception
   {
      List<Path> files;
      try (Stream<Path> paths = Files.walk(CONFIGURATION))
      {
         files = paths.filter(path -> path.getFileName().toString().startsWith("cache-configuration")).toList();
      }
      assertFalse("No cache configuration under " + CONFIGURATION.toAbsolutePath(), files.isEmpty());
      for (Path file : files)
      {
         Set<String> profiles = new HashSet<>(Set.of("cluster"));
         Matcher matcher = PROFILES.matcher(Files.readString(file));
         while (matcher.find())
         {
            for (String profile : matcher.group(1).split(","))
            {
               profiles.add(profile.trim());
            }
         }
         try
         {
            new ConfigurationUnmarshaller(profiles).unmarshall(file.toUri().toURL());
         }
         catch (Exception e)
         {
            throw new AssertionError(file + " cannot be read with the profiles " + profiles, e);
         }
      }
   }

   /**
    * A cache mode the kernel does not know makes the cache run local, silently: every default mode is one it knows.
    */
   @Test
   public void testEveryCacheModeIsAKnownMode() throws Exception
   {
      for (Path file : cacheConfigurations())
      {
         Matcher matcher = CACHE_MODE.matcher(Files.readString(file));
         while (matcher.find())
         {
            try
            {
               CacheMode.valueOf(matcher.group(2).toUpperCase());
            }
            catch (IllegalArgumentException e)
            {
               fail(file + ": " + matcher.group(1) + " defaults to '" + matcher.group(2) + "', which is not a cache mode");
            }
         }
      }
   }

   private static List<Path> cacheConfigurations() throws Exception
   {
      try (Stream<Path> paths = Files.walk(CONFIGURATION))
      {
         return paths.filter(path -> path.getFileName().toString().startsWith("cache-configuration")).toList();
      }
   }

   @Test
   public void testSharedCachesTakeTheirClusterMode() throws Exception
   {
      String configuration = Files.readString(COMMONS_EXO_CACHES);
      INVALIDATED_CACHES.forEach(cache -> assertClusterMode(configuration, cache, "asyncInvalidation"));
      REPLICATED_CACHES.forEach(cache -> assertClusterMode(configuration, cache, "replication"));
   }

   private static void assertClusterMode(String configuration, String cache, String expectedMode)
   {
      Matcher declaration = Pattern.compile("<object-param[^>]*>\\s*<name>" + Pattern.quote(cache)
         + "</name>(.*?)</object-param>", Pattern.DOTALL).matcher(configuration);
      assertTrue(cache + " is not declared", declaration.find());
      Matcher mode = CACHE_MODE.matcher(declaration.group(1));
      assertTrue(cache + " declares no cache mode", mode.find());
      assertEquals(cache + " cache mode", expectedMode, mode.group(2));
   }

   @Test
   public void testTheIdeSpringCacheIsSizedByItsPropertiesOnly() throws Exception
   {
      assertEquals(Map.of(), getProperties("IdeSpringCacheProperties", Set.of()));
      assertEquals(Map.of("meeds.cache.ide.widget.max", "2000", "meeds.cache.ide.widget.ttl", "-1"),
                   getProperties("IdeSpringCacheProperties", Set.of("ide")));
      // A declaration would be replaced in a cluster by the configuration the properties build
      try (Stream<Path> paths = Files.walk(CONFIGURATION))
      {
         for (Path file : paths.filter(path -> path.getFileName().toString().startsWith("cache-configuration")).toList())
         {
            assertFalse(file.toString(), Files.readString(file).contains("<name>ide.widget</name>"));
         }
      }
   }

   private static Map<String, String> getProperties(String key, Set<String> profiles) throws Exception
   {
      Component component = new ConfigurationUnmarshaller(profiles).unmarshall(EXTENSION_CONFIGURATION.toUri().toURL())
                                                                   .getComponent(key);
      Map<String, String> values = new HashMap<>();
      if (component != null)
      {
         Iterator<Property> properties = component.getInitParams().getPropertiesParam(key).getPropertyIterator();
         while (properties.hasNext())
         {
            Property property = properties.next();
            values.put(property.getName(), Deserializer.resolveString(property.getValue()));
         }
      }
      return values;
   }
}
