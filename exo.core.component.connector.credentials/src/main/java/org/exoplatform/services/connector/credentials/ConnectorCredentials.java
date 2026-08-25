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

/**
 * Base type for the material an {@link ConnectorCredentialsProvider} hands back: always
 * already in its final, channel-specific, ready-to-use form (see {@link MailConnectorCredentials},
 * {@link HttpConnectorCredentials}) - never a generic value the connector would need to
 * interpret.
 */
public abstract class ConnectorCredentials {

   private final ConnectorCredentialsChannel channel;

   private final Long expiresAtEpochMillis;

   private final String targetIdentityHint;

   protected ConnectorCredentials(ConnectorCredentialsChannel channel, Long expiresAtEpochMillis, String targetIdentityHint) {
      this.channel = channel;
      this.expiresAtEpochMillis = expiresAtEpochMillis;
      this.targetIdentityHint = targetIdentityHint;
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

   /**
    * Optional resource-addressing hint produced by the provider itself (e.g. the
    * mailbox/principal to substitute for an impersonation-style provider such as
    * BlueMind sudo). {@code null} when the provider has nothing to add - the
    * connector then addresses the resource exactly as it already does today.
    * The connector uses this value if present; it never computes it.
    */
   public String getTargetIdentityHint() {
      return targetIdentityHint;
   }

}
