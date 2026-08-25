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
 * Everything an {@link ConnectorCredentialsProvider} needs to produce material for one user
 * on one connector. The connector is identified only by id + a display name: the
 * provider must not need to know the connector's own entity type (email vs CalDAV vs
 * CardDAV) to do its job.
 * <p>
 * No contextual info (language, request headers...) is carried here beyond what
 * authentication itself requires - callers pass everything else as explicit method
 * parameters on their own services, per the platform's "no implicit context" rule.
 */
public class ConnectorCredentialsContext {

   private final long connectorId;

   private final String connectorCredentialsProviderName;

   private final String username;

   private final ConnectorCredentialsChannel channel;

   private final String connectorKind;

   public ConnectorCredentialsContext(long connectorId, String connectorCredentialsProviderName, String username,
                                       ConnectorCredentialsChannel channel, String connectorKind) {
      this.connectorId = connectorId;
      this.connectorCredentialsProviderName = connectorCredentialsProviderName;
      this.username = username;
      this.channel = channel;
      this.connectorKind = connectorKind;
   }

   /**
    * Id of the admin-configured connector (e.g. EmailConnectorEntity, CaldavServerEntity)
    * this authentication is being produced for.
    */
   public long getConnectorId() {
      return connectorId;
   }

   /**
    * Name of the {@link ConnectorCredentialsProvider} the connector is configured to use
    * (its {@link ConnectorCredentialsProvider#getName()}). Carried here so a provider
    * implementation shared by several connector kinds can tell them apart if it
    * ever needs to.
    */
   public String getConnectorCredentialsProviderName() {
      return connectorCredentialsProviderName;
   }

   /**
    * The eXo username the material is produced for - the identity the connector
    * ultimately acts on behalf of, whatever the provider does under the hood
    * (personal credentials, a technical account impersonating this user...).
    */
   public String getUsername() {
      return username;
   }

   /**
    * The channel the caller needs material for. Required because a provider may
    * support more than one channel (e.g. Personal supports both IMAP and SMTP with
    * the same underlying secret) - this is how it knows which one to tag the
    * produced material with.
    */
   public ConnectorCredentialsChannel getChannel() {
      return channel;
   }

   /**
    * Stable identifier of the connector family this context belongs to (e.g.
    * {@code "email"}, {@code "caldav"}) - not the admin-configured provider name.
    * A provider shared across connector kinds (e.g. the generic Personal provider)
    * uses this to find the per-connector-kind adapter that knows where that kind's
    * own personal credentials are stored.
    */
   public String getConnectorKind() {
      return connectorKind;
   }

}
