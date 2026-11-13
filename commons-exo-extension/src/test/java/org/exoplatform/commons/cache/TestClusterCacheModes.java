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

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * In a cluster, a cache of the data a shared store holds is invalidated: replication is kept for the state no store
 * holds, which the other nodes could not load. A cache of what each node loads from its own deployment (templates,
 * resource bundles) stays local: every node holds the same content, and no write on one node changes another's.
 */
public class TestClusterCacheModes
{
   private static final String CONFIGURATION = "src/main/webapp/WEB-INF/conf/commons-exo/cache-configuration.xml";

   @Test
   public void testOnlyTheStateNoStoreHoldsIsReplicated() throws Exception
   {
      Map<String, String> modes = getClusterModes();
      Set<String> replicated = new TreeSet<>();
      modes.forEach((name, mode) -> {
         if (mode.toLowerCase().endsWith("replication"))
         {
            replicated.add(name);
         }
      });

      assertEquals(Set.of("commons.UserStateService", "portal.coediting"), replicated);
      assertEquals("asyncInvalidation", modes.get("social.ActivitiesCache"));
      assertEquals("asyncInvalidation", modes.get("commons.SettingService"));
   }

   @Test
   public void testTheSitesANodeBuiltAreInvalidatedAndNeverSent() throws Exception
   {
      // A built site holds the node's UI tree: only its invalidation travels
      assertEquals("asyncInvalidation", getClusterModes().get("portal.UIPortal"));
   }

   @Test
   public void testTheCachesOfDeployedResourcesStayLocal() throws Exception
   {
      Map<String, String> modes = getClusterModes();

      assertEquals("local", modes.get("portal.TemplateService"));
      assertEquals("local", modes.get("portal.ResourceBundleData"));
      assertEquals("local", modes.get("ecms.BaseResourceCache"));
   }

   @Test
   public void testTheSettingsCachesOfTheWalletAndThePerkStoreAreInvalidated() throws Exception
   {
      // An undeclared cache takes the cluster template in local mode
      Map<String, String> modes = getClusterModes();

      assertEquals("asyncInvalidation", modes.get("wallet.rewardSettings"));
      assertEquals("asyncInvalidation", modes.get("wallet.addressLabels"));
      assertEquals("asyncInvalidation", modes.get("perkstore.globalSettings"));
      // Each applies where its add-on runs
      assertEquals("wallet", getDeclarationProfiles("wallet.rewardSettings"));
      assertEquals("wallet", getDeclarationProfiles("wallet.addressLabels"));
      assertEquals("", getDeclarationProfiles("perkstore.globalSettings"));
   }

   @Test
   public void testTheRewardingAdministratorsCacheIsInvalidated() throws Exception
   {
      assertEquals("asyncInvalidation", getClusterModes().get("gamification.administrators"));
      assertEquals("gamification", getDeclarationProfiles("gamification.administrators"));
   }

   /**
    * @param cacheName the name of a declared cache
    * @return the profiles its declaration applies to, empty for every profile
    */
   private static String getDeclarationProfiles(String cacheName) throws Exception
   {
      File file = new File(System.getProperty("basedir", "."), CONFIGURATION);
      NodeList params = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("object-param");
      for (int i = 0; i < params.getLength(); i++)
      {
         Element param = (Element)params.item(i);
         if (cacheName.equals(param.getElementsByTagName("name").item(0).getTextContent().trim()))
         {
            return param.getAttribute("profiles");
         }
      }
      throw new AssertionError("No declaration of cache " + cacheName);
   }

   /**
    * @return the default mode of each cache in the cluster profile, by cache name
    */
   private static Map<String, String> getClusterModes() throws Exception
   {
      File file = new File(System.getProperty("basedir", "."), CONFIGURATION);
      NodeList fields = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("field");
      Map<String, String> modes = new TreeMap<>();
      for (int i = 0; i < fields.getLength(); i++)
      {
         Element field = (Element)fields.item(i);
         String profiles = field.getAttribute("profiles");
         if ("cacheMode".equals(field.getAttribute("name")) && (profiles.isEmpty() || profiles.contains("cluster")))
         {
            modes.put(getCacheName(field), getDefaultValue(field.getElementsByTagName("string").item(0).getTextContent()));
         }
      }
      return modes;
   }

   private static String getCacheName(Element field)
   {
      Node node = field.getParentNode();
      while (!"object-param".equals(node.getNodeName()))
      {
         node = node.getParentNode();
      }
      return ((Element)node).getElementsByTagName("name").item(0).getTextContent().trim();
   }

   private static String getDefaultValue(String value)
   {
      String trimmed = value.trim();
      int separator = trimmed.indexOf(':');
      return trimmed.startsWith("${") && separator > 0 ? trimmed.substring(separator + 1, trimmed.length() - 1) : trimmed;
   }
}
