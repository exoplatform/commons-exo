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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.MembershipEntry;

/**
 * The managed mode's one question: for this connector kind and this user, which
 * connector is designated - or none.
 * <p>
 * Answered as an id or null, on purpose. The earlier design wrapped it in an
 * always-non-null object because the outcome might have been <i>stored</i>, where an
 * absent field is ambiguous. It is recomputed on every call instead, so a synchronous
 * return has exactly one meaning when it is null: nothing applies to this user.
 */
@ExtendWith(MockitoExtension.class)
class ManagedConnectorServiceTest {

   private ManagedConnectorService     service;

   private ConnectorCredentialsService credentials;

   /** An in-memory stand-in for the storage: what was designated, per kind. */
   private final Map<String, Long>     designations = new HashMap<>();

   private ManagedConnectorStorage     storage;

   private UserACL                     userAcl;

   private static final Identity       ROOT = new Identity("root", List.of(new MembershipEntry("/platform/administrators", "*")));

   /** What was excluded, per kind - the storage stand-in's other half. */
   private final Map<String, List<String>> exclusions = new HashMap<>();

   @BeforeEach
   void setUp() throws Exception {
      userAcl = mock(UserACL.class);
      // root is the administrator every write test acts as; alice is nobody special.
      lenient().when(userAcl.getUserIdentity("root")).thenReturn(ROOT);
      lenient().when(userAcl.isAdministrator(ROOT)).thenReturn(true);
      credentials = mock(ConnectorCredentialsService.class);
      // Shared fixture, lenient: each test uses the part of it that its scenario reaches.
      lenient().when(credentials.requiresUserAction("personal")).thenReturn(true);
      lenient().when(credentials.requiresUserAction("bluemind-sudo")).thenReturn(false);
      lenient().when(credentials.requiresUserAction("nobody")).thenThrow(new ConnectorCredentialsException("no such provider"));
      storage = mock(ManagedConnectorStorage.class);
      lenient().when(storage.readDesignation(anyString())).thenAnswer(call -> designations.get(call.getArgument(0, String.class)));
      lenient().when(storage.readExclusions(anyString()))
               .thenAnswer(call -> exclusions.getOrDefault(call.getArgument(0, String.class), List.of()));
      service = new ManagedConnectorService(storage, credentials, userAcl);
   }

   // ------------------------------------------------------------ the read

   /** Nothing designated, nobody attached - whatever the kind, whoever the user. */
   @Test
   void namesNoConnectorWhileNothingIsDesignated() {
      assertNull(service.designatedConnectorFor("email", "alice"));
      assertNull(service.designatedConnectorFor("caldav", "alice"));
      assertNull(service.designatedConnectorFor("carddav", "bob"));
   }

   /**
    * The kind is the discriminant of everything the service holds. A blank one is a
    * programming error, not a state to resolve - it is refused rather than answered
    * "nothing", which a caller could mistake for a decision.
    */
   @Test
   void refusesABlankKind() {
      assertCode("managedConnector.kind.required", () -> service.designatedConnectorFor(null, "alice"));
      assertCode("managedConnector.kind.required", () -> service.designatedConnectorFor("  ", "alice"));
   }

   /** The same for the user: there is nobody to resolve for. */
   @Test
   void refusesABlankUsername() {
      assertCode("managedConnector.user.required", () -> service.designatedConnectorFor("email", null));
      assertCode("managedConnector.user.required", () -> service.designatedConnectorFor("email", ""));
   }

   // ------------------------------------------------------------ designating

   /**
    * Designating is what makes the read answer. Any user of that kind is then named the
    * connector - the exclusions that narrow this come in their own step.
    */
   @Test
   void namesTheDesignatedConnectorToEveryUserOfThatKind() throws Exception {
      service.designate("email", 7L, "bluemind-sudo", List.of(), "root");
      designations.put("email", 7L);

      verify(storage).storeDesignation("email", 7L);
      assertEquals(7L, service.designationOf("email"));
      assertEquals(7L, service.designatedConnectorFor("email", "alice"));
      assertEquals(7L, service.designatedConnectorFor("email", "bob"));
   }

   /**
    * <b>Only a provider that asks the user for nothing may be designated.</b> Personal
    * would produce an instance connector nobody can connect through. Refused before
    * anything is written - neither the designation nor the exclusions: an earlier shape
    * wrote the exclusions first and left the old designation with the new list when the
    * provider was refused.
    */
   @Test
   void refusesToDesignateAProviderThatAsksTheUser() {
      assertCode("managedConnector.provider.asksTheUser",
                 () -> service.designate("email", 7L, "personal", List.of("/externals"), "root"));

      verify(storage, never()).storeDesignation(anyString(), anyLong());
      verify(storage, never()).storeExclusions(anyString(), any());
   }

   /** A provider nobody registered is refused the same way, and the contract's own exception does not leak. */
   @Test
   void refusesToDesignateAnUnknownProvider() {
      assertCode("managedConnector.provider.unknown", () -> service.designate("email", 7L, "nobody", List.of(), "root"));
      assertCode("managedConnector.provider.unknown", () -> service.designate("email", 7L, " ", List.of(), "root"));
      assertCode("managedConnector.provider.unknown", () -> service.requireEligible("nobody"));
      assertCode("managedConnector.provider.asksTheUser", () -> service.requireEligible("personal"));

      verify(storage, never()).storeDesignation(anyString(), anyLong());
      verify(storage, never()).storeExclusions(anyString(), any());
   }

   /**
    * <b>Administrators only.</b> These writes are published to every WAR; the REST
    * annotation of one host is no contract for the next caller. A user who is not an
    * administrator - or whom the platform cannot identify - writes nothing.
    */
   @Test
   void refusesWritesFromANonAdministrator() {
      when(userAcl.getUserIdentity("alice")).thenReturn(new Identity("alice", List.of()));

      IllegalAccessException designating = assertThrows(IllegalAccessException.class,
                                                        () -> service.designate("email", 7L, "bluemind-sudo", List.of(), "alice"));
      IllegalAccessException clearing = assertThrows(IllegalAccessException.class,
                                                     () -> service.clearDesignation("email", "alice"));
      assertThrows(IllegalAccessException.class, () -> service.designate("email", 7L, "bluemind-sudo", List.of(), "ghost"));

      assertEquals("managedConnector.administrator.required", designating.getMessage());
      assertEquals("managedConnector.administrator.required", clearing.getMessage());
      verify(storage, never()).storeDesignation(anyString(), anyLong());
      verify(storage, never()).storeExclusions(anyString(), any());
      verify(storage, never()).removeDesignation(anyString());
   }

   /** A blank caller is a programming error, not an access refusal. */
   @Test
   void refusesABlankCallerOnWrites() {
      assertCode("managedConnector.user.required", () -> service.designate("email", 7L, "bluemind-sudo", List.of(), " "));
      assertCode("managedConnector.user.required", () -> service.clearDesignation("email", null));
   }

   /**
    * Designating stores the exclusions, normalised (blanks dropped, duplicates folded),
    * and then the designation - in that order: a login between the two writes must not
    * attach someone being excluded in the same breath.
    */
   @Test
   void designatingStoresTheExclusionsThenTheDesignation() throws Exception {
      service.designate("email", 7L, "bluemind-sudo", List.of(" /externals ", "", "/externals", "/contractors"), "root");

      InOrder order = inOrder(storage);
      order.verify(storage).storeExclusions("email", List.of("/externals", "/contractors"));
      order.verify(storage).storeDesignation("email", 7L);
   }

   /** No exclusions given is none stored, never null. */
   @Test
   void designatingWithoutExclusionsStoresNone() throws Exception {
      service.designate("email", 7L, "bluemind-sudo", null, "root");

      verify(storage).storeExclusions("email", List.of());
      verify(storage).storeDesignation("email", 7L);
   }

   /** Clearing puts the kind back to "nothing applies" - designation and exclusions alike. */
   @Test
   void clearsADesignationAndItsExclusions() throws Exception {
      designations.put("email", 7L);
      exclusions.put("email", List.of("/externals"));

      service.clearDesignation("email", "root");
      designations.remove("email");

      verify(storage).removeDesignation("email");
      verify(storage).storeExclusions("email", List.of());
      assertNull(service.designatedConnectorFor("email", "alice"));
   }

   /** The kind guard holds on the writes too. */
   @Test
   void refusesToDesignateOrClearABlankKind() {
      assertCode("managedConnector.kind.required", () -> service.designate(" ", 7L, "bluemind-sudo", List.of(), "root"));
      assertCode("managedConnector.kind.required", () -> service.clearDesignation(null, "root"));
      assertCode("managedConnector.kind.required", () -> service.designationOf(""));
   }

   // ------------------------------------------------------------ excluding

   /**
    * <b>The exception the administrator carves out.</b> A member of an excluded group
    * is not attached, whatever the designation says. Membership is read from the
    * user's {@link Identity}, group by group, exactly as the group id is stored - not
    * by subtree.
    */
   @Test
   void leavesOutAMemberOfAnExcludedGroup() {
      designations.put("email", 7L);
      exclusions.put("email", List.of("/externals"));
      givenGroupsOf("alice", "/platform/users", "/externals");

      assertNull(service.designatedConnectorFor("email", "alice"));
   }

   /** Everyone else is. */
   @Test
   void namesTheConnectorToAUserOutsideTheExcludedGroups() {
      designations.put("email", 7L);
      exclusions.put("email", List.of("/externals"));
      givenGroupsOf("bob", "/platform/users", "/developers");

      assertEquals(7L, service.designatedConnectorFor("email", "bob"));
   }

   /**
    * The identity is not asked for nothing. Without a designation there is nobody to
    * exclude from; without exclusions there is nothing to check - and this runs at every
    * login.
    */
   @Test
   void readsNoGroupsWhenThereIsNothingToExcludeFrom() {
      service.designatedConnectorFor("email", "alice");
      designations.put("caldav", 3L);
      assertEquals(3L, service.designatedConnectorFor("caldav", "alice"));

      verify(userAcl, never()).getUserIdentity(anyString());
   }

   /**
    * A user the platform cannot identify is left unattached. Attaching someone whose
    * exclusion could not be checked would override an administrator's decision; leaving
    * them for the next login costs one login.
    */
   @Test
   void namesNobodyWhenTheUserHasNoIdentity() {
      designations.put("email", 7L);
      exclusions.put("email", List.of("/externals"));
      when(userAcl.getUserIdentity("alice")).thenReturn(null);

      assertNull(service.designatedConnectorFor("email", "alice"));
   }

   /** What was excluded is what is read back. */
   @Test
   void readsTheExcludedGroups() {
      exclusions.put("email", List.of("/externals"));

      assertEquals(List.of("/externals"), service.exclusionsOf("email"));
      assertEquals(List.of(), service.exclusionsOf("caldav"));
   }

   /** The kind guard holds on the read too. */
   @Test
   void refusesToReadExclusionsOfABlankKind() {
      assertCode("managedConnector.kind.required", () -> service.exclusionsOf(null));
   }

   /** A real {@link Identity}, as the platform registers it at login: one entry per group. */
   private void givenGroupsOf(String username, String... groupIds) {
      List<MembershipEntry> entries = new ArrayList<>();
      for (String groupId : groupIds) {
         entries.add(new MembershipEntry(groupId, "member"));
      }
      when(userAcl.getUserIdentity(username)).thenReturn(new Identity(username, entries));
   }

   private void assertCode(String code, org.junit.jupiter.api.function.Executable call) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, call);
      assertTrue(refusal.getMessage().startsWith(code), refusal.getMessage());
   }
}
