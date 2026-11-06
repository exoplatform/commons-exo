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

import org.jgroups.JChannel;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.protocols.FORK;

/**
 * The channel a node opens on the shared transport of a stack, whose forks are the channels of the stack. Its forks are
 * counted under the lock of {@link SharedJGroupsTransport}.
 */
final class MainChannel
{
   private final String key;

   private final String forkStackId;

   private final JChannel channel;

   private int forks;

   MainChannel(String key, ProtocolStackConfigurator stack, String singletonName, String clusterName, String nodeName)
      throws Exception
   {
      this.key = key;
      this.forkStackId = singletonName;
      this.channel = new JChannel(new MainStack(stack));
      try
      {
         FORK fork = channel.getProtocolStack().findProtocol(FORK.class);
         fork.setUnknownForkHandler(new UnknownForkResponder(channel));
         channel.addAddressGenerator(new InfinispanAddressGenerator());
         if (nodeName != null && !nodeName.isEmpty())
         {
            channel.setName(nodeName);
         }
         channel.connect(clusterName);
      }
      catch (Exception e) //NOSONAR
      {
         channel.close();
         throw e;
      }
   }

   /**
    * @return a new fork of the channel, named after the cluster it joins
    */
   JChannel fork(String clusterName) throws Exception
   {
      JChannel fork = new SharedForkChannel(this, clusterName);
      forks++;
      return fork;
   }

   boolean hasForks()
   {
      return forks > 0;
   }

   /**
    * @return whether the fork released was the last one
    */
   boolean releaseFork()
   {
      return --forks == 0;
   }

   String getKey()
   {
      return key;
   }

   String getForkStackId()
   {
      return forkStackId;
   }

   JChannel getChannel()
   {
      return channel;
   }

   void close()
   {
      channel.close();
   }
}
