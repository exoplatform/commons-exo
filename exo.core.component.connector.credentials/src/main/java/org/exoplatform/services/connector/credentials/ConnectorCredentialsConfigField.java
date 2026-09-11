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

import java.util.List;

/**
 * One field an administrator fills in on a connector that selected a given provider -
 * see {@link ConnectorCredentialsProvider#getConfigurationFields()}.
 * <p>
 * It carries what is needed to <i>render</i> the field and to <i>store</i> its value,
 * and nothing else: the label travels as an i18n key, never as text, because the
 * contract module ships no bundle and knows no language; the admin UI resolves the key
 * against its own. The options of a {@link ConnectorCredentialsConfigFieldType#CHOICE}
 * are the bare admitted values - their labels are derived by the UI as
 * {@code <labelKey>.option.<value>}, so a closed list costs no second descriptor type.
 * <p>
 * Instances are read from a provider bean, which is a <b>singleton</b>: the same field
 * object is shared by every request. Hence the defensive copy on the way in and the
 * unmodifiable view on the way out - a mutable options list would let any caller
 * rewrite what every administrator on the platform is offered.
 */
public class ConnectorCredentialsConfigField {

   private final String                              key;

   private final ConnectorCredentialsConfigFieldType type;

   private final String                              labelKey;

   private final String                              helpKey;

   private final boolean                             required;

   private final List<String>                        options;

   public ConnectorCredentialsConfigField(String key,
                                          ConnectorCredentialsConfigFieldType type,
                                          String labelKey,
                                          String helpKey,
                                          boolean required,
                                          List<String> options) {
      this.key = key;
      this.type = type;
      this.labelKey = labelKey;
      this.helpKey = helpKey;
      this.required = required;
      this.options = options == null ? List.of() : List.copyOf(options);
   }

   /**
    * The field's identifier: the key the admin form posts, the key the connector
    * relays in its opaque map, and the key under which the value is stored.
    */
   public String getKey() {
      return key;
   }

   /**
    * How the field is rendered, and - for {@link ConnectorCredentialsConfigFieldType#SECRET}
    * - the only marker telling the storage to encrypt the value.
    */
   public ConnectorCredentialsConfigFieldType getType() {
      return type;
   }

   /**
    * The i18n key of the field's label, resolved by the admin UI.
    */
   public String getLabelKey() {
      return labelKey;
   }

   /**
    * The i18n key of the field's help text, resolved by the admin UI. May be null
    * when the label says enough on its own.
    */
   public String getHelpKey() {
      return helpKey;
   }

   /**
    * Whether a value must be present. Read both by the UI, to mark the field, and by
    * the storage, which refuses a configuration missing it.
    */
   public boolean isRequired() {
      return required;
   }

   /**
    * The admitted values of a CHOICE field, in display order; empty for every other
    * type. Unmodifiable - see the class javadoc.
    */
   public List<String> getOptions() {
      return options;
   }

}
