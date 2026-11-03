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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.infinispan.Cache;
import org.infinispan.commons.util.TypedProperties;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.NodeVersion;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.BytesMessage;
import org.jgroups.JChannel;
import org.jgroups.blocks.MessageDispatcher;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.junit.After;
import org.junit.Test;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.configuration.ConfigurationManagerImpl;

/**
 * Two nodes in one JVM, each on the TCP stack the platform ships with its own port: the stacks differ, so each node
 * opens its own main channel.
 */
public class TestSharedJGroupsTransport
{
   private static final String STACK = "/conf/jgroups/jgroups-service-tcp.xml";

   private static final String PROPERTY_PREFIX = "exo.service.cluster.jgroups.";

   private final List<AutoCloseable> resources = new ArrayList<>();

   @After
   public void tearDown() throws Exception
   {
      for (int i = resources.size() - 1; i >= 0; i--)
      {
         resources.get(i).close();
      }
      for (String name : System.getProperties().stringPropertyNames())
      {
         if (name.startsWith(PROPERTY_PREFIX))
         {
            System.clearProperty(name);
         }
      }
   }

   @Test
   public void testForksOfOneStackShareOneMainChannel() throws Exception
   {
      int[] ports = freePorts(1);
      ProtocolStackConfigurator stack = stack(ports[0], ports);
      int mainChannels = SharedJGroupsTransport.getMainChannelCount();

      JChannel first = SharedJGroupsTransport.createChannel(stack, "first", "node1");
      first.connect("first");
      JChannel second = SharedJGroupsTransport.createChannel(stack, "second", "node1");
      second.connect("second");
      assertEquals(mainChannels + 1, SharedJGroupsTransport.getMainChannelCount());
      assertEquals(first.getAddress(), second.getAddress());
      // Infinispan's address, which carries the node version
      assertEquals("node1(v=" + NodeVersion.INSTANCE + ")", first.getAddress().toString());

      first.close();
      assertEquals(mainChannels + 1, SharedJGroupsTransport.getMainChannelCount());
      second.close();
      second.close();
      assertEquals(mainChannels, SharedJGroupsTransport.getMainChannelCount());
   }

   @Test
   public void testTwoNodesReplicateThroughTheirForks() throws Exception
   {
      int[] ports = freePorts(2);
      DefaultCacheManager node1 = startNode(stack(ports[0], ports), "node1");
      DefaultCacheManager node2 = startNode(stack(ports[1], ports), "node2");
      Cache<String, String> cache1 = node1.getCache("replicated");
      Cache<String, String> cache2 = node2.getCache("replicated");
      waitForMembers(cache1, 2);

      cache1.put("key", "value");
      assertEquals("value", cache2.get("key"));
      cache2.remove("key");
      assertNull(cache1.get("key"));
   }

   @Test
   public void testRequestToAMissingForkIsAnswered() throws Exception
   {
      int[] ports = freePorts(2);
      ProtocolStackConfigurator stack1 = stack(ports[0], ports);
      ProtocolStackConfigurator stack2 = stack(ports[1], ports);
      JChannel node1Shared = connect(stack1, "shared", "node1");
      JChannel node1Only = connect(stack1, "node1-only", "node1");
      JChannel node2Shared = connect(stack2, "shared", "node2");
      MessageDispatcher sharedDispatcher1 = dispatcher(node1Shared);
      dispatcher(node2Shared);
      MessageDispatcher onlyDispatcher1 = dispatcher(node1Only);
      waitForMembers(node1Only, 2);

      assertEquals("pong", sharedDispatcher1.sendMessage(ping(node2Shared), RequestOptions.SYNC().timeout(10000)));
      long start = System.currentTimeMillis();
      // node2 has no fork for this cluster: without an answer, the request would wait for its timeout
      assertNull(onlyDispatcher1.sendMessage(ping(node2Shared), RequestOptions.SYNC().timeout(30000)));
      assertTrue(System.currentTimeMillis() - start < 10000);
   }

   @Test
   public void testSingletonNameMakesTheChannelAFork() throws Exception
   {
      TypedProperties properties = loadJGroupsConfig("jar:/conf/jgroups/jgroups-service-tcp.xml");
      assertFalse(properties.containsKey(JGroupsTransport.CONFIGURATION_FILE));
      assertFalse(properties.containsKey(JGroupsTransport.CONFIGURATION_XML));
      Object configurator = properties.get(JGroupsTransport.CHANNEL_CONFIGURATOR);
      assertTrue(configurator instanceof SharedJGroupsChannelConfigurator);
      assertEquals("service-transport-tcp", ((SharedJGroupsChannelConfigurator)configurator).getName());
   }

   @Test
   public void testStackWithoutSingletonNameKeepsItsOwnChannel() throws Exception
   {
      TypedProperties properties = loadJGroupsConfig("jar:/conf/jgroups/udp.xml");
      assertFalse(properties.containsKey(JGroupsTransport.CONFIGURATION_FILE));
      assertFalse(properties.containsKey(JGroupsTransport.CHANNEL_CONFIGURATOR));
      assertTrue(properties.getProperty(JGroupsTransport.CONFIGURATION_XML).contains("<UDP"));
   }

   @Test
   public void testPartitionQualifiesTheMainCluster()
   {
      assertEquals("transport", SharedJGroupsTransport.getMainClusterName("transport"));
      System.setProperty(SharedJGroupsTransport.PARTITION_NAME, "partition");
      try
      {
         PropertyManager.refresh();
         assertEquals("partition-transport", SharedJGroupsTransport.getMainClusterName("transport"));
      }
      finally
      {
         System.clearProperty(SharedJGroupsTransport.PARTITION_NAME);
         PropertyManager.refresh();
      }
   }

   @Test
   public void testStackWithoutSingletonName() throws Exception
   {
      int[] ports = freePorts(1);
      String xml = stackXml(ports[0], ports).replaceAll("singleton_name=\"[^\"]*\"", "");
      ProtocolStackConfigurator stack = SharedJGroupsTransport.parse(xml);
      assertNull(SharedJGroupsTransport.getSingletonName(stack));
      assertThrows(IllegalArgumentException.class, () -> SharedJGroupsTransport.createChannel(stack, "cluster", null));
      assertNotEquals(null, SharedJGroupsTransport.getSingletonName(stack(ports[0], ports)));
   }

   @Test
   public void testChannelCreationSetsTheJGroupsFilter() throws Exception
   {
      int[] ports = freePorts(1);
      ProtocolStackConfigurator stack = stack(ports[0], ports);
      clearAllowList();

      resources.add(SharedJGroupsTransport.createChannel(stack, "filtered", "node1"));
      assertNotNull(System.getProperty(ClusterAllowList.JGROUPS_FILTER_PROPERTY));
   }

   @Test
   public void testJGroupsConfigurationLoadSetsTheJGroupsFilter() throws Exception
   {
      clearAllowList();

      loadJGroupsConfig("jar:/conf/jgroups/udp.xml");
      assertNotNull(System.getProperty(ClusterAllowList.JGROUPS_FILTER_PROPERTY));
   }

   /**
    * Forgets the list of this node and the filter it set, as before the first cache manager or channel of a node.
    */
   private static void clearAllowList() throws Exception
   {
      Field instance = ClusterAllowList.class.getDeclaredField("instance");
      instance.setAccessible(true);
      instance.set(null, null);
      System.clearProperty(ClusterAllowList.JGROUPS_FILTER_PROPERTY);
   }

   private static TypedProperties loadJGroupsConfig(String configurationFile) throws Exception
   {
      GlobalConfigurationBuilder builder = new GlobalConfigurationBuilder();
      builder.transport()
             .defaultTransport()
             .nodeName("node1")
             .addProperty(JGroupsTransport.CONFIGURATION_FILE, configurationFile);
      assertTrue(Utils.loadJGroupsConfig(new ConfigurationManagerImpl(), builder.build(), builder));
      return builder.build().transport().properties();
   }

   private DefaultCacheManager startNode(ProtocolStackConfigurator stack, String nodeName)
   {
      ConfigurationBuilderHolder holder = new ConfigurationBuilderHolder();
      GlobalConfigurationBuilder global = holder.getGlobalConfigurationBuilder();
      global.cacheManagerName(nodeName).jmx().enabled(false);
      global.transport()
            .defaultTransport()
            .clusterName("replicated-test")
            .nodeName(nodeName)
            .addProperty(JGroupsTransport.CHANNEL_CONFIGURATOR, new SharedJGroupsChannelConfigurator(stack, nodeName));
      holder.newConfigurationBuilder("replicated").clustering().cacheMode(CacheMode.REPL_SYNC);
      DefaultCacheManager manager = new DefaultCacheManager(holder, true);
      resources.add(manager::stop);
      return manager;
   }

   private JChannel connect(ProtocolStackConfigurator stack, String clusterName, String nodeName) throws Exception
   {
      JChannel channel = SharedJGroupsTransport.createChannel(stack, clusterName, nodeName);
      resources.add(channel);
      channel.connect(clusterName);
      return channel;
   }

   private MessageDispatcher dispatcher(JChannel channel)
   {
      MessageDispatcher dispatcher = new MessageDispatcher(channel, message -> "pong");
      resources.add(0, dispatcher);
      return dispatcher;
   }

   private static BytesMessage ping(JChannel target)
   {
      return new BytesMessage(target.getAddress(), "ping".getBytes(StandardCharsets.UTF_8));
   }

   private static void waitForMembers(Cache<?, ?> cache, int members) throws InterruptedException
   {
      for (int i = 0; i < 100 && cache.getAdvancedCache().getDistributionManager().getCacheTopology().getMembers()
                                       .size() < members; i++)
      {
         Thread.sleep(100);
      }
      assertEquals(members, cache.getAdvancedCache().getDistributionManager().getCacheTopology().getMembers().size());
   }

   private static void waitForMembers(JChannel channel, int members) throws InterruptedException
   {
      for (int i = 0; i < 100 && channel.getView().size() < members; i++)
      {
         Thread.sleep(100);
      }
      assertEquals(members, channel.getView().size());
   }

   /**
    * The shipped TCP stack, its variables resolved for a node bound to the given port and finding its peers on the
    * others.
    */
   private static ProtocolStackConfigurator stack(int port, int[] ports) throws IOException
   {
      return SharedJGroupsTransport.parse(stackXml(port, ports));
   }

   private static String stackXml(int port, int[] ports) throws IOException
   {
      StringBuilder hosts = new StringBuilder();
      for (int peer : ports)
      {
         hosts.append(hosts.length() == 0 ? "" : ",").append("127.0.0.1[").append(peer).append(']');
      }
      Map<String, String> properties = Map.of("tcp.bind_addr", "127.0.0.1",
                                              "tcp.bind_port", String.valueOf(port),
                                              "tcpping.initial_hosts", hosts.toString(),
                                              "fd_sock.start_port", "0");
      properties.forEach((name, value) -> System.setProperty(PROPERTY_PREFIX + name, value));
      try (InputStream input = TestSharedJGroupsTransport.class.getResourceAsStream(STACK))
      {
         return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
   }

   private static int[] freePorts(int count) throws IOException
   {
      int[] ports = new int[count];
      List<ServerSocket> sockets = new ArrayList<>();
      try
      {
         for (int i = 0; i < count; i++)
         {
            ServerSocket socket = new ServerSocket(0);
            sockets.add(socket);
            ports[i] = socket.getLocalPort();
         }
      }
      finally
      {
         for (ServerSocket socket : sockets)
         {
            socket.close();
         }
      }
      return ports;
   }
}
