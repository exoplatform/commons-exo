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
package org.exoplatform.services.connector.credentials.rest.model;

import java.util.List;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;

/**
 * One provider as the administration screen needs it: the name to store on the
 * connector, whether choosing it obliges the user to act, and the fields to render.
 * <p>
 * The field descriptors travel as they are, rather than through a parallel REST type.
 * They are already flat, immutable transport objects with no behaviour and no entity
 * behind them - a second identical class would be duplication whose only effect is one
 * more place to forget a field. Read-only in this direction: nothing deserializes into
 * them, so the absence of a no-arg constructor is not a gap.
 */
public class ConnectorCredentialsProviderModel {

   private final String                                name;

   private final boolean                               requiresUserAction;

   private final List<ConnectorCredentialsConfigField> fields;

   public ConnectorCredentialsProviderModel(String name,
                                            boolean requiresUserAction,
                                            List<ConnectorCredentialsConfigField> fields) {
      this.name = name;
      this.requiresUserAction = requiresUserAction;
      this.fields = fields == null ? List.of() : List.copyOf(fields);
   }

   public String getName() {
      return name;
   }

   public boolean isRequiresUserAction() {
      return requiresUserAction;
   }

   public List<ConnectorCredentialsConfigField> getFields() {
      return fields;
   }

}
