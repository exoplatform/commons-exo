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

import java.util.Map;

/**
 * Where a provider's configuration lives, for one connector.
 * <p>
 * An interface, and not because a second implementation is expected: EXO-89646 decides
 * where the sudo provider's code lives, and the storage will follow it. Keeping the
 * contract separate lets that move happen without reopening a caller.
 * <p>
 * Two reads, deliberately. A provider needs its secret in the clear to do its work; an
 * administration screen must never see it. In Java the method name is the only guard
 * against calling the wrong one from a REST layer, hence
 * {@link #readDecrypted(ConnectorCredentialsContext)} saying outright what it hands
 * back.
 * <p>
 * Only the two methods that touch the codec are checked: a keystore that cannot be read
 * must reach the caller as a failure, never as a null secret quietly stored or handed to
 * a provider - a silent null there authenticates nobody and says nothing about why.
 */
public interface ConnectorProviderConfigStorage {

   /**
    * Checks a configuration against its provider's descriptor without writing anything.
    * <p>
    * Exists because a setting key carries the connector's id, so a configuration can
    * only be written after the connector has been created - and a refusal at that point
    * leaves a connector created with no configuration, which an administrator answers by
    * creating a second one. Validating first keeps that state from happening rather than
    * repairing it.
    *
    * @param context the connector and provider the values are for; the id may still be
    *          unassigned, validation does not use it
    * @param values what was posted, keyed by descriptor field
    * @throws ConnectorCredentialsException carrying a message code when a key is unknown,
    *           a required field empty, or a value outside a field's options
    */
   void validate(ConnectorCredentialsContext context, Map<String, String> values) throws ConnectorCredentialsException;

   /**
    * Writes a configuration, after the same checks as
    * {@link #validate(ConnectorCredentialsContext, Map)}.
    * <p>
    * A blank SECRET keeps the stored one only while every non-secret value is unchanged.
    * An update that moves the API URL or the login must carry the secret again.
    * <p>
    * <b>Entitlement is the caller's.</b> This storage checks no administrator: the bean is
    * published to every WAR, and whoever calls it decides who may write. Its two callers
    * write it only from operations that check the administrator first
    * ({@code CaldavServerService} for a CalDAV server, {@code EmailConnectorService} for an
    * email connector). A new caller does the same.
    *
    * @param context the connector and provider the values are for
    * @param values what was posted, keyed by descriptor field
    * @throws ConnectorCredentialsException carrying a message code on the refusals of
    *           validate, or when the codec cannot encode a secret
    */
   void store(ConnectorCredentialsContext context, Map<String, String> values) throws ConnectorCredentialsException;

   /**
    * The stored configuration, secrets in clear.
    * <p>
    * <b>Never null</b>: an empty map when nothing is stored, so a caller reads keys
    * without guarding, and a missing key means a missing value rather than a missing
    * configuration.
    *
    * @param context the connector and provider to read for
    * @return the stored values, possibly empty
    * @throws ConnectorCredentialsException when a stored secret cannot be decoded
    */
   Map<String, String> readDecrypted(ConnectorCredentialsContext context) throws ConnectorCredentialsException;

   /**
    * The same, with every secret field left out. <b>Never null</b>, like the above.
    *
    * @param context the connector and provider to read for
    * @return the stored non-secret values, possibly empty
    */
   Map<String, String> readWithoutSecrets(ConnectorCredentialsContext context);

   /**
    * Removes a connector's configuration for the context's provider.
    * <p>
    * Entitlement is the caller's, as for
    * {@link #store(ConnectorCredentialsContext, Map)}.
    *
    * @param context the connector and provider to remove for
    */
   void delete(ConnectorCredentialsContext context);

}
