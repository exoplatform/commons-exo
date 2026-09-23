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
package org.exoplatform.services.connector.credentials.bluemind;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

/**
 * BlueMind refused the request's own authentication - HTTP 401 or 403 - as opposed
 * to answering it: a session it no longer accepts, or a technical password it
 * rejects. Kept apart from a refusal BlueMind states in its answer ("status: Bad" for
 * an account it does not know) because only this one is worth retrying with a fresh
 * technical session (EXO-89647).
 */
public class BluemindAuthenticationException extends ConnectorCredentialsException {

   private static final long serialVersionUID = 1L;

   public BluemindAuthenticationException(String message) {
      super(message);
   }
}
