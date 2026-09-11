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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A descriptor is read from a provider bean, which is a singleton: every request
 * shares the very same field objects. So the options list must be beyond the reach of
 * whoever built it and of whoever reads it - a mutable one would let any caller
 * rewrite what an administrator is offered, for every user of the platform.
 */
public class ConnectorCredentialsConfigFieldTest {

   private ConnectorCredentialsConfigField choiceOf(List<String> options) {
      return new ConnectorCredentialsConfigField("targetLoginField",
                                                 ConnectorCredentialsConfigFieldType.CHOICE,
                                                 "label.targetLoginField",
                                                 "help.targetLoginField",
                                                 true,
                                                 options);
   }

   @Test
   void theOptionsAreCopiedAwayFromTheCaller() {
      List<String> declared = new ArrayList<>(List.of("username", "email"));
      ConnectorCredentialsConfigField field = choiceOf(declared);

      declared.add("smuggled");

      assertEquals(List.of("username", "email"),
                   field.getOptions(),
                   "the list the provider passed is copied, so mutating it afterwards changes nothing");
   }

   @Test
   void theOptionsCannotBeRewrittenByAReader() {
      ConnectorCredentialsConfigField field = choiceOf(List.of("username", "email"));

      assertThrows(UnsupportedOperationException.class,
                   () -> field.getOptions().add("smuggled"),
                   "a reader of the descriptor cannot change what the next reader sees");
   }

}
