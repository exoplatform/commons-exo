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

import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.NodeVersion;
import org.jgroups.stack.AddressGenerator;

/**
 * Generates addresses as Infinispan's transport does, which reads the node version from them.
 */
final class InfinispanAddressGenerator implements AddressGenerator
{
   @Override
   public org.jgroups.Address generateAddress()
   {
      return generateAddress(null);
   }

   @Override
   public org.jgroups.Address generateAddress(String name)
   {
      return Address.randomUUID(name, NodeVersion.INSTANCE, null, null, null);
   }
}
