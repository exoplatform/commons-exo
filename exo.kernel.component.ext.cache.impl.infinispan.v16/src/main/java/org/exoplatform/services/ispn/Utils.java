/*
 * Copyright (C) 2011 eXo Platform SAS.
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

import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.util.TemplateConfigurationHelper;
import org.exoplatform.services.cache.ExoCacheInitException;
import org.infinispan.configuration.global.AllowListConfiguration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.conf.ProtocolStackConfigurator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class Utils
{

   private Utils()
   {
   }

   /**
    * Configures the serialization of a cache manager: the platform's marshaller, whose allow-list is the only one. An
    * allow-list in the Infinispan configuration would accept classes on the cache path only, so it is refused.
    *
    * @param configBuilder the global configuration of the cache manager
    * @param configurationPath the path of the Infinispan configuration it comes from
    * @throws ExoCacheInitException if the Infinispan configuration has an allow-list, or if the cluster allow-list
    *           cannot be built
    */
   public static void configureSerialization(GlobalConfigurationBuilder configBuilder, String configurationPath)
      throws ExoCacheInitException
   {
      AllowListConfiguration allowList = configBuilder.serialization().allowList().create();
      if (!allowList.getClasses().isEmpty() || !allowList.getRegexps().isEmpty())
      {
         throw new ExoCacheInitException("The Infinispan configuration '" + configurationPath
            + "' has an <allow-list> or <white-list> element, which would accept classes on the cache path only: list"
            + " them in the '" + ClusterAllowList.ALLOW_LIST_PROPERTY + "' property instead");
      }
      try
      {
         configBuilder.serialization().marshaller(new ClusterJavaSerializationMarshaller());
      }
      catch (IllegalStateException e)
      {
         throw new ExoCacheInitException(e.getMessage(), e);
      }
   }

   /**
    * Load the JGroups configuration file thanks to the {@link ConfigurationManager}. When the transport of the stack has
    * a singleton name, the channel is a fork of the channel the node shares for that stack
    * ({@link SharedJGroupsTransport}).
    * @param config the global configuration from which the JGroups config will be extracted
    * @param configBuilder the related configuration builder
    * @return <code>true</code> if the JGoups config could be loaded successfully, 
    * <code>false</code> if there were no JGroups config to load
    * @throws IllegalStateException if the JGroups config could not be loaded
    */
   public static boolean loadJGroupsConfig(ConfigurationManager cfm, GlobalConfiguration config,
      GlobalConfigurationBuilder configBuilder) throws ExoCacheInitException
   {
      Properties properties = config.transport().properties();
      if (properties == null || !properties.containsKey(JGroupsTransport.CONFIGURATION_FILE))
      {
         return false;
      }
      String filename = properties.getProperty(JGroupsTransport.CONFIGURATION_FILE);
      String xml;
      try (InputStream inputStream = TemplateConfigurationHelper.getInputStream(cfm, filename))
      {
         // inputStream still remains null, so file was not opened
         if (inputStream == null)
         {
            throw new IllegalStateException("The jgroups configuration cannot be loaded from '" + filename + "'");
         }
         xml = org.exoplatform.container.util.Utils.readStream(inputStream);
      }
      catch (IOException e)
      {
         throw new IllegalStateException("The jgroups configuration cannot be read from '" + filename + "'", e);
      }
      // Remove the property corresponding to the configuration file
      configBuilder.transport().removeProperty(JGroupsTransport.CONFIGURATION_FILE);
      ProtocolStackConfigurator stack = SharedJGroupsTransport.parse(xml);
      if (SharedJGroupsTransport.getSingletonName(stack) == null)
      {
         // Set the jgroups configuration as XML
         configBuilder.transport().addProperty(JGroupsTransport.CONFIGURATION_XML, xml);
      }
      else
      {
         // JGroups no longer shares a transport between channels: the channels of the stack are forks of one channel
         configBuilder.transport().addProperty(JGroupsTransport.CHANNEL_CONFIGURATOR,
            new SharedJGroupsChannelConfigurator(stack, config.transport().nodeName()));
      }
      return true;
   }
}
