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

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cache.ExoCacheConfig;
import org.exoplatform.services.cache.ExoCacheFactory;
import org.exoplatform.services.cache.ExoCacheInitException;
import org.exoplatform.services.cache.impl.infinispan.generic.GenericExoCacheCreator;
import org.exoplatform.services.ispn.Utils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.infinispan.Cache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.jmx.MBeanServerLookup;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.picocontainer.Startable;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

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
    * The initial parameter key that defines the full path of the configuration template
    */
   private static final String CACHE_CONFIG_TEMPLATE_KEY = "cache.config.template";

   /**
    * The initial parameter key that defines the full path of the optional async configuration template
    */
   private static final String CACHE_ASYNC_TEMPLATE_KEY  = "cache.async.config.template";

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
   private final DefaultCacheManager asyncCacheManager;

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
      this(ctx, getValueParam(params, CACHE_CONFIG_TEMPLATE_KEY), getValueParam(params, CACHE_ASYNC_TEMPLATE_KEY), configManager);
   }

   public ExoCacheFactoryImpl(ExoContainerContext ctx, String cacheConfigTemplate, String cacheAsyncConfigTemplate, ConfigurationManager configManager)
      throws ExoCacheInitException
   {
      this.ctx = ctx;
      this.configManager = configManager;
      if (cacheConfigTemplate == null)
      {
         throw new IllegalArgumentException("The parameter '" + CACHE_CONFIG_TEMPLATE_KEY + "' must be set");
      }
      // Initialize the main cache manager
      this.cacheManager = initCacheManager(cacheConfigTemplate);

      // Started with the main one, not on the first asynchronous cache: in cluster mode, its channel is a fork of the
      // channel the node shares, whose oldest member coordinates the fork and must therefore have it
      try
      {
         this.asyncCacheManager = cacheAsyncConfigTemplate == null ? null : initCacheManager(cacheAsyncConfigTemplate);
      }
      catch (ExoCacheInitException | RuntimeException e)
      {
         // No instance will be stopped: release the channel of the main one
         cacheManager.stop();
         throw e;
      }
   }

   /**
    * Initializes the {@link DefaultCacheManager}
    * @throws ExoCacheInitException if the cache manager cannot be initialized
    */
   private DefaultCacheManager initCacheManager(final String cacheConfigTemplate) throws ExoCacheInitException
   {
      ConfigurationBuilderHolder holder = parseConfiguration(cacheConfigTemplate);
      configureCacheManager(holder.getGlobalConfigurationBuilder(), cacheConfigTemplate);
      DefaultCacheManager cacheManager;
      try
      {
         // Create the CacheManager from the new configuration
         cacheManager = new DefaultCacheManager(holder, true);
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
    * Reads and parses an Infinispan configuration file
    * @param configurationPath the path of the file, as the {@link ConfigurationManager} resolves it
    * @throws ExoCacheInitException if the file cannot be found, read or parsed
    */
   private ConfigurationBuilderHolder parseConfiguration(String configurationPath) throws ExoCacheInitException
   {
      InputStream is;
      try
      {
         is = configManager.getInputStream(configurationPath);
      }
      catch (Exception e)//NOSONAR
      {
         throw new ExoCacheInitException("The configuration of the CacheManager cannot be loaded from '"
                                             + configurationPath + "'", e);
      }
      if (is == null)
      {
         throw new ExoCacheInitException("The configuration of the CacheManager cannot be found at '"
                                             + configurationPath + "'");
      }
      try (InputStream input = is)
      {
         ParserRegistry parser = new ParserRegistry(Thread.currentThread().getContextClassLoader());
         return parser.parse(input, MediaType.APPLICATION_XML);
      }
      catch (Exception e) //NOSONAR
      {
         throw new ExoCacheInitException("Cannot parse the configuration '" + configurationPath + "'", e);
      }
   }

   /**
    * Configure the cache manager
    * 
    * @param configBuilder the configuration builder on which we applied all the required changes
    * @param configurationPath the path of the Infinispan configuration
    * @throws ExoCacheInitException
    */
   private void configureCacheManager(GlobalConfigurationBuilder configBuilder, String configurationPath)
      throws ExoCacheInitException
   {
      GlobalConfiguration config = configBuilder.build();
      // Configure the marshaller, which sets the deserialization filter of JGroups before any channel is created
      Utils.configureSerialization(configBuilder, configurationPath);
      // Configure JGroups
      configureJGroups(config, configBuilder);
      // Configure the name of the cache manager
      String cacheManagerName = config.cacheManagerName() + "_" + ctx.getName();
      configBuilder.cacheManagerName(cacheManagerName);
      // Configure the MBeanServerLookup
      configBuilder.jmx()
                   .enabled(true)
                   .domain(getFreeJmxDomain(config.jmx().domain(), cacheManagerName))
                   .mBeanServerLookup(MBEAN_SERVER_LOOKUP);
   }

   /**
    * Infinispan no longer lets two cache managers of the same name share a JMX domain: the second one takes the
    * configured domain suffixed with the first free index, as the <code>duplicate-domains</code> attribute of the
    * former templates did.
    */
   private static String getFreeJmxDomain(String domain, String cacheManagerName)
   {
      MBeanServer mbeanServer = MBEAN_SERVER_LOOKUP.getMBeanServer(null);
      String freeDomain = domain;
      for (int i = 2; isCacheManagerRegistered(mbeanServer, freeDomain, cacheManagerName); i++)
      {
         freeDomain = domain + i;
      }
      return freeDomain;
   }

   private static boolean isCacheManagerRegistered(MBeanServer mbeanServer, String domain, String cacheManagerName)
   {
      try
      {
         return mbeanServer.isRegistered(new ObjectName(domain + ":type=CacheManager,name="
            + ObjectName.quote(cacheManagerName) + ",component=CacheManager"));
      }
      catch (MalformedObjectNameException e)
      {
         return false;
      }
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
      if (CacheConfig == null && config.isDistributed())
      {
         throw new ExoCacheInitException("The cache '" + region + "' is configured as distributed, which is no longer"
            + " supported: configure it as local, replicated, or invalidated, synchronous or asynchronous.");
      }
      final String customConfig =  CacheConfig;
      final ExoCache<Serializable, Object> eXoCache;
      final DefaultCacheManager cacheManager;
      try
      {
         final ConfigurationBuilder confBuilder = new ConfigurationBuilder();
         if (customConfig != null)
         {
            // A custom configuration has been set
            if (LOG.isInfoEnabled())
               LOG.info("A custom configuration has been set for the cache '" + region + "'.");
            // Load the configuration
            ConfigurationBuilderHolder holder = parseConfiguration(customConfig);
            GlobalConfigurationBuilder configBuilder = holder.getGlobalConfigurationBuilder();
            // Configure JGroups and JMX since it could affect the state of the Global Config
            configureCacheManager(configBuilder, customConfig);
            GlobalConfiguration gc = configBuilder.build();

            // Check if a CacheManager with the same GlobalConfiguration exists
            DefaultCacheManager currentCacheManager =
                mappingGlobalConfigCacheManager.get(gc.transport().clusterName());
            if (currentCacheManager == null)
            {
               // Use a different cache manager name to prevent naming conflict
               configBuilder.cacheManagerName(gc.cacheManagerName() + "_" + region + "_" + ctx.getName());
               // No cache manager has been defined so far for this Cache Configuration
               currentCacheManager = new DefaultCacheManager(holder, true);
               // We register this new cache manager
               mappingGlobalConfigCacheManager.put(gc.transport().clusterName(), currentCacheManager);
            }
            cacheManager = currentCacheManager;
            confBuilder.read(cacheManager.getDefaultCacheConfiguration());
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
         }
         // Reset the configuration to avoid conflicts
         resetConfiguration(confBuilder);
         final ExoCacheCreator creator = getExoCacheCreator(config);
         // Create the cache
         eXoCache = creator.create(config, confBuilder, new Callable<Cache<Serializable, Object>>()
         {
            public Cache<Serializable, Object> call() throws Exception
            {
               cacheManager.defineConfiguration(region, confBuilder.build());
               // create and start the cache
               return cacheManager.getCache(region);
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

   /**
    * Stops every cache manager of the factory, the asynchronous and custom ones being mapped to their cluster names, so
    * that each releases its channel, a fork of the channel the node shares in cluster mode.
    */
   @Override
   public void stop() {
     Set<DefaultCacheManager> cacheManagers = Collections.newSetFromMap(new IdentityHashMap<>());
     cacheManagers.add(cacheManager);
     cacheManagers.addAll(mappingGlobalConfigCacheManager.values());
     cacheManagers.forEach(DefaultCacheManager::stop);
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
    * Clean the configuration template to prevent conflicts
    */
   protected void resetConfiguration(ConfigurationBuilder confBuilder)
   {
      confBuilder.memory().maxCount(-1).whenFull(EvictionStrategy.NONE);
      confBuilder.expiration().lifespan(-1L).maxIdle(-1L).wakeUpInterval(60000L);
   }

}
