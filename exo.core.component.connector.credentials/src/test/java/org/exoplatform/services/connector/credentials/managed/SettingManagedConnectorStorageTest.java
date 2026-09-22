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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;

/**
 * Where the designation lives: one setting per kind, under a scope of its own, in
 * the global context. A dedicated scope rather than the provider configuration's, so
 * that an administrator can purge one without the other.
 */
@ExtendWith(MockitoExtension.class)
class SettingManagedConnectorStorageTest {

   private static final String               SCOPE_ID = "managedConnector";

   private MockedStatic<ExoContainerContext> containerContext;

   private SettingService                    settingService;

   private SettingManagedConnectorStorage    storage;

   /**
    * States a container the woven aspect can work with - the same discipline as
    * SettingProviderConfigStorageTest: the writes are @ContainerTransactional and the
    * aspect IS woven in this module, so left to itself it would boot a real portal.
    */
   @BeforeEach
   void setUp() {
      containerContext = mockStatic(ExoContainerContext.class);
      containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(mock(ExoContainer.class));
      settingService = mock(SettingService.class);
      storage = new SettingManagedConnectorStorage(settingService);
   }

   @AfterEach
   void forgetTheContainer() {
      containerContext.close();
   }

   /** The exact key and scope a designation is written under. */
   @Test
   void storesTheDesignationUnderItsKindInTheDedicatedScope() {
      storage.storeDesignation("email", 7L);

      ArgumentCaptor<Scope> scope = ArgumentCaptor.forClass(Scope.class);
      ArgumentCaptor<SettingValue> value = ArgumentCaptor.forClass(SettingValue.class);
      verify(settingService).set(eq(Context.GLOBAL), scope.capture(), eq("email/designatedConnector"), value.capture());
      assertEquals(SCOPE_ID, scope.getValue().getId());
      assertEquals("7", String.valueOf(value.getValue().getValue()));
   }

   /** Nothing stored, nothing designated - null, not a default. */
   @Test
   void readsNullWhenNothingIsStored() {
      when(settingService.get(eq(Context.GLOBAL), any(Scope.class), eq("email/designatedConnector"))).thenReturn(null);

      assertNull(storage.readDesignation("email"));
   }

   /** What was written is what is read back, as a number. */
   @Test
   void readsTheStoredDesignation() {
      doReturn(SettingValue.create("7")).when(settingService).get(eq(Context.GLOBAL), any(Scope.class), eq("email/designatedConnector"));

      assertEquals(7L, storage.readDesignation("email"));
   }

   /** A value the platform cannot read as an id is treated as absent, never as a crash. */
   @Test
   void readsNullWhenTheStoredValueIsNotAnId() {
      doReturn(SettingValue.create("not-a-number")).when(settingService).get(eq(Context.GLOBAL), any(Scope.class), eq("email/designatedConnector"));

      assertNull(storage.readDesignation("email"));
   }

   /** Removing deletes the very key that was written. */
   @Test
   void removesTheDesignationKey() {
      storage.removeDesignation("email");

      ArgumentCaptor<Scope> scope = ArgumentCaptor.forClass(Scope.class);
      verify(settingService).remove(eq(Context.GLOBAL), scope.capture(), eq("email/designatedConnector"));
      assertEquals(SCOPE_ID, scope.getValue().getId());
   }

   /** The kind is in the key: a caldav designation never lands on email's. */
   @Test
   void keepsTheKindsApart() {
      storage.storeDesignation("caldav", 3L);

      verify(settingService).set(eq(Context.GLOBAL), any(Scope.class), eq("caldav/designatedConnector"), any(SettingValue.class));
   }

   // ------------------------------------------------------------ exclusions

   /** The excluded groups of a kind, as one JSON array under their own key. */
   @Test
   void storesTheExcludedGroupsUnderTheirKind() {
      storage.storeExclusions("email", List.of("/externals", "/contractors"));

      ArgumentCaptor<SettingValue> value = ArgumentCaptor.forClass(SettingValue.class);
      verify(settingService).set(eq(Context.GLOBAL), any(Scope.class), eq("email/excludedGroups"), value.capture());
      assertEquals("[\"/externals\",\"/contractors\"]", String.valueOf(value.getValue().getValue()));
   }

   /** Nothing stored is an empty list - never null, the caller iterates it. */
   @Test
   void readsNoExclusionsWhenNothingIsStored() {
      when(settingService.get(eq(Context.GLOBAL), any(Scope.class), eq("email/excludedGroups"))).thenReturn(null);

      assertEquals(List.of(), storage.readExclusions("email"));
   }

   /** What was written is what is read back. */
   @Test
   void readsTheStoredExclusions() {
      doReturn(SettingValue.create("[\"/externals\",\"/contractors\"]")).when(settingService)
            .get(eq(Context.GLOBAL), any(Scope.class), eq("email/excludedGroups"));

      assertEquals(List.of("/externals", "/contractors"), storage.readExclusions("email"));
   }

   /** A value nobody can read as a list excludes nobody, and does not fail the login reading it. */
   @Test
   void readsNoExclusionsWhenTheStoredValueIsNotAList() {
      doReturn(SettingValue.create("not json")).when(settingService)
            .get(eq(Context.GLOBAL), any(Scope.class), eq("email/excludedGroups"));

      assertEquals(List.of(), storage.readExclusions("email"));
   }
}