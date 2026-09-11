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
package org.exoplatform.services.connector.credentials.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigFieldType;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.rest.model.ConnectorCredentialsProviderModel;

/**
 * What the administration screen reads to build its provider selector and, for the
 * chosen provider, its configuration form. Unit-level: the mapping is what this layer
 * owns - the route and the administrators-only guard are declarative and only a run
 * exercises them.
 */
public class CredentialsProviderRestTest {

   private ConnectorCredentialsProvider provider(String name,
                                                 boolean requiresUserAction,
                                                 List<ConnectorCredentialsConfigField> fields) {
      ConnectorCredentialsProvider provider = mock(ConnectorCredentialsProvider.class);
      when(provider.getName()).thenReturn(name);
      when(provider.requiresUserAction()).thenReturn(requiresUserAction);
      when(provider.getConfigurationFields()).thenReturn(fields);
      return provider;
   }

   private ConnectorCredentialsService serviceWith(ConnectorCredentialsProvider... providers) {
      ConnectorCredentialsService service = new ConnectorCredentialsService();
      for (ConnectorCredentialsProvider provider : providers) {
         when(provider.getSupportedChannels()).thenReturn(EnumSet.allOf(ConnectorCredentialsChannel.class));
         service.register(provider);
      }
      return service;
   }

   @Test
   void aProviderWithNoConfigurationIsAnsweredWithAnEmptyFieldList() {
      CredentialsProviderRest rest = new CredentialsProviderRest(serviceWith(provider("personal", true, List.of())));

      List<ConnectorCredentialsProviderModel> answered = rest.getProviders();

      assertEquals(1, answered.size());
      assertEquals("personal", answered.get(0).getName());
      assertTrue(answered.get(0).isRequiresUserAction(),
                 "the selector greys out a default-connector choice on a provider that needs the user");
      assertTrue(answered.get(0).getFields().isEmpty(), "nothing for an administrator to fill in");
   }

   @Test
   void everyDescribedFieldTravelsToTheUi() {
      ConnectorCredentialsConfigField target = new ConnectorCredentialsConfigField("targetLoginField",
                                                                                   ConnectorCredentialsConfigFieldType.CHOICE,
                                                                                   "label.targetLoginField",
                                                                                   "help.targetLoginField",
                                                                                   true,
                                                                                   List.of("username", "email"));
      CredentialsProviderRest rest =
                                   new CredentialsProviderRest(serviceWith(provider("bluemind-sudo", false, List.of(target))));

      ConnectorCredentialsConfigField answered = rest.getProviders().get(0).getFields().get(0);

      assertEquals("targetLoginField", answered.getKey());
      assertEquals(ConnectorCredentialsConfigFieldType.CHOICE, answered.getType());
      assertEquals("help.targetLoginField", answered.getHelpKey(), "the help text the vendor's API key note goes in");
      assertEquals(List.of("username", "email"), answered.getOptions(), "a closed list stays closed across the wire");
   }

}
