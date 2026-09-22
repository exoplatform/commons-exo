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

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;

/**
 * The managed mode's settings, in the platform's {@code SettingService}: global
 * context, a scope of its own, one key per kind and per fact.
 * <p>
 * <b>A dedicated scope</b> rather than the provider configuration's: the two are
 * distinct things an administrator may want to purge independently - the technical
 * accounts of the connectors on one side, which connector the instance imposes on the
 * other.
 * <p>
 * {@code @Component}, not {@code @Service}: nothing outside this module reads the
 * storage directly, the add-ons go through {@link ManagedConnectorService}.
 */
@Component
public class SettingManagedConnectorStorage implements ManagedConnectorStorage {

   /** The scope every managed-mode setting lives under. */
   static final Scope           SCOPE              = Scope.APPLICATION.id("managedConnector");

   /** The key of a kind's designated connector, suffixing the kind. */
   static final String          DESIGNATION_SUFFIX = "/designatedConnector";

   /** The key of a kind's excluded groups, a JSON array, suffixing the kind. */
   static final String          EXCLUSIONS_SUFFIX  = "/excludedGroups";

   private static final Log     LOG                = ExoLogger.getLogger(SettingManagedConnectorStorage.class);

   private final SettingService settingService;

   private final ObjectMapper   mapper             = new ObjectMapper();

   public SettingManagedConnectorStorage(SettingService settingService) {
      this.settingService = settingService;
   }

   @Override
   public Long readDesignation(String kind) {
      SettingValue<?> stored = settingService.get(Context.GLOBAL, SCOPE, designationKey(kind));
      if (stored == null || stored.getValue() == null) {
         return null;
      }
      try {
         return Long.valueOf(String.valueOf(stored.getValue()));
      } catch (NumberFormatException e) {
         // A value nobody can read as an id is a designation nobody can act on. Treated as
         // absent rather than thrown: this is read at every login, and a corrupted setting
         // must not make the login fail.
         LOG.warn("The designated connector of kind {} is not an id ({}); ignoring it", kind, stored.getValue());
         return null;
      }
   }

   @Override
   @ContainerTransactional
   public void storeDesignation(String kind, long connectorId) {
      settingService.set(Context.GLOBAL, SCOPE, designationKey(kind), SettingValue.create(String.valueOf(connectorId)));
   }

   @Override
   @ContainerTransactional
   public void removeDesignation(String kind) {
      settingService.remove(Context.GLOBAL, SCOPE, designationKey(kind));
   }

   @Override
   public List<String> readExclusions(String kind) {
      SettingValue<?> stored = settingService.get(Context.GLOBAL, SCOPE, exclusionsKey(kind));
      if (stored == null || stored.getValue() == null) {
         return List.of();
      }
      try {
         return mapper.readValue(String.valueOf(stored.getValue()), new TypeReference<List<String>>() {
         });
      } catch (JsonProcessingException e) {
         // Excluding nobody is the safe reading of a list nobody can parse: the
         // designation still applies, and the WARN tells an administrator what to fix.
         // This is read at every login, and a corrupted setting must not fail it.
         LOG.warn("The excluded groups of kind {} are not a readable list; excluding nobody", kind, e);
         return List.of();
      }
   }

   @Override
   @ContainerTransactional
   public void storeExclusions(String kind, List<String> groupIds) {
      try {
         settingService.set(Context.GLOBAL, SCOPE, exclusionsKey(kind), SettingValue.create(mapper.writeValueAsString(groupIds)));
      } catch (JsonProcessingException e) {
         // Jackson cannot fail writing a list of strings; the checked signature says otherwise.
         throw new IllegalStateException(e); // NOSONAR
      }
   }

   /**
    * The key of a kind's excluded groups.
    *
    * @param kind the connector kind
    * @return the setting key
    */
   private String exclusionsKey(String kind) {
      return kind + EXCLUSIONS_SUFFIX;
   }

   /**
    * The key of a kind's designation. The kind comes first so that everything about
    * one kind sits together, and nothing about one kind can touch another's.
    *
    * @param kind the connector kind
    * @return the setting key
    */
   private String designationKey(String kind) {
      return kind + DESIGNATION_SUFFIX;
   }
}
