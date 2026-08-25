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
 * Material for the {@link ConnectorCredentialsChannel#HTTP} channel (CalDAV/CardDAV): the final value
 * to send in the HTTP {@code Authorization} header (e.g. {@code "Basic ..."} or
 * {@code "Bearer ..."}), already produced by the provider - the connector just sets
 * the header, it never assembles the value itself.
 */
public class HttpConnectorCredentials extends ConnectorCredentials {

   private final String authorizationHeaderValue;

   public HttpConnectorCredentials(String authorizationHeaderValue, Long expiresAtEpochMillis) {
      super(ConnectorCredentialsChannel.HTTP, expiresAtEpochMillis);
      this.authorizationHeaderValue = authorizationHeaderValue;
   }

   public String getAuthorizationHeaderValue() {
      return authorizationHeaderValue;
   }

}
