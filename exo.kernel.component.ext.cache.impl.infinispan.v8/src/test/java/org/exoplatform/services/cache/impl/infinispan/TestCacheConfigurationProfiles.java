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

import static org.junit.Assert.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import org.exoplatform.container.configuration.ConfigurationUnmarshaller;

/**
 * The cache configuration the extension ships, read with every profile it names, the cluster one included: an object
 * that cannot be built makes the container drop the whole file, and every cache it configures runs on the defaults.
 */
public class TestCacheConfigurationProfiles
{
   private static final Path CONFIGURATION = Path.of("..", "commons-exo-extension", "src", "main", "webapp", "WEB-INF", "conf");

   private static final Pattern PROFILES = Pattern.compile("profiles=\"([^\"]+)\"");

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
}
