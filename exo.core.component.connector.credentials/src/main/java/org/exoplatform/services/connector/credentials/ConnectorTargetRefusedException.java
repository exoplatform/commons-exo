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
 * The remote authority refused the account a provider derived for this user - the
 * account of {@link ConnectorCredentialsProvider#resolveTargetIdentity(ConnectorCredentialsContext)}
 * - as opposed to failing to answer, or refusing the provider's own technical account.
 * <p>
 * A fact about this user's account, not about the connector: producing again for the
 * same target is refused again, while the other users of the connector are not
 * concerned. A connector may therefore stop offering this user a connection through
 * the provider, until the target the provider derives for them changes.
 * <p>
 * A provider throws it only when the remote answer names the target as the refused
 * party. Any other failure - unreachable authority, technical account refused, an
 * unexpected status - stays a plain {@link ConnectorCredentialsException}.
 */
public class ConnectorTargetRefusedException extends ConnectorCredentialsException {

   private static final long serialVersionUID = 4471964083410718301L;

   public ConnectorTargetRefusedException(String message, Throwable cause) {
      super(message, cause);
   }

}
