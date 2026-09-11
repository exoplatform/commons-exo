/*
 * Copyright (C) 2026 eXo Platform SAS
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

/**
 * The credentials providers a connector may select, each with the fields an
 * administrator must fill in for it. Served by this WAR, from the contract's own REST
 * layer: the list is the same for every connector kind, so neither email-connector nor
 * caldav-integration holds a copy of it.
 *
 * @returns {Promise<Array>} the providers, ordered by name
 */
export function getCredentialsProviders() {
  return fetch('/commons-exo-extension/rest/credentials-providers', {
    method: 'GET',
    credentials: 'include',
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when getting the credentials providers');
    }
    return resp.json();
  });
}
