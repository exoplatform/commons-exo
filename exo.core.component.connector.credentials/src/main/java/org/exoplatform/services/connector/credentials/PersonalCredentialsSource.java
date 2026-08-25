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
 * A connector's own way of exposing the personal credentials its users already
 * entered for it (e.g. email's stored IMAP username/password, CalDAV's stored
 * username/password). Implemented once per connector kind so the generic
 * {@link PersonalCredentialsProvider} never needs to know about any connector's
 * own storage type.
 */
public interface PersonalCredentialsSource {

   /**
    * Stable identifier of the connector family this source serves (e.g.
    * {@code "email"}, {@code "caldav"}), matching {@link ConnectorCredentialsContext#getConnectorKind()}.
    */
   String getConnectorKind();

   /**
    * The raw (username, secret) pair this connector's user has stored for
    * themselves, or {@code null} if the user has not configured personal
    * credentials on this connector.
    */
   RawCredentials getCredentials(String username);

}
