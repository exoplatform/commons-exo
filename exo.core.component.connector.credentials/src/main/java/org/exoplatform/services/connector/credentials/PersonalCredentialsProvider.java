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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The single, generic "Personal" provider: delegates to whichever connector kind's
 * own {@link PersonalCredentialsSource} matches the context, then wraps the raw
 * credentials it gets back into the material shape the requested channel needs.
 * <p>
 * A bean once platform-wide: {@code commons-exo-extension}'s Spring Boot bootstrap
 * lists this package in its {@code scanBasePackages}, and it is the only WAR that
 * does - so this component-scan discovery only ever happens once, no
 * {@code @ConditionalOnMissingBean} guard needed.
 * <p>
 * The sources are <b>not</b> discovered by that scan, and this is where the design
 * turns: each source lives in its own WAR, whose Spring context is built
 * <i>after</i> this one, because every connector depends on this module. A
 * {@code List<PersonalCredentialsSource>} injected here would therefore be
 * resolved while no source existed yet, and would stay empty for the life of the
 * platform - silently, since an empty map produces no credentials rather than an
 * error. So the sources push themselves in through
 * {@link #register(PersonalCredentialsSource)} from their own
 * {@code @PostConstruct}, which runs at the one moment both this bean and theirs
 * exist.
 * <p>
 * Hence {@code @Service} rather than {@code @Component}: that is the annotation
 * the Kernel-Spring bridge exports across WARs
 * ({@code KernelContainerLifecyclePlugin.isServiceBean}), and under
 * {@code @Component} no source could see this provider to register into it.
 */
@Service
public class PersonalCredentialsProvider implements ConnectorCredentialsProvider {

   public static final String NAME = "personal";

   private static final Log LOG = ExoLogger.getLogger(PersonalCredentialsProvider.class);

   private final ConnectorCredentialsService connectorCredentialsService;

   /**
    * Concurrent because sources register from the boot thread of their own WAR,
    * and those threads are not serialised with one another.
    */
   private final Map<String, PersonalCredentialsSource> sourcesByConnectorKind = new ConcurrentHashMap<>();

   public PersonalCredentialsProvider(ConnectorCredentialsService connectorCredentialsService) {
      this.connectorCredentialsService = connectorCredentialsService;
   }

   /**
    * Announces this provider to the resolution service. Plain required injection
    * and no {@code @ConditionalOnClass}, unlike a provider shipped in its own WAR:
    * this one lives in the same module and the same Spring context as the service,
    * so both absences the guards cover are impossible here.
    */
   @PostConstruct
   public void register() {
      connectorCredentialsService.register(this);
   }

   /**
    * Records a connector's source under the kind it declares. A second source for
    * a kind already registered is refused rather than silently replacing the
    * first: two sources for one kind means two storages claiming the same users,
    * and whichever won would depend on WAR deployment order.
    *
    * @param source the source announcing itself, never null
    */
   public void register(PersonalCredentialsSource source) {
      String connectorKind = source.getConnectorKind();
      PersonalCredentialsSource previous = sourcesByConnectorKind.putIfAbsent(connectorKind, source);
      if (previous != null) {
         LOG.warn("A personal credentials source is already registered for connector kind '{}' ({}); ignoring {}",
                  connectorKind,
                  previous.getClass().getName(),
                  source.getClass().getName());
         return;
      }
      LOG.info("Registered personal credentials source for connector kind '{}'", connectorKind);
   }

   @Override
   public String getName() {
      return NAME;
   }

   @Override
   public Set<ConnectorCredentialsChannel> getSupportedChannels() {
      return EnumSet.allOf(ConnectorCredentialsChannel.class);
   }

   @Override
   public boolean requiresUserAction() {
      return true;
   }

   @Override
   public ConnectorCredentials produce(ConnectorCredentialsContext context) throws ConnectorCredentialsException {
      PersonalCredentialsSource source = source(context);
      if (source == null) {
         throw new ConnectorCredentialsException("No PersonalCredentialsSource registered for connector kind "
             + context.getConnectorKind());
      }
      RawCredentials credentials = source.getCredentials(context.getUsername());
      if (credentials == null) {
         throw new ConnectorCredentialsException("No personal credentials configured for user " + context.getUsername()
             + " on connector kind " + context.getConnectorKind());
      }
      return toMaterial(context.getChannel(), credentials);
   }

   /**
    * The remote account the user entered themselves, which is what a connector
    * addressing a per-account resource needs - a CalDAV collection path, an SMTP
    * From. Personal authenticates as that same account, so this is not an
    * impersonation hint here; it is answered all the same, so that a connector has
    * one rule for every provider instead of reading the user's settings itself and
    * branching on which provider is configured.
    * <p>
    * Reads through {@link #source(ConnectorCredentialsContext)}, the same lookup
    * {@link #produce(ConnectorCredentialsContext)} uses, so the account named here
    * and the account authenticated there cannot drift apart. Answers {@code null}
    * rather than throwing when nothing is configured: the caller decides whether
    * that is fatal for what it was trying to build.
    */
   @Override
   public String resolveTargetIdentity(ConnectorCredentialsContext context) {
      PersonalCredentialsSource source = source(context);
      RawCredentials credentials = source == null ? null : source.getCredentials(context.getUsername());
      return credentials == null ? null : credentials.getUsername();
   }

   @Override
   public void invalidate(ConnectorCredentialsContext context) {
      // Nothing cached here: material is rebuilt fresh from the source's own storage
      // on every call, so there is nothing to invalidate.
   }

   /**
    * The source serving the context's connector kind, read at call time: a
    * connector's WAR may have registered long after this bean was built, and one
    * deployed later still must be served. Null when no connector of that kind is
    * deployed, which is a normal state - an addon absent from a platform
    * registers nothing.
    *
    * @param context the request being served
    * @return the source, or null
    */
   private PersonalCredentialsSource source(ConnectorCredentialsContext context) {
      return sourcesByConnectorKind.get(context.getConnectorKind());
   }

   private ConnectorCredentials toMaterial(ConnectorCredentialsChannel channel, RawCredentials credentials) {
      if (channel == ConnectorCredentialsChannel.HTTP) {
         String token = Base64.getEncoder()
                               .encodeToString((credentials.getUsername() + ":" + credentials.getSecret()).getBytes(StandardCharsets.UTF_8));
         return new HttpConnectorCredentials("Basic " + token, null);
      }
      Authenticator authenticator = new Authenticator() {
         @Override
         protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(credentials.getUsername(), credentials.getSecret());
         }
      };
      return new MailConnectorCredentials(channel, authenticator, null);
   }

}
