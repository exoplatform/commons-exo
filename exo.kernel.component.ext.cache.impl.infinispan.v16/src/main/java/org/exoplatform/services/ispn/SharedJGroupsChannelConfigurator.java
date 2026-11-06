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

import java.util.List;

import javax.sql.DataSource;

import org.infinispan.remoting.transport.jgroups.JGroupsChannelConfigurator;
import org.jgroups.JChannel;
import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.util.SocketFactory;

/**
 * Gives Infinispan's transport a channel on the shared transport of a JGroups stack whose transport has a singleton
 * name ({@link SharedJGroupsTransport}).
 */
public class SharedJGroupsChannelConfigurator implements JGroupsChannelConfigurator
{
   private final ProtocolStackConfigurator stack;

   private final String nodeName;

   /**
    * @param stack a JGroups stack whose transport has a singleton name
    * @param nodeName the name of the node, given to the main channel; <code>null</code> to let JGroups generate one
    */
   public SharedJGroupsChannelConfigurator(ProtocolStackConfigurator stack, String nodeName)
   {
      this.stack = stack;
      this.nodeName = nodeName;
   }

   @Override
   public String getName()
   {
      return SharedJGroupsTransport.getSingletonName(stack);
   }

   @Override
   public JChannel createChannel(String clusterName) throws Exception
   {
      return SharedJGroupsTransport.createChannel(stack, clusterName, nodeName);
   }

   /**
    * The main channel is shared, so it keeps the socket factory of the stack.
    */
   @Override
   public void setSocketFactory(SocketFactory socketFactory)
   {
      // Nothing to do
   }

   /**
    * The stacks shipped with the platform use no data source.
    */
   @Override
   public void setDataSource(DataSource dataSource)
   {
      // Nothing to do
   }

   @Override
   public String getProtocolStackString()
   {
      return stack.getProtocolStackString();
   }

   @Override
   public List<ProtocolConfiguration> getProtocolStack()
   {
      return stack.getProtocolStack();
   }
}
