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

import org.apache.commons.lang3.StringUtils;

import org.springframework.stereotype.Service;

import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.Identity;

/**
 * The managed mode's one question: for a connector kind and a user, which connector
 * is designated for them - or none.
 * <p>
 * Lives beside the credentials contract rather than inside each connector add-on,
 * because the rule is the same for calendars and mailboxes and its eligibility
 * criterion - only a provider that asks the user for nothing may be designated - is a
 * property of the contract itself. Both add-ons ask; neither reimplements.
 * <p>
 * <b>What it does not do</b>: connect anyone, or know whether a user already has a
 * configuration. Attaching is the add-on's own act, and the add-on checks first that
 * the user has nothing configured for this kind - commons-exo does not see the
 * add-ons' user settings.
 * <p>
 * The kind is an open string ({@code "email"}, {@code "caldav"}, whatever a future
 * add-on declares), never an enumeration: the contract is an open list, and closing it
 * here would make every new add-on a change to commons-exo.
 * <p>
 * Annotated {@code @Service} rather than {@code @Component} for the reason the rest of
 * this module carries: the callers live in other WARs, and only {@code @Service} makes
 * a bean visible across the Spring contexts the kernel bridge merges.
 */
@Service
public class ManagedConnectorService {

   /** Message codes, the way the REST layer turns an IllegalArgumentException into a 400. */
   public static final String                KIND_REQUIRED          = "managedConnector.kind.required";

   public static final String                USER_REQUIRED          = "managedConnector.user.required";

   public static final String                PROVIDER_UNKNOWN       = "managedConnector.provider.unknown";

   public static final String                PROVIDER_ASKS_THE_USER = "managedConnector.provider.asksTheUser";

   /** What a write by someone who is not an administrator is refused with. */
   public static final String                ADMINISTRATOR_REQUIRED = "managedConnector.administrator.required";

   private static final Log                  LOG                    = ExoLogger.getLogger(ManagedConnectorService.class);

   private final ManagedConnectorStorage      storage;

   private final ConnectorCredentialsService  connectorCredentialsService;

   private final UserACL                      userAcl;

   public ManagedConnectorService(ManagedConnectorStorage storage,
                                  ConnectorCredentialsService connectorCredentialsService,
                                  UserACL userAcl) {
      this.storage = storage;
      this.connectorCredentialsService = connectorCredentialsService;
      this.userAcl = userAcl;
   }

   /**
    * The populations excluded from a kind's designation.
    *
    * @param kind the connector kind
    * @return the excluded group ids, empty when none
    * @throws IllegalArgumentException when the kind is blank
    */
   public List<String> exclusionsOf(String kind) {
      requireKind(kind);
      return storage.readExclusions(kind);
   }

   /**
    * Designates the connector of a kind for the whole instance, minus the given
    * populations - one write path, so that nothing is stored when anything is refused.
    * <p>
    * Three checks, in this order, before anything is written. The caller must be an
    * administrator: these writes are published to every WAR by the kernel bridge, and
    * the REST {@code @Secured} of one host is not a contract for the next caller. The
    * provider must ask the user for nothing - <b>the one thing commons-exo can check
    * about the connector</b>: Personal cannot be designated, because it would produce an
    * instance connector nobody can connect through. Whether the connector id exists, is
    * active, and really carries this provider is the add-on's knowledge, checked before
    * it calls here.
    * <p>
    * Then the exclusions are stored <i>before</i> the designation: a login between the
    * two writes must not attach a user the administrator is excluding in the same
    * breath. With the checks ahead of both writes, a refusal leaves whatever was in
    * force exactly as it was - an earlier shape, exclusions written first and the
    * eligibility checked in a second call, left the old designation with the new
    * exclusions when the second call refused.
    * <p>
    * The excluded ids are normalised - blanks dropped, duplicates folded - because what
    * is stored is what will be compared to a user's memberships at every login.
    *
    * @param kind the connector kind
    * @param connectorId the connector to designate, as the add-on identifies it
    * @param providerName the provider that connector is configured with
    * @param excludedGroups the eXo group ids whose members are not attached, null for none
    * @param username the eXo login of the caller
    * @throws IllegalAccessException when the caller is not an administrator
    * @throws IllegalArgumentException carrying a message code when the kind or the
    *           caller is blank, the provider is unknown, or the provider asks the user
    *           for something
    */
   public void designate(String kind,
                         long connectorId,
                         String providerName,
                         List<String> excludedGroups,
                         String username) throws IllegalAccessException {
      requireKind(kind);
      requireAdministrator(username);
      requireEligible(providerName);
      storage.storeExclusions(kind, normalise(excludedGroups));
      storage.storeDesignation(kind, connectorId);
   }

   /**
    * Forgets the designation of a kind, and the exclusions that qualified it: off
    * leaves nothing behind, a list of excluded groups with no designation to be
    * excluded from being a state nobody can read on a screen. Its users keep whatever
    * connection they have; nobody new is attached.
    *
    * @param kind the connector kind
    * @param username the eXo login of the caller
    * @throws IllegalAccessException when the caller is not an administrator
    * @throws IllegalArgumentException when the kind or the caller is blank
    */
   public void clearDesignation(String kind, String username) throws IllegalAccessException {
      requireKind(kind);
      requireAdministrator(username);
      storage.removeDesignation(kind);
      storage.storeExclusions(kind, List.of());
   }

   /**
    * Refuses a provider that cannot be designated, as the contract answers it: unknown,
    * or asking the user for something. Public because the add-ons also need it on the
    * write that changes a designated connector's provider - the designation would
    * otherwise end up naming a provider this very check refuses.
    *
    * @param providerName the provider to check
    * @throws IllegalArgumentException carrying {@link #PROVIDER_UNKNOWN} or
    *           {@link #PROVIDER_ASKS_THE_USER}
    */
   public void requireEligible(String providerName) {
      if (asksTheUser(providerName)) {
         throw new IllegalArgumentException(PROVIDER_ASKS_THE_USER);
      }
   }

   /**
    * The connector designated for a kind - the administrator's fact, with no user in
    * the question.
    *
    * @param kind the connector kind
    * @return the designated connector id, or null when none is
    * @throws IllegalArgumentException when the kind is blank
    */
   public Long designationOf(String kind) {
      requireKind(kind);
      return storage.readDesignation(kind);
   }

   /**
    * The connector this user is to be attached to for this kind, or null when the
    * managed mode does not apply to them.
    * <p>
    * Answered as an id or null, on purpose. An earlier design wrapped it in an
    * always-non-null object because the outcome might have been <i>stored</i>, where an
    * absent field cannot be told from a decision. It is recomputed on every call
    * instead, so null has exactly one meaning: nothing applies to this user - no
    * designation for this kind, or a population the administrator excluded. The caller
    * does not need to know which; in both cases it does nothing.
    *
    * @param kind the connector kind, as the add-on declares it
    * @param username the eXo login to resolve for
    * @return the designated connector id, or null
    * @throws IllegalArgumentException when the kind or the user is blank - a programming
    *           error, not a state to resolve
    */
   public Long designatedConnectorFor(String kind, String username) {
      requireKind(kind);
      if (StringUtils.isBlank(username)) {
         throw new IllegalArgumentException(USER_REQUIRED);
      }
      Long designated = storage.readDesignation(kind);
      if (designated == null) {
         return null;
      }
      List<String> excluded = storage.readExclusions(kind);
      // The identity is asked only when there is something to exclude from and
      // someone to exclude: this runs at every login, for every kind.
      if (excluded.isEmpty()) {
         return designated;
      }
      return isMemberOfAny(username, excluded) ? null : designated;
   }

   /**
    * Whether the user belongs to one of the groups, by exact group id - a member of a
    * sub-group of an excluded group is <b>not</b> excluded, which is how eXo membership
    * itself works.
    * <p>
    * The groups are read from the same {@link Identity} the platform's own permission
    * checks use, through {@link UserACL#getUserIdentity(String)}: cached per user, and
    * consistent with what every other ACL decision sees. A user the platform cannot
    * identify counts as "excluded": attaching someone whose exclusion could not be
    * checked would override an administrator's decision, while leaving them for the
    * next login costs one login.
    *
    * @param username the eXo login
    * @param groupIds the excluded groups
    * @return true when the user is in one of them, or when nobody can tell
    */
   private boolean isMemberOfAny(String username, List<String> groupIds) {
      Identity identity = userAcl.getUserIdentity(username);
      if (identity == null) {
         LOG.warn("No identity for user {} to apply the managed-mode exclusions; not attaching them", username);
         return true;
      }
      return identity.getGroups().stream().anyMatch(groupIds::contains);
   }

   /**
    * Whether a provider asks the user for anything, as the contract answers it.
    *
    * @param providerName the provider to ask about
    * @return true when the user has something to supply
    * @throws IllegalArgumentException carrying a message code when no provider of that
    *           name is registered
    */
   private boolean asksTheUser(String providerName) {
      if (StringUtils.isBlank(providerName)) {
         throw new IllegalArgumentException(PROVIDER_UNKNOWN);
      }
      try {
         return connectorCredentialsService.requiresUserAction(providerName);
      } catch (ConnectorCredentialsException e) {
         // The contract's own exception names the provider already; what the
         // administration screen needs is a code it can translate.
         throw new IllegalArgumentException(PROVIDER_UNKNOWN + ": " + providerName, e);
      }
   }

   /**
    * Refuses a caller who is not an administrator. The identity is the platform's own,
    * the same the permission checks everywhere else read.
    *
    * @param username the eXo login of the caller
    * @throws IllegalAccessException when the caller is not an administrator
    * @throws IllegalArgumentException when the caller is blank - a programming error
    */
   private void requireAdministrator(String username) throws IllegalAccessException {
      if (StringUtils.isBlank(username)) {
         throw new IllegalArgumentException(USER_REQUIRED);
      }
      Identity identity = userAcl.getUserIdentity(username);
      if (identity == null || !userAcl.isAdministrator(identity)) {
         throw new IllegalAccessException(ADMINISTRATOR_REQUIRED);
      }
   }

   /**
    * The excluded ids as they are stored: blanks dropped, trimmed, duplicates folded.
    *
    * @param groupIds what the administrator picked, null for none
    * @return the list to store
    */
   private List<String> normalise(List<String> groupIds) {
      return groupIds == null ? List.of()
                              : groupIds.stream()
                                        .filter(StringUtils::isNotBlank)
                                        .map(String::trim)
                                        .distinct()
                                        .toList();
   }

   /**
    * Refuses a blank kind, which is a programming error: the kind discriminates
    * everything this service holds, and answering "nothing" for a blank one would look
    * like a decision to the caller.
    *
    * @param kind the connector kind to check
    */
   private void requireKind(String kind) {
      if (StringUtils.isBlank(kind)) {
         throw new IllegalArgumentException(KIND_REQUIRED);
      }
   }
}
