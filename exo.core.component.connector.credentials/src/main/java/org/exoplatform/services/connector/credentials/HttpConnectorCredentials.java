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
 * Material for the {@link ConnectorCredentialsChannel#HTTP} channel (CalDAV/CardDAV): the final value
 * to send in the HTTP {@code Authorization} header (e.g. {@code "Basic ..."} or
 * {@code "Bearer ..."}), already produced by the provider - the connector just sets
 * the header, it never assembles the value itself.
 */
public class HttpConnectorCredentials extends ConnectorCredentials {

   private final String authorizationHeaderValue;

   public HttpConnectorCredentials(String authorizationHeaderValue, Long expiresAtEpochMillis, String targetIdentityHint) {
      super(ConnectorCredentialsChannel.HTTP, expiresAtEpochMillis, targetIdentityHint);
      this.authorizationHeaderValue = authorizationHeaderValue;
   }

   public String getAuthorizationHeaderValue() {
      return authorizationHeaderValue;
   }

}
