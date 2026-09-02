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
 * A raw (username, secret) pair as stored by a connector for its Personal mode,
 * before {@link PersonalCredentialsProvider} wraps it into channel-typed material.
 * Deliberately not reused elsewhere in the contract: every other provider produces
 * {@link ConnectorCredentials} directly, without an intermediate raw-pair step.
 */
public class RawCredentials {

   private final String username;

   private final String secret;

   public RawCredentials(String username, String secret) {
      this.username = username;
      this.secret = secret;
   }

   public String getUsername() {
      return username;
   }

   public String getSecret() {
      return secret;
   }

}
