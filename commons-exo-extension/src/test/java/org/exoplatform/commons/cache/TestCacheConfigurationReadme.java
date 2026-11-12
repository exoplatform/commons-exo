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
package org.exoplatform.commons.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The repository's README lists every cache the extension declares, with the property that sets each value and its
 * default, as the configuration file states them. The table is generated from that file: run this test with
 * {@code -Dexo.cache.readme.update=true} to write it, and the build fails when the file changes without it.
 */
public class TestCacheConfigurationReadme
{
   private static final String CONFIGURATION = "src/main/webapp/WEB-INF/conf/commons-exo/cache-configuration.xml";

   private static final String README = "../README.md";

   private static final String START = "<!-- caches:start -->";

   private static final String END = "<!-- caches:end -->";

   @Test
   public void testTheReadmeListsTheCachesAsConfigured() throws Exception
   {
      File basedir = new File(System.getProperty("basedir", "."));
      Path readme = new File(basedir, README).toPath();
      String content = Files.readString(readme, StandardCharsets.UTF_8);
      int start = content.indexOf(START);
      int end = content.indexOf(END);
      assertTrue("The README has no " + START + " ... " + END + " block", start >= 0 && end > start);

      String table = getTable(new File(basedir, CONFIGURATION));
      String updated = content.substring(0, start + START.length()) + "\n" + table + content.substring(end);
      if (Boolean.getBoolean("exo.cache.readme.update"))
      {
         Files.writeString(readme, updated, StandardCharsets.UTF_8);
      }
      assertEquals("The README's cache table differs from " + CONFIGURATION
         + ": run this test with -Dexo.cache.readme.update=true and commit the README", updated, Files.readString(readme,
         StandardCharsets.UTF_8));
   }

   private static String getTable(File configuration) throws Exception
   {
      StringBuilder table = new StringBuilder();
      table.append("| Cache | Profile | Max entries | Live time (s) | Cluster mode | Eviction |\n");
      table.append("|---|---|---|---|---|---|\n");
      NodeList params = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configuration)
         .getElementsByTagName("object-param");
      for (int i = 0; i < params.getLength(); i++)
      {
         Element param = (Element)params.item(i);
         Element object = (Element)param.getElementsByTagName("object").item(0);
         if (object == null || !object.getAttribute("type").endsWith("ExoCacheConfig"))
         {
            continue;
         }
         table.append("| `")
            .append(getField(object, "name"))
            .append("` | ")
            .append(param.getAttribute("profiles"))
            .append(" | ")
            .append(getCell(object, "maxSize"))
            .append(" | ")
            .append(getCell(object, "liveTime"))
            .append(" | ")
            .append(getCell(object, "cacheMode"))
            .append(" | ")
            .append(getCell(object, "strategy"))
            .append(" |\n");
      }
      return table.toString();
   }

   /**
    * @return the default value then, when a property sets it, the property's name
    */
   private static String getCell(Element object, String name)
   {
      String value = getField(object, name);
      if (value.isEmpty())
      {
         return "";
      }
      int separator = value.indexOf(':');
      if (!value.startsWith("${") || separator < 0)
      {
         return value;
      }
      return value.substring(separator + 1, value.length() - 1) + " (`" + value.substring(2, separator) + "`)";
   }

   private static String getField(Element object, String name)
   {
      NodeList fields = object.getElementsByTagName("field");
      for (int i = 0; i < fields.getLength(); i++)
      {
         Element field = (Element)fields.item(i);
         String profiles = field.getAttribute("profiles");
         if (name.equals(field.getAttribute("name")) && (profiles.isEmpty() || profiles.contains("cluster")))
         {
            return field.getTextContent().trim();
         }
      }
      return "";
   }
}
