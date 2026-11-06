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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.jgroups.JChannel;
import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.conf.XmlConfigurator;

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

   /**
    * Guards the main channels: one is opened, joined and closed while it is held. A thread waiting to open a channel
    * can be interrupted, which a monitor would not allow, so that a stop does not wait for a start blocked in its join.
    */
   static final ReentrantLock LOCK = new ReentrantLock();

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
    * @throws InterruptedException if the thread is interrupted while another one opens or closes a main channel
    * @throws Exception if the main channel cannot be created or connected
    */
   public static JChannel createChannel(ProtocolStackConfigurator stack, String clusterName, String nodeName)
      throws Exception
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
      LOCK.lockInterruptibly();
      try
      {
         MainChannel main = MAIN_CHANNELS.get(key);
         if (main == null)
         {
            main = new MainChannel(key, stack, singletonName, mainClusterName, nodeName);
            MAIN_CHANNELS.put(key, main);
         }
         try
         {
            return main.fork(clusterName);
         }
         catch (Exception e) //NOSONAR
         {
            if (!main.hasForks())
            {
               MAIN_CHANNELS.remove(key);
               main.close();
            }
            throw e;
         }
      }
      finally
      {
         LOCK.unlock();
      }
   }

   static String getMainClusterName(String singletonName)
   {
      String partition = PropertyManager.getProperty(PARTITION_NAME);
      return partition == null || partition.isBlank() ? singletonName : partition + "-" + singletonName;
   }

   static int getMainChannelCount()
   {
      LOCK.lock();
      try
      {
         return MAIN_CHANNELS.size();
      }
      finally
      {
         LOCK.unlock();
      }
   }

   /**
    * Releases a fork of a main channel, which closes with its last fork. A release is never interrupted, so that the
    * count of the forks stays exact.
    */
   static void release(MainChannel main)
   {
      LOCK.lock();
      try
      {
         if (main.releaseFork())
         {
            MAIN_CHANNELS.remove(main.getKey());
            main.close();
         }
      }
      finally
      {
         LOCK.unlock();
      }
   }
}
