/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.services.connector.credentials;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * The single entry point connectors call to obtain credentials material: resolves
 * the {@link ConnectorCredentialsProvider} named by {@link ConnectorCredentialsContext#getConnectorCredentialsProviderName()}
 * and delegates to it, after checking it actually supports the requested channel -
 * a guard no individual provider has to implement itself.
 * <p>
 * A bean once platform-wide: discovered by {@code commons-exo-extension}'s Spring
 * Boot bootstrap, the only WAR that scans this package.
 * <p>
 * Annotated {@code @Service} rather than {@code @Component} because that is what
 * the Kernel-Spring bridge exports: {@code KernelContainerLifecyclePlugin} only
 * registers a WAR's beans into the other WARs' contexts when the class carries
 * {@code @Service}. Under {@code @Component} this bean exists, resolves its
 * providers correctly, and is invisible to every connector that needs it - which
 * is a startup failure in the consumer's context, not here.
 * <p>
 * Providers announce themselves through {@link #register(ConnectorCredentialsProvider)}
 * rather than being collected as a {@code List} at construction, for the same boot-order
 * reason the sources do it on {@link PersonalCredentialsProvider}: a provider shipped in
 * its own WAR - a technical-account or OAuth one - has a Spring context built after this
 * module's, so a constructor-injected list would be resolved before it existed. It would
 * then be missing for good, and the symptom would be a connector told its configured
 * provider does not exist.
 */
@Service
public class ConnectorCredentialsService {

   /**
    * Concurrent because providers register from the boot thread of their own WAR,
    * and those threads are not serialised with one another.
    */
   private final Map<String, ConnectorCredentialsProvider> providersByName = new ConcurrentHashMap<>();

   /**
    * Records a provider under the name it declares, which is the name an
    * administrator stores on a connector to select it.
    * <p>
    * A duplicate name <b>throws</b>, where a duplicate source kind on
    * {@link PersonalCredentialsProvider#register(PersonalCredentialsSource)} only
    * warns. The consequences differ: two sources for one connector kind leave the
    * platform working off the first, while two providers under one name make every
    * server row naming it ambiguous about <i>which authority authenticates</i> - a
    * question no deployment should answer by WAR ordering. The failure surfaces in
    * the startup of the WAR that introduced the duplicate, which is where the
    * misconfiguration is.
    *
    * @param provider the provider announcing itself, never null
    * @throws IllegalStateException when another provider already holds that name
    */
   public void register(ConnectorCredentialsProvider provider) {
      ConnectorCredentialsProvider previous = providersByName.putIfAbsent(provider.getName(), provider);
      if (previous != null && previous != provider) {
         throw new IllegalStateException("Duplicate ConnectorCredentialsProvider name: " + provider.getName() + " (already held by "
             + previous.getClass().getName() + ", refused for " + provider.getClass().getName() + ")");
      }
   }

   /**
    * Produces ready-to-use credentials material for the given context.
    *
    * @throws ConnectorCredentialsException if no provider is registered under the
    *            context's provider name, if that provider does not support the
    *            requested channel, or if the provider itself fails to produce
    *            material
    */
   public ConnectorCredentials produce(ConnectorCredentialsContext context) throws ConnectorCredentialsException {
      ConnectorCredentialsProvider provider = resolve(context.getConnectorCredentialsProviderName());
      if (!provider.getSupportedChannels().contains(context.getChannel())) {
         throw new ConnectorCredentialsException("Provider " + provider.getName() + " does not support channel "
             + context.getChannel());
      }
      return provider.produce(context);
   }

   /**
    * Which identity the material for this context is going to address, without
    * producing anything - see
    * {@link ConnectorCredentialsProvider#resolveTargetIdentity(ConnectorCredentialsContext)}.
    * A connector needing it to build a per-account URL or a From header asks here,
    * and never reaches for a provider directly.
    * <p>
    * The channel is deliberately not checked, unlike {@link #produce(ConnectorCredentialsContext)}:
    * the account a provider would address does not depend on the channel it is asked
    * to speak, and a caller resolving an identity has no material to mistype.
    *
    * @throws ConnectorCredentialsException if no provider is registered under the
    *            context's provider name
    */
   public String resolveTargetIdentity(ConnectorCredentialsContext context) throws ConnectorCredentialsException {
      return resolve(context.getConnectorCredentialsProviderName()).resolveTargetIdentity(context);
   }

   /**
    * Invalidates any material the resolved provider may have cached for the given
    * context. A no-op if the context names no registered provider - this is a
    * best-effort cleanup called after a failure has already occurred, not a place
    * to raise a second one.
    */
   public void invalidate(ConnectorCredentialsContext context) {
      ConnectorCredentialsProvider provider = providersByName.get(context.getConnectorCredentialsProviderName());
      if (provider != null) {
         provider.invalidate(context);
      }
   }

   private ConnectorCredentialsProvider resolve(String providerName) throws ConnectorCredentialsException {
      ConnectorCredentialsProvider provider = providersByName.get(providerName);
      if (provider == null) {
         throw new ConnectorCredentialsException("No ConnectorCredentialsProvider registered for name " + providerName);
      }
      return provider;
   }

}
