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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.protocols.FORK;

/**
 * The stack of a main channel: the shared stack without the singleton name JGroups 5 no longer knows, with FORK on top
 * unless the stack already has it.
 */
final class MainStack implements ProtocolStackConfigurator
{
   private final List<ProtocolConfiguration> protocols = new ArrayList<>();

   MainStack(ProtocolStackConfigurator stack)
   {
      boolean hasFork = false;
      for (ProtocolConfiguration protocol : stack.getProtocolStack())
      {
         Map<String, String> properties = new HashMap<>(protocol.getProperties());
         properties.remove(SharedJGroupsTransport.SINGLETON_NAME);
         protocols.add(new ProtocolConfiguration(protocol.getProtocolName(), properties));
         hasFork |= isFork(protocol.getProtocolName());
      }
      if (!hasFork)
      {
         protocols.add(new ProtocolConfiguration(FORK.class.getSimpleName(), new HashMap<>()));
      }
   }

   private static boolean isFork(String protocolName)
   {
      return FORK.class.getSimpleName().equals(protocolName) || FORK.class.getName().equals(protocolName);
   }

   @Override
   public String getProtocolStackString()
   {
      return protocols.toString();
   }

   @Override
   public List<ProtocolConfiguration> getProtocolStack()
   {
      return protocols;
   }
}
