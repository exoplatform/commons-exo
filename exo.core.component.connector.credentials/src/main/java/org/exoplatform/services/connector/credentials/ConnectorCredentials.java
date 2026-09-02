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

/**
 * Base type for the material an {@link ConnectorCredentialsProvider} hands back: always
 * already in its final, channel-specific, ready-to-use form (see {@link MailConnectorCredentials},
 * {@link HttpConnectorCredentials}) - never a generic value the connector would need to
 * interpret.
 */
public abstract class ConnectorCredentials {

   private final ConnectorCredentialsChannel channel;

   private final Long expiresAtEpochMillis;

   protected ConnectorCredentials(ConnectorCredentialsChannel channel, Long expiresAtEpochMillis) {
      this.channel = channel;
      this.expiresAtEpochMillis = expiresAtEpochMillis;
   }

   public ConnectorCredentialsChannel getChannel() {
      return channel;
   }

   /**
    * When this material stops being usable, or {@code null} if it never expires on
    * its own (e.g. the Personal provider's material, valid as long as the stored
    * credentials are). A caller must not use expired material - and must call
    * {@link ConnectorCredentialsProvider#invalidate(ConnectorCredentialsContext)} once it has
    * proven wrong (a single retry on a 401/AUTHENTICATIONFAILED), never retry in a loop.
    */
   public Long getExpiresAtEpochMillis() {
      return expiresAtEpochMillis;
   }

   public boolean isExpired() {
      return expiresAtEpochMillis != null && expiresAtEpochMillis <= System.currentTimeMillis();
   }

}
