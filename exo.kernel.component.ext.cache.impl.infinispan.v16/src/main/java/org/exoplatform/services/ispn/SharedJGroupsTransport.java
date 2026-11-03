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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.NodeVersion;
import org.jgroups.EmptyMessage;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.blocks.RequestCorrelator;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.conf.XmlConfigurator;
import org.jgroups.fork.ForkChannel;
import org.jgroups.fork.UnknownForkHandler;
import org.jgroups.protocols.FORK;
import org.jgroups.stack.AddressGenerator;

import org.exoplatform.commons.utils.PropertyManager;

/**
 * Lets the JGroups channels of a node share one transport, as the {@code singleton_name} attribute of a JGroups 3
 * transport did before JGroups 4 removed it: the first channel created for a stack opens a main channel on the
 * stack's transport, and each channel of that stack is a fork of it, named after its cluster.
 * <p>
 * A fork's view is its main channel's view, so a member may lack a fork while it starts or stops. A request sent to
 * such a member is answered with an empty response, which Infinispan reads as a cache that is not running there, rather
 * than left to time out (the pattern WildFly uses for its forks). The main channel builds its addresses as Infinispan's
 * transport does, since the forks share them.
 */
public final class SharedJGroupsTransport
{
   /**
    * The attribute of the transport protocol that makes the channels of a stack share one transport.
    */
   public static final String SINGLETON_NAME = "singleton_name";

   /**
    * The property naming the cluster partition, which qualifies the main channel's cluster name, so that two
    * partitions on the same network never share a main channel.
    */
   public static final String PARTITION_NAME = "exo.cluster.partition.name";

   private static final Map<String, MainChannel> MAIN_CHANNELS = new HashMap<>();

   private SharedJGroupsTransport()
   {
   }

   /**
    * Parses a JGroups stack and resolves its variables.
    *
    * @param xml the JGroups configuration
    * @return the stack it declares
    * @throws IllegalStateException if the configuration cannot be parsed
    */
   public static ProtocolStackConfigurator parse(String xml)
   {
      try
      {
         XmlConfigurator stack = XmlConfigurator.getInstance(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
         for (ProtocolConfiguration protocol : stack.getProtocolStack())
         {
            protocol.substituteVariables();
         }
         return stack;
      }
      catch (Exception e)
      {
         throw new IllegalStateException("The JGroups configuration cannot be parsed", e);
      }
   }

   /**
    * @param stack a JGroups stack
    * @return the singleton name of its transport, or <code>null</code> if its channels do not share it
    */
   public static String getSingletonName(ProtocolStackConfigurator stack)
   {
      List<ProtocolConfiguration> protocols = stack.getProtocolStack();
      if (protocols.isEmpty())
      {
         return null;
      }
      String singletonName = protocols.get(0).getProperties().get(SINGLETON_NAME);
      return singletonName == null || singletonName.isBlank() ? null : singletonName;
   }

   /**
    * Creates a channel on the shared transport of a stack, opening the main channel of the stack if no other channel
    * of this node uses it. The channel is not connected; closing it releases the main channel, which closes with the
    * last of its forks.
    *
    * @param stack a JGroups stack whose transport has a singleton name
    * @param clusterName the cluster the channel joins, which identifies its fork
    * @param nodeName the name of the node, given to the main channel; <code>null</code> to let JGroups generate one
    * @return the channel
    * @throws Exception if the main channel cannot be created or connected
    */
   public static synchronized JChannel createChannel(ProtocolStackConfigurator stack, String clusterName,
      String nodeName) throws Exception
   {
      // JGroups reads its deserialization filter once, which the list sets
      ClusterAllowList.getInstance();
      String singletonName = getSingletonName(stack);
      if (singletonName == null)
      {
         throw new IllegalArgumentException("The transport of the JGroups stack has no '" + SINGLETON_NAME + "'");
      }
      String mainClusterName = getMainClusterName(singletonName);
      String key = mainClusterName + '\n' + stack.getProtocolStackString();
      MainChannel main = MAIN_CHANNELS.get(key);
      if (main == null)
      {
         main = new MainChannel(key, stack, singletonName, mainClusterName, nodeName);
         MAIN_CHANNELS.put(key, main);
      }
      return main.fork(clusterName);
   }

   static String getMainClusterName(String singletonName)
   {
      String partition = PropertyManager.getProperty(PARTITION_NAME);
      return partition == null || partition.isBlank() ? singletonName : partition + "-" + singletonName;
   }

   static synchronized int getMainChannelCount()
   {
      return MAIN_CHANNELS.size();
   }

   private static synchronized void release(MainChannel main)
   {
      if (--main.forks == 0)
      {
         MAIN_CHANNELS.remove(main.key);
         main.channel.close();
      }
   }

   private static final class MainChannel
   {
      private final String key;

      private final String forkStackId;

      private final JChannel channel;

      private int forks;

      private MainChannel(String key, ProtocolStackConfigurator stack, String singletonName, String clusterName,
         String nodeName) throws Exception
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

      private JChannel fork(String clusterName) throws Exception
      {
         JChannel fork;
         try
         {
            fork = new SharedForkChannel(this, clusterName);
         }
         catch (Exception e) //NOSONAR
         {
            if (forks == 0)
            {
               MAIN_CHANNELS.remove(key);
               channel.close();
            }
            throw e;
         }
         forks++;
         return fork;
      }
   }

   /**
    * The stack of a main channel: the shared stack without the singleton name JGroups 5 no longer knows, with FORK on
    * top unless the stack already has it.
    */
   private static final class MainStack implements ProtocolStackConfigurator
   {
      private final List<ProtocolConfiguration> protocols = new ArrayList<>();

      private MainStack(ProtocolStackConfigurator stack)
      {
         boolean hasFork = false;
         for (ProtocolConfiguration protocol : stack.getProtocolStack())
         {
            Map<String, String> properties = new HashMap<>(protocol.getProperties());
            properties.remove(SINGLETON_NAME);
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

   private static final class SharedForkChannel extends ForkChannel
   {
      private final MainChannel main;

      private boolean released;

      private SharedForkChannel(MainChannel main, String forkChannelId) throws Exception
      {
         super(main.channel, main.forkStackId, forkChannelId);
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
            synchronized (this)
            {
               if (!released)
               {
                  released = true;
                  release(main);
               }
            }
         }
      }
   }

   /**
    * Generates addresses as Infinispan's transport does, which reads the node version from them.
    */
   private static final class InfinispanAddressGenerator implements AddressGenerator
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

   /**
    * Answers a request sent to a fork this member does not have with an empty response, which Infinispan reads as a
    * cache that is not running here.
    */
   private static final class UnknownForkResponder implements UnknownForkHandler
   {
      private static final short CORRELATOR_HEADER_ID = ClassConfigurator.getProtocolId(RequestCorrelator.class);

      private final JChannel channel;

      private UnknownForkResponder(JChannel channel)
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
}
