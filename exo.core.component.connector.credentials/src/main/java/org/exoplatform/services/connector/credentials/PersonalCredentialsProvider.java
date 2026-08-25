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

/**
 * The single, generic "Personal" provider: delegates to whichever connector kind's
 * own {@link PersonalCredentialsSource} matches the context, then wraps the raw
 * credentials it gets back into the material shape the requested channel needs.
 * <p>
 * Framework-agnostic on purpose (no Spring annotation here - this module has no
 * Spring dependency): each connector kind that wants Personal mode declares this
 * class as a bean itself, guarded so at most one instance ever exists platform-wide
 * regardless of how many connector kinds contribute the bean definition.
 */
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
      PersonalCredentialsSource source = sourcesByConnectorKind.get(context.getConnectorKind());
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

   @Override
   public void invalidate(ConnectorCredentialsContext context) {
      // Nothing cached here: material is rebuilt fresh from the source's own storage
      // on every call, so there is nothing to invalidate.
   }

   private ConnectorCredentials toMaterial(ConnectorCredentialsChannel channel, RawCredentials credentials) {
      if (channel == ConnectorCredentialsChannel.HTTP) {
         String token = Base64.getEncoder()
                               .encodeToString((credentials.getUsername() + ":" + credentials.getSecret()).getBytes(StandardCharsets.UTF_8));
         return new HttpConnectorCredentials("Basic " + token, null, null);
      }
      Authenticator authenticator = new Authenticator() {
         @Override
         protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(credentials.getUsername(), credentials.getSecret());
         }
      };
      return new MailConnectorCredentials(channel, authenticator, null, null);
   }

}
