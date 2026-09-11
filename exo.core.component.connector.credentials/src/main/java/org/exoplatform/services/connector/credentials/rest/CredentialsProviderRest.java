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

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.rest.util.EntityBuilder;
import org.exoplatform.services.connector.credentials.rest.model.ConnectorCredentialsProviderModel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * What an administrator's connector screen reads to offer the choice of a credentials
 * provider and, once one is chosen, to render its configuration form.
 * <p>
 * It lives in the contract's own module rather than in a connector's: the list is the
 * same for every connector kind, and duplicating it in email-connector and
 * caldav-integration would be two screens to reopen at the next provider. Served by
 * commons-exo-extension, the WAR whose Spring context already hosts this module's beans.
 * <p>
 * Administrators only, and read-only: it exposes the shape of a configuration, never a
 * configured value - no technical account login, and above all no secret.
 */
@RestController
@RequestMapping("/credentials-providers")
public class CredentialsProviderRest {

   private final ConnectorCredentialsService connectorCredentialsService;

   @Autowired
   public CredentialsProviderRest(ConnectorCredentialsService connectorCredentialsService) {
      this.connectorCredentialsService = connectorCredentialsService;
   }

   @GetMapping
   @Secured("administrators")
   @Operation(summary = "Lists the credentials providers a connector may select, with the fields each one needs",
              method = "GET",
              description = "Answers every announced provider, ordered by name, with the configuration fields an administrator must fill in for it. Describes the shape only - no configured value and no secret is ever returned here.")
   @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
       @ApiResponse(responseCode = "403", description = "Forbidden"), })
   public List<ConnectorCredentialsProviderModel> getProviders() {
      return connectorCredentialsService.getProviders().stream().map(EntityBuilder::toProviderModel).toList();
   }

}
