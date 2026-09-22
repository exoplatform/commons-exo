/**
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The contract's one time-dependent rule, recorded before a second provider depends
 * on it: material with no expiry never expires, and material expires at its instant,
 * not after it.
 */
class ConnectorCredentialsTest {

   @Test
   void testMaterialWithoutAnExpiryNeverExpires() {
      assertFalse(new HttpConnectorCredentials("Basic x", null).isExpired());
   }

   @Test
   void testMaterialExpiresAtItsInstantNotAfterIt() {
      long now = System.currentTimeMillis();
      assertTrue(new HttpConnectorCredentials("Basic x", now).isExpired(), "exactly at the instant counts as expired");
      assertTrue(new HttpConnectorCredentials("Basic x", now - 1).isExpired());
      assertFalse(new HttpConnectorCredentials("Basic x", now + 60_000L).isExpired());
   }

}
