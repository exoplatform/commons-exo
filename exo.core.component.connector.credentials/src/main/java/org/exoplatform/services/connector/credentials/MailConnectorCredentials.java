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

import javax.mail.Authenticator;

/**
 * Material for the {@link ConnectorCredentialsChannel#IMAP} and {@link ConnectorCredentialsChannel#SMTP} channels:
 * a javax.mail {@link Authenticator} already configured with whatever credentials
 * the provider produced (a user's own password, a technical account's SASL PLAIN
 * authzid, a BlueMind sudo session id used as the password...). The connector plugs
 * it straight into its javax.mail {@code Session} - it never inspects what is inside.
 */
public class MailConnectorCredentials extends ConnectorCredentials {

   private final Authenticator authenticator;

   public MailConnectorCredentials(ConnectorCredentialsChannel channel, Authenticator authenticator, Long expiresAtEpochMillis) {
      super(channel, expiresAtEpochMillis);
      if (channel != ConnectorCredentialsChannel.IMAP && channel != ConnectorCredentialsChannel.SMTP) {
         throw new IllegalArgumentException("MailConnectorCredentials only supports IMAP or SMTP, got " + channel);
      }
      this.authenticator = authenticator;
   }

   public Authenticator getAuthenticator() {
      return authenticator;
   }

}
