/*
 * Copyright (C) 2010 eXo Platform SAS.
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
package org.exoplatform.services.cache.impl.infinispan;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.ExoCacheInitException;
import org.exoplatform.services.cache.impl.infinispan.distributed.DistributedExoCache;
import org.exoplatform.services.cache.impl.infinispan.generic.GenericExoCacheCreator;
import org.exoplatform.services.ispn.DistributedCacheManager;
import org.exoplatform.services.ispn.Utils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.jmx.MBeanServerLookup;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.statetransfer.StateTransferManager;
import org.infinispan.topology.CacheTopology;
import org.infinispan.topology.LocalTopologyManager;
import org.picocontainer.Startable;

import java.io.InputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;

/**
 * This class is the Infinispan implementation of the {@link org.exoplatform.services.cache.ExoCacheFactory}
 * 
 * @author <a href="mailto:nicolas.filotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class ExoCacheFactoryImpl implements ExoCacheFactory, Startable {

   /**
    * The logger
    */
   private static final Log LOG = ExoLogger.getLogger(ExoCacheFactoryImpl.class);

   /**
    * Set to {@code false} to start a replicated cache built from a template empty, with no state transfer.
    */
   public static final String REPLICATED_STATE_TRANSFER = "exo.cache.cluster.replication.statetransfer";

   /**
    * The initial parameter key that defines the full path of the configuration template
    */
   private static final String CACHE_CONFIG_TEMPLATE_KEY = "cache.config.template";

   /**
    * How often the factory checks whether a cache it started received its initial state.
    */
   private static final long STATE_TRANSFER_POLL_MILLIS = 100;

   /**
    * The initial parameter key that defines the full path of the optional async configuration template
    */
   private static final String CACHE_ASYNC_TEMPLATE_KEY  = "cache.async.config.template";

   /**
    * The cache manager for the distributed cache
    */
   private final DistributedCacheManager distributedCacheManager;

   /**
    * The current {@link ExoContainerContext}
    */
   private final ExoContainerContext ctx;

   /**
    * The configuration manager that allows us to retrieve a configuration file in several different
    * manners
    */
   private final ConfigurationManager configManager;

   /**
    * The {@link DefaultCacheManager} used for all sync cache regions
    */
   private final DefaultCacheManager cacheManager;

   /**
    * The {@link DefaultCacheManager} used for all async cache regions
    */
   private  DefaultCacheManager asyncCacheManager = null;

   /**
    * The mapping between the configuration types and the creators
    */
   private final Map<Class<? extends ExoCacheConfig>, ExoCacheCreator> mappingConfigTypeCreators =
      new HashMap<Class<? extends ExoCacheConfig>, ExoCacheCreator>();

   /**
    * The mapping between the implementations and the creators. This is mainly used for backward compatibility
    */
   private final Map<String, ExoCacheCreator> mappingImplCreators = new HashMap<String, ExoCacheCreator>();

   /**
    * The mapping between the cache names and the configuration paths
    */
   private final Map<String, String> mappingCacheNameConfig = new HashMap<String, String>();

   /**
    * The async cache template path
    */
   private final String asyncCacheTemplate;

   /**
    * The mapping between the cluster name and the cache managers
    */
   private final Map<String, DefaultCacheManager> mappingGlobalConfigCacheManager =
      new HashMap<String, DefaultCacheManager>();

   /**
    * The default creator
    */
   private final ExoCacheCreator defaultCreator = new GenericExoCacheCreator();

   private static final MBeanServerLookup MBEAN_SERVER_LOOKUP = new MBeanServerLookup()
   {
      public MBeanServer getMBeanServer(Properties properties)
      {
         return ExoContainerContext.getTopContainer().getMBeanServer();
      }
   };

   public ExoCacheFactoryImpl(ExoContainerContext ctx, InitParams params, ConfigurationManager configManager)
      throws ExoCacheInitException
   {
      this(ctx, getValueParam(params, CACHE_CONFIG_TEMPLATE_KEY), getValueParam(params, CACHE_ASYNC_TEMPLATE_KEY), configManager, null);
   }

   public ExoCacheFactoryImpl(ExoContainerContext ctx, InitParams params, ConfigurationManager configManager,
      DistributedCacheManager dcm) throws ExoCacheInitException
   {
      this(ctx, getValueParam(params, CACHE_CONFIG_TEMPLATE_KEY), getValueParam(params, CACHE_ASYNC_TEMPLATE_KEY), configManager, dcm);
   }

   public ExoCacheFactoryImpl(ExoContainerContext ctx, String cacheConfigTemplate, String cacheAsyncConfigTemplate, ConfigurationManager configManager,
      DistributedCacheManager dcm) throws ExoCacheInitException
   {
      this.distributedCacheManager = dcm;
      this.ctx = ctx;
      this.configManager = configManager;
      if (cacheConfigTemplate == null)
      {
         throw new IllegalArgumentException("The parameter '" + CACHE_CONFIG_TEMPLATE_KEY + "' must be set");
      }
      // Initialize the main cache manager
      this.cacheManager = initCacheManager(cacheConfigTemplate);

      this.asyncCacheTemplate = cacheAsyncConfigTemplate;
   }

   /**
    * Initializes the {@link DefaultCacheManager}
    * @throws ExoCacheInitException if the cache manager cannot be initialized
    */
   private DefaultCacheManager initCacheManager(final String cacheConfigTemplate) throws ExoCacheInitException
   {
      InputStream is = null;
      try
      {
         // Read the configuration file of the cache
         is = configManager.getInputStream(cacheConfigTemplate);
      }
      catch (Exception e)//NOSONAR
      {
         throw new ExoCacheInitException("The configuration of the CacheManager cannot be loaded from '"
                                             + cacheConfigTemplate + "'", e);
      }
      if (is == null)
      {
         throw new ExoCacheInitException("The configuration of the CacheManager cannot be found at '"
                                             + cacheConfigTemplate + "'");
      }
      GlobalConfigurationBuilder configBuilder;
      Configuration config;
      try
      {
         ParserRegistry parser = new ParserRegistry(Thread.currentThread().getContextClassLoader());
         // Loads the configuration from the input stream
         ConfigurationBuilderHolder holder = parser.parse(is);
         configBuilder = holder.getGlobalConfigurationBuilder();
         config = holder.getDefaultConfigurationBuilder().build();
      }
      catch (RuntimeException e) //NOSONAR
      {
         throw new ExoCacheInitException("Cannot parse the configuration '" + cacheConfigTemplate + "'", e);
      }
      configureCacheManager(configBuilder);
      DefaultCacheManager cacheManager;
      try
      {
         // Create the CacheManager from the new configuration
         cacheManager = new DefaultCacheManager(configBuilder.build(), config);
      }
      catch (RuntimeException e) //NOSONAR
      {
         throw new ExoCacheInitException(
             "Cannot initialize the CacheManager corresponding to the configuration '" + cacheConfigTemplate
                 + "'", e);
      }
      // Register the main cache manager
      mappingGlobalConfigCacheManager.put(cacheManager.getCacheManagerConfiguration().transport().clusterName(),
                                          cacheManager);
      return cacheManager;
   }

   /**
    * Configure the cache manager
    * 
    * @param configBuilder the configuration builder on which we applied all the required changes
    * @throws ExoCacheInitException
    */
   private void configureCacheManager(GlobalConfigurationBuilder configBuilder) throws ExoCacheInitException
   {
      GlobalConfiguration config = configBuilder.build();
      // Configure JGroups
      configureJGroups(config, configBuilder);
      // Configure the name of the cache manager
      configBuilder.globalJmxStatistics().enable()
         .cacheManagerName(config.globalJmxStatistics().cacheManagerName() + "_" + ctx.getName()).
         // Configure the MBeanServerLookup
         mBeanServerLookup(MBEAN_SERVER_LOOKUP);
   }

   /**
    * If some JGoups properties has been set, it will load the configuration and set
    * the cluster name by adding as suffix the name of the {@link ExoContainerContext}
    * 
    * @param config the global configuration from which the JGroups config will be extracted
    * @param configBuilder the related configuration builder
    * @throws ExoCacheInitException if any exception occurs while configuring JGroups
    */
   private void configureJGroups(GlobalConfiguration config, GlobalConfigurationBuilder configBuilder)
      throws ExoCacheInitException
   {
      if (loadJGroupsConfig(config, configBuilder))
      {
         // The JGroups Config could be loaded which means that the configuration is for a cluster
         configBuilder.transport().clusterName(config.transport().clusterName() + "-" + ctx.getName());
      }
   }

   /**
    * Load the JGroups configuration file thanks to the {@link ConfigurationManager}
    * @param config the global configuration from which the JGroups config will be extracted
    * @param configBuilder the related configuration builder
    * @return <code>true</code> if the JGoups config could be loaded successfully, 
    * <code>false</code> if there were no JGroups config to load
    * @throws ExoCacheInitException if the JGroups config could not be loaded
    */
   private boolean loadJGroupsConfig(GlobalConfiguration config, GlobalConfigurationBuilder configBuilder)
      throws ExoCacheInitException
   {
      return Utils.loadJGroupsConfig(configManager, config, configBuilder);
   }

   /**
    * To create a new cache instance according to the given configuration, we follow the steps below:
    * 
    * We first try to find if a specific location of the cache configuration has been defined thanks
    * to an external component plugin of type ExoCacheFactoryConfigPlugin. If so we use the default cache
    * configuration defined in this file otherwise we use the default cache configuration defined in
    * "${CACHE_CONFIG_TEMPLATE_KEY}"
    */
   @SuppressWarnings({"rawtypes", "unchecked"})
   public ExoCache<Serializable, Object> createCache(final ExoCacheConfig config) throws ExoCacheInitException
   {
      final String region = config.getName();
      String CacheConfig = mappingCacheNameConfig.get(region);
      if(CacheConfig == null && config.isAsync() && asyncCacheTemplate !=null && asyncCacheManager == null)
      {
         //use async template if cache use async mode
         this.asyncCacheManager = initCacheManager(asyncCacheTemplate);
      }
      final String customConfig =  CacheConfig;
      final ExoCache<Serializable, Object> eXoCache;
      final DefaultCacheManager cacheManager;
      try
      {
         final ConfigurationBuilder confBuilder = new ConfigurationBuilder();
         boolean templateStateTransfer = false;
         if (customConfig != null)
         {
            // A custom configuration has been set
            if (LOG.isInfoEnabled())
               LOG.info("A custom configuration has been set for the cache '" + region + "'.");
            ParserRegistry parser = new ParserRegistry(Thread.currentThread().getContextClassLoader());
            // Load the configuration
            ConfigurationBuilderHolder holder = parser.parse(configManager.getInputStream(customConfig));
            GlobalConfigurationBuilder configBuilder = holder.getGlobalConfigurationBuilder();
            // Configure JGroups and JMX since it could affect the state of the Global Config
            configureCacheManager(configBuilder);
            GlobalConfiguration gc = configBuilder.build();

            // Check if a CacheManager with the same GlobalConfiguration exists
            DefaultCacheManager currentCacheManager =
                mappingGlobalConfigCacheManager.get(gc.transport().clusterName());
            if (currentCacheManager == null)
            {
               // Use a different cache manager name to prevent naming conflict
               configBuilder.globalJmxStatistics().cacheManagerName(
                   gc.globalJmxStatistics().cacheManagerName() + "_" + region + "_" + ctx.getName());
               // No cache manager has been defined so far for this Cache Configuration
               currentCacheManager =
                   new DefaultCacheManager(configBuilder.build(), holder.getDefaultConfigurationBuilder()
                                                                        .build(), false);
               for (Entry<String, ConfigurationBuilder> entry : holder.getNamedConfigurationBuilders().entrySet())
               {
                  currentCacheManager.defineConfiguration(entry.getKey(), entry.getValue().build());
               }
               currentCacheManager.start();
               // We register this new cache manager
               mappingGlobalConfigCacheManager.put(gc.transport().clusterName(), currentCacheManager);
            }
            cacheManager = currentCacheManager;
            confBuilder.read(cacheManager.getDefaultCacheConfiguration());
         }
         else if (config.isDistributed())
         {
            // We expect a distributed cache
            if (distributedCacheManager == null)
            {
               throw new IllegalArgumentException(
                  "The DistributedCacheManager has not been defined in the configuration,"
                  + " please configure it at root container level if you want to use a distributed cache.");
            }
            return new DistributedExoCache(ctx, config,
               distributedCacheManager.getCache(DistributedExoCache.CACHE_NAME));
         }
         else
         {
            cacheManager = (config.isAsync() && asyncCacheManager != null) ? this.asyncCacheManager : this.cacheManager;
            // No custom configuration has been found, a configuration template will be used 
            if (LOG.isInfoEnabled())
               LOG.info("The configuration template will be used for the cache '" + region + "'.");
            confBuilder.read(cacheManager.getDefaultCacheConfiguration());
            if (!config.isRepicated())
            {
               // The cache is local
               confBuilder.clustering().cacheMode(CacheMode.LOCAL);
            }
            else if (!config.isInvalidated())
            {
               configureStateTransfer(confBuilder);
               templateStateTransfer = true;
            }
         }
         // Reset the configuration to avoid conflicts
         resetConfiguration(confBuilder);
         final ExoCacheCreator creator = getExoCacheCreator(config);
         final boolean awaitStateTransfer = templateStateTransfer;
         // Create the cache
         eXoCache = creator.create(config, confBuilder, new Callable<Cache<Serializable, Object>>()
         {
            public Cache<Serializable, Object> call() throws Exception
            {
               cacheManager.defineConfiguration(region, confBuilder.build());
               // create and start the cache
               Cache<Serializable, Object> cache = cacheManager.getCache(region);
               if (awaitStateTransfer)
               {
                  waitForInitialStateTransfer(region, cache);
               }
               return cache;
            }
         });
      }
      catch (Exception e) //NOSONAR
      {
         throw new ExoCacheInitException("The cache '" + region + "' could not be initialized", e);
      }
      return eXoCache;
   }

   /**
    * Add a list of creators to register
    * @param plugin the plugin that contains the creators
    */
   public void addCreator(ExoCacheCreatorPlugin plugin)
   {
      final List<ExoCacheCreator> creators = plugin.getCreators();
      for (ExoCacheCreator creator : creators)
      {
         mappingConfigTypeCreators.put(creator.getExpectedConfigType(), creator);
         Set<String> implementations = creator.getExpectedImplementations();
         if (implementations == null)
         {
            throw new IllegalArgumentException("The set of implementations cannot be null");
         }
         for (String imp : implementations)
         {
            mappingImplCreators.put(imp, creator);
         }
      }
   }

   /**
    * Add a list of custom configuration to register
    * @param plugin the plugin that contains the configs
    */
   public void addConfig(ExoCacheFactoryConfigPlugin plugin)
   {
      final Map<String, String> configs = plugin.getConfigs();
      mappingCacheNameConfig.putAll(configs);
   }

   @Override
   public void start() {
     // Nothing to start
   }

   @Override
   public void stop() {
     if (cacheManager != null) {
       cacheManager.stop();
     }
   }
   /**
    * Returns the value of the ValueParam if and only if the value is not empty
    */
   private static String getValueParam(InitParams params, String key)
   {
      if (params == null)
      {
         return null;
      }
      final ValueParam vp = params.getValueParam(key);
      String result;
      if (vp == null || (result = vp.getValue()) == null || (result = result.trim()).length() == 0)
      {
         return null;
      }
      return result;
   }

   /**
    * Returns the most relevant ExoCacheCreator according to the give configuration
    */
   protected ExoCacheCreator getExoCacheCreator(ExoCacheConfig config)
   {
      ExoCacheCreator creator = mappingConfigTypeCreators.get(config.getClass());
      if (creator == null)
      {
         // No creator for this type has been found, let's try the implementation field
         creator = mappingImplCreators.get(config.getImplementation());
         if (creator == null)
         {
            // No creator can be found, we will use the default creator
            if (LOG.isInfoEnabled())
               LOG.info("No cache creator has been found for the cache '" + config.getName()
                  + "', the default one will be used.");
            return defaultCreator;
         }
      }
      if (LOG.isInfoEnabled())
         LOG.info("The cache '" + config.getName() + "' will be created with '" + creator.getClass() + "'.");
      return creator;
   }

   /**
    * A replicated cache holds state that no store holds: a node that creates it, at its first use or when it rejoins
    * the cluster, receives the entries the other nodes hold and waits for them before it serves its first read. The
    * cluster templates do not transfer it by default, and a cache invalidated rather than replicated holds data that
    * each node loads from its store, which a transfer would only copy for nothing.
    * <p>
    * The cache starts without waiting for the transfer, which Infinispan would do inside the start: past the
    * state-transfer timeout it fails the start and keeps the failed cache, which every later lookup of the region
    * returns. The factory waits instead ({@link #waitForInitialStateTransfer}), up to the same timeout, and past it
    * serves the cache while the transfer goes on: until it ends, a read of an entry the node did not receive yet is
    * fetched from another node, and the local size and listings miss those entries.
    * {@link #REPLICATED_STATE_TRANSFER} set to {@code false} keeps the template instead: the cache starts at once,
    * empty.
    * @param confBuilder the configuration of a replicated cache
    */
   protected void configureStateTransfer(ConfigurationBuilder confBuilder)
   {
      if (!"false".equalsIgnoreCase(PropertyManager.getProperty(REPLICATED_STATE_TRANSFER)))
      {
         confBuilder.clustering().stateTransfer().fetchInMemoryState(true).awaitInitialTransfer(false);
      }
   }

   /**
    * Waits until the node is a reader of the cache, which is when Infinispan considers its initial state transfer
    * complete, for up to the cache's state-transfer timeout, as Infinispan does: not at all while rebalancing is
    * disabled, since the node does not become a reader then. Past the timeout a warning names the region, and the
    * cache is served while the transfer goes on: a read of an entry not received yet is fetched from another node,
    * and the local size and listings miss those entries until the transfer ends.
    * <p>
    * The wait runs on the thread that creates the cache, which the cache service does at the first lookup of the
    * region: often a request thread, a code sign-in for {@code otp.tentatives}. While a peer does not answer, that
    * request waits up to the state-transfer timeout of the cache's template, and so does every other request that looks
    * the region up meanwhile: {@code exo.cache.cluster.statetransfer.timeout} for the synchronous cluster template,
    * {@code exo.cache.async.cluster.statetransfer.timeout} for the asynchronous one, 240 s by default.
    * @param region the name of the cache
    * @param cache the started cache
    * @return {@code true} when the transfer completed within the timeout, or the cache transfers nothing
    */
   static boolean waitForInitialStateTransfer(String region, Cache<Serializable, Object> cache)
   {
      Configuration configuration = cache.getCacheConfiguration();
      if (!configuration.clustering().cacheMode().isClustered()
         || !configuration.clustering().stateTransfer().fetchInMemoryState())
      {
         return true;
      }
      ComponentRegistry registry = cache.getAdvancedCache().getComponentRegistry();
      if (!isRebalancingEnabled(registry, region))
      {
         return true;
      }
      StateTransferManager stateTransferManager = registry.getStateTransferManager();
      Address self = cache.getCacheManager().getAddress();
      long timeout = configuration.clustering().stateTransfer().timeout();
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
      while (!isReader(stateTransferManager, self))
      {
         if (cache.getStatus().isTerminated() || cache.getStatus().isStopping())
         {
            return false;
         }
         if (System.nanoTime() - deadline >= 0)
         {
            LOG.warn("The initial state transfer of the cache '" + region + "' did not complete within the"
               + " state-transfer timeout (" + timeout + " ms): the node serves the cache while the transfer goes on;"
               + " until it ends, a read of an entry not received yet is fetched from another node, and the local"
               + " size and listings miss those entries.");
            return false;
         }
         try
         {
            Thread.sleep(STATE_TRANSFER_POLL_MILLIS);
         }
         catch (InterruptedException e)
         {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while waiting for the initial state transfer of the cache '" + region + "'");
            return false;
         }
      }
      return true;
   }

   /**
    * Asks the coordinator once, as Infinispan does before its own wait: with rebalancing disabled a joining node
    * never becomes a reader, and Infinispan counts the initial transfer complete at once.
    */
   private static boolean isRebalancingEnabled(ComponentRegistry registry, String region)
   {
      try
      {
         LocalTopologyManager topologyManager = registry.getComponent(LocalTopologyManager.class);
         return topologyManager == null || topologyManager.isRebalancingEnabled();
      }
      catch (Exception e)
      {
         LOG.debug("Could not tell whether rebalancing is enabled for the cache '" + region + "': waiting for it", e);
         return true;
      }
   }

   private static boolean isReader(StateTransferManager stateTransferManager, Address self)
   {
      CacheTopology topology = stateTransferManager == null ? null : stateTransferManager.getCacheTopology();
      return stateTransferManager == null
         || topology != null && topology.getReadConsistentHash() != null
            && topology.getReadConsistentHash().getMembers().contains(self);
   }

   /**
    * Clean the configuration template to prevent conflicts
    */
   protected void resetConfiguration(ConfigurationBuilder confBuilder)
   {
      confBuilder.eviction().strategy(EvictionStrategy.NONE).size(-1).expiration()
         .lifespan(-1L).maxIdle(-1L).wakeUpInterval(60000L);
   }

}
