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
 * A channel an {@link ConnectorCredentialsProvider} can produce ready-to-use material for.
 * IMAP and SMTP share the same javax.mail material shape ({@link MailConnectorCredentials});
 * HTTP covers CalDAV/CardDAV ({@link HttpConnectorCredentials}).
 */
public enum ConnectorCredentialsChannel {
   IMAP, SMTP, HTTP
}
