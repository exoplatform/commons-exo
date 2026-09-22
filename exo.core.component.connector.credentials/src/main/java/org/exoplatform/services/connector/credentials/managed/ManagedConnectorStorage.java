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
package org.exoplatform.services.connector.credentials.managed;

import java.util.List;

/**
 * Where the managed mode keeps what the administrator set, per connector kind.
 * <p>
 * An interface so that {@link ManagedConnectorService} is tested against what it
 * decides rather than against how settings are serialised, and so that the storage
 * can be exercised alone against the platform's {@code SettingService}.
 */
public interface ManagedConnectorStorage {

   /**
    * The connector designated for a kind.
    *
    * @param kind the connector kind
    * @return the designated connector id, or null when none is stored
    */
   Long readDesignation(String kind);

   /**
    * Records the connector designated for a kind, replacing any previous one.
    *
    * @param kind the connector kind
    * @param connectorId the connector to designate
    */
   void storeDesignation(String kind, long connectorId);

   /**
    * Forgets the designation of a kind.
    *
    * @param kind the connector kind
    */
   void removeDesignation(String kind);

   /**
    * The groups excluded from a kind's designation.
    *
    * @param kind the connector kind
    * @return the excluded group ids, empty when none are stored - never null, the
    *         caller iterates it at every login
    */
   List<String> readExclusions(String kind);

   /**
    * Records the groups excluded from a kind's designation, replacing the previous list.
    *
    * @param kind the connector kind
    * @param groupIds the group ids to exclude
    */
   void storeExclusions(String kind, List<String> groupIds);
}
