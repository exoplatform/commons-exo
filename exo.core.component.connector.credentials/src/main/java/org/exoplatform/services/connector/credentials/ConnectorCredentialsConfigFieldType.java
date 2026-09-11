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
 * How a {@link ConnectorCredentialsConfigField} is rendered - and, for
 * {@link #SECRET}, how it is stored.
 * <p>
 * {@code SECRET} is the one type that means two things: the admin UI masks the field,
 * and the generic configuration storage encrypts the value. Keeping that in the
 * descriptor is what leaves a single place in the platform that encrypts a provider's
 * configuration, hence a single place to audit.
 * <p>
 * {@code CHOICE} exists so a closed list stays closed: free text cannot be validated,
 * and an unvalidatable value surfaces much later as a remote call addressed to
 * nobody, instead of a refusal at save time.
 */
public enum ConnectorCredentialsConfigFieldType {
   TEXT, SECRET, CHOICE
}
