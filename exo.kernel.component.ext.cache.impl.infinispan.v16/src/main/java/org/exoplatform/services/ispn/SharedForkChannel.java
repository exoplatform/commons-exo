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

import java.util.concurrent.atomic.AtomicBoolean;

import org.jgroups.JChannel;
import org.jgroups.fork.ForkChannel;
import org.jgroups.stack.AddressGenerator;

/**
 * A channel of a stack, forked from the main channel of the node, which it releases once when it closes.
 */
final class SharedForkChannel extends ForkChannel
{
   private final MainChannel   main;

   private final AtomicBoolean released = new AtomicBoolean();

   SharedForkChannel(MainChannel main, String forkChannelId) throws Exception
   {
      super(main.getChannel(), main.getForkStackId(), forkChannelId);
      this.main = main;
   }

   /**
    * The main channel carries the node name.
    */
   @Override
   public ForkChannel setName(String name)
   {
      return this;
   }

   /**
    * The main channel carries the node name.
    */
   @Override
   public JChannel name(String name)
   {
      return this;
   }

   /**
    * The main channel generates the addresses its forks share, as Infinispan's transport does.
    */
   @Override
   public ForkChannel addAddressGenerator(AddressGenerator generator)
   {
      return this;
   }

   @Override
   public void close()
   {
      try
      {
         super.close();
      }
      finally
      {
         if (released.compareAndSet(false, true))
         {
            SharedJGroupsTransport.release(main);
         }
      }
   }
}
