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
 * Thrown by an {@link ConnectorCredentialsProvider} when it cannot produce authentication
 * material for a given {@link ConnectorCredentialsContext} (remote call failure, technical
 * account misconfigured, target user unknown to the remote server...).
 */
public class ConnectorCredentialsException extends Exception {

   private static final long serialVersionUID = 1L;

   public ConnectorCredentialsException(String message) {
      super(message);
   }

   public ConnectorCredentialsException(String message, Throwable cause) {
      super(message, cause);
   }

}
