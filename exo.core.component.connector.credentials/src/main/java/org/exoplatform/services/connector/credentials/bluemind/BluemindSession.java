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

/**
 * A BlueMind session, as {@code /api/auth} hands it back.
 * <p>
 * The {@code authKey} is what every later request carries - in the
 * {@code X-BM-ApiKey} header for the API, as the password for IMAP and CalDAV.
 * The {@code latd} is the account the session actually belongs to, and it is <b>not</b>
 * the login that was asked for. Both halves can differ: BlueMind resolves the domain to
 * its own internal uid, and it resolves an alias to the real account - a live 5.7
 * instance answered a login of {@code svc-exo@demo3.example.com} with
 * {@code exo.service@19d43481671.internal}, neither half surviving. So it is carried for
 * diagnosis and <b>never compared</b> to the requested login: the comparison would reject
 * perfectly good sessions.
 *
 * <p>
 * {@link #toString()} names the account and never the {@code authKey}: the key opens
 * every mailbox the technical account can impersonate, and a record's generated
 * {@code toString} would put it in any log line or exception message the session
 * reaches.
 *
 * @param authKey the session token
 * @param latd the account the session belongs to, in BlueMind's own spelling
 */
public record BluemindSession(String authKey, String latd) {

  @Override
  public String toString() {
    return "BluemindSession[latd=" + latd + "]";
  }
}
