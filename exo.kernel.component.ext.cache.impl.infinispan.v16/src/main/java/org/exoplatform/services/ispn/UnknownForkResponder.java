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

import org.jgroups.EmptyMessage;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.blocks.RequestCorrelator;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.fork.UnknownForkHandler;
import org.jgroups.protocols.FORK;

/**
 * Answers a request sent to a fork this member does not have with an empty response, which Infinispan reads as a cache
 * that is not running here.
 */
final class UnknownForkResponder implements UnknownForkHandler
{
   private static final short CORRELATOR_HEADER_ID = ClassConfigurator.getProtocolId(RequestCorrelator.class);

   private final JChannel     channel;

   UnknownForkResponder(JChannel channel)
   {
      this.channel = channel;
   }

   @Override
   public Object handleUnknownForkStack(Message message, String forkStackId)
   {
      return respond(message);
   }

   @Override
   public Object handleUnknownForkChannel(Message message, String forkChannelId)
   {
      return respond(message);
   }

   private Object respond(Message message)
   {
      RequestCorrelator.Header header = message.getHeader(CORRELATOR_HEADER_ID);
      if (header != null && header.type == RequestCorrelator.Header.REQ && header.rspExpected())
      {
         Message response = new EmptyMessage(message.getSrc()).setFlag(message.getFlags(), false)
            .clearFlag(Message.Flag.RSVP);
         response.putHeader(FORK.ID, message.getHeader(FORK.ID));
         response.putHeader(CORRELATOR_HEADER_ID,
            new RequestCorrelator.Header(RequestCorrelator.Header.RSP, header.req_id, header.corrId));
         channel.down(response);
      }
      return null;
   }
}
