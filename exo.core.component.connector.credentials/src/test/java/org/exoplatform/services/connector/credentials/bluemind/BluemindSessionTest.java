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
package org.exoplatform.services.connector.credentials.bluemind;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BluemindSessionTest {

  @Test
  void toStringNamesTheAccountAndNeverTheSessionKey() {
    BluemindSession session = new BluemindSession("0f1e2d3c-secret-session-key", "exo.service@internal");

    String rendered = session.toString();

    assertFalse(rendered.contains("0f1e2d3c-secret-session-key"), rendered);
    assertTrue(rendered.contains("exo.service@internal"), rendered);
  }
}
