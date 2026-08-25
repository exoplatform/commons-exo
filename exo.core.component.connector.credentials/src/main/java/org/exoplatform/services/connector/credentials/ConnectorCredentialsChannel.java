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
 * A channel an {@link ConnectorCredentialsProvider} can produce ready-to-use material for.
 * IMAP and SMTP share the same javax.mail material shape ({@link MailConnectorCredentials});
 * HTTP covers CalDAV/CardDAV ({@link HttpConnectorCredentials}).
 */
public enum ConnectorCredentialsChannel {
   IMAP, SMTP, HTTP
}
