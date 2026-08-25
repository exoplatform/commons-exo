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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;

import org.springframework.stereotype.Component;

/**
 * The single, generic "Personal" provider: delegates to whichever connector kind's
 * own {@link PersonalCredentialsSource} matches the context, then wraps the raw
 * credentials it gets back into the material shape the requested channel needs.
 * <p>
 * A bean once platform-wide: {@code commons-exo-extension}'s Spring Boot bootstrap
 * lists this package in its {@code scanBasePackages}, and it is the only WAR that
 * does - so this component-scan discovery only ever happens once, no
 * {@code @ConditionalOnMissingBean} guard needed.
 */
@Component
public class PersonalCredentialsProvider implements ConnectorCredentialsProvider {

   public static final String NAME = "personal";

   private final Map<String, PersonalCredentialsSource> sourcesByConnectorKind;

   public PersonalCredentialsProvider(List<PersonalCredentialsSource> sources) {
      this.sourcesByConnectorKind = sources.stream()
                                            .collect(Collectors.toMap(PersonalCredentialsSource::getConnectorKind,
                                                                       Function.identity(),
                                                                       (first, second) -> first));
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
