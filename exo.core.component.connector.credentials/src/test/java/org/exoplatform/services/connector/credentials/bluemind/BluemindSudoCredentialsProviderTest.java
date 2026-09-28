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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import org.exoplatform.services.connector.credentials.ConnectorCredentials;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigFieldType;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.ConnectorProviderConfigStorage;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;
import org.exoplatform.services.connector.credentials.MailConnectorCredentials;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserHandler;

/**
 * The provider that authenticates through a BlueMind technical account and a sudo.
 * <p>
 * This class covers what the provider <i>declares</i>: its name, the channels it
 * serves, that it needs nothing from the user, and the configuration it asks an
 * administrator for. Producing the material is another matter and another test.
 */
public class BluemindSudoCredentialsProviderTest {

   private static final String             ALICE = "alice";

   private ConnectorCredentialsService     service;

   private ConnectorProviderConfigStorage  configStorage;

   private OrganizationService             organizationService;

   private BluemindAuthClient bluemind;

   private UserHandler                     userHandler;

   private BluemindSudoCredentialsProvider provider;

   @BeforeEach
   void setUp() {
      service = new ConnectorCredentialsService();
      configStorage = mock(ConnectorProviderConfigStorage.class);
      organizationService = mock(OrganizationService.class);
      userHandler = mock(UserHandler.class);
      lenient().when(organizationService.getUserHandler()).thenReturn(userHandler);
      bluemind = mock(BluemindAuthClient.class);
      provider = new BluemindSudoCredentialsProvider(service, configStorage, organizationService, new BluemindSessionStorage(bluemind, 600, 10000));
      provider.register();
   }

   /** The context a connector hands over when it needs material for Alice. */
   private ConnectorCredentialsContext context() {
      return new ConnectorCredentialsContext(2L, BluemindSudoCredentialsProvider.NAME, ALICE,
                                             ConnectorCredentialsChannel.IMAP, "email");
   }

   /** The configuration as produce() reads it - the secret included. */
   private void givenTheTechnicalSecret() throws ConnectorCredentialsException {
      lenient().when(configStorage.readDecrypted(any()))
               .thenReturn(Map.of("apiUrl", "https://bm.example.com",
                                  "technicalLogin", "admin0@global.virt",
                                  "technicalSecret", "t0ps3cret",
                                  "targetLoginField", "email"));
   }

   /** The two calls BlueMind answers on the happy path. */
   private void givenBluemindServes(String target, String targetSid) throws ConnectorCredentialsException {
      lenient().when(bluemind.login("https://bm.example.com", "admin0@global.virt", "t0ps3cret"))
               .thenReturn(new BluemindSession("sid-tech", "admin0@19d43481671.internal"));
      lenient().when(bluemind.sudo("https://bm.example.com", "sid-tech", target))
               .thenReturn(new BluemindSession(targetSid, target + "-resolved"));
   }

   private ConnectorCredentialsContext context(ConnectorCredentialsChannel channel) {
      return new ConnectorCredentialsContext(2L, BluemindSudoCredentialsProvider.NAME, ALICE, channel, "email");
   }

   private PasswordAuthentication authenticate(Authenticator authenticator) throws Exception {
      Method method = Authenticator.class.getDeclaredMethod("getPasswordAuthentication");
      method.setAccessible(true); // NOSONAR - the only way to read what the mail layer will
      return (PasswordAuthentication) method.invoke(authenticator);
   }

   private void givenConfiguredWith(String targetLoginField) {
      lenient().when(configStorage.readWithoutSecrets(any()))
               .thenReturn(Map.of("apiUrl", "https://bm.example.com",
                                  "technicalLogin", "admin0@global.virt",
                                  "targetLoginField", targetLoginField));
   }

   private void givenEmailOf(String username, String email) throws Exception {
      User user = mock(User.class);
      lenient().when(user.getEmail()).thenReturn(email);
      lenient().when(userHandler.findUserByName(username)).thenReturn(user);
   }

   /**
    * The name a connector stores to select this provider. Pinned because it is written
    * in connector rows and in setting keys: changing it orphans both.
    */
   @Test
   void isRegisteredUnderItsName() {
      assertEquals("bluemind-sudo", provider.getName());
      assertSame(provider, service.getProviders()
                                  .stream()
                                  .filter(p -> "bluemind-sudo".equals(p.getName()))
                                  .findFirst()
                                  .orElse(null));
   }

   /**
    * The sudo yields a session id that BlueMind accepts on all three, which the vendor
    * confirmed for IMAP and CalDAV.
    */
   @Test
   void servesEveryChannel() {
      assertEquals(EnumSet.allOf(ConnectorCredentialsChannel.class), provider.getSupportedChannels());
   }

   /**
    * The single difference with the Personal provider, and the one the whole
    * default-connector feature rests on: nobody has to type anything for a user to be
    * connected. A true here would make EXO-89651 to EXO-89656 impossible.
    */
   @Test
   void asksNothingOfTheUser() {
      assertFalse(provider.requiresUserAction());
   }

   private Map<String, ConnectorCredentialsConfigField> describedFields() {
      return provider.getConfigurationFields()
                     .stream()
                     .collect(Collectors.toMap(ConnectorCredentialsConfigField::getKey, Function.identity()));
   }

   /**
    * The four values an administrator has to give, and no more. Pinned as a set
    * because the connectors store them under these very keys: a rename orphans every
    * configuration already saved.
    */
   @Test
   void describesTheFourFieldsAnAdministratorMustFill() {
      assertEquals(List.of("apiUrl", "technicalLogin", "technicalSecret", "targetLoginField"),
                   provider.getConfigurationFields().stream().map(ConnectorCredentialsConfigField::getKey).toList());
   }

   /**
    * The API base URL cannot be derived from the connector's own URLs - those address
    * IMAP, SMTP or CalDAV, none of which is the REST API the sudo is asked for. So it
    * is a field of its own, and a required one.
    */
   @Test
   void asksForTheApiUrlBecauseNothingElseCarriesIt() {
      ConnectorCredentialsConfigField apiUrl = describedFields().get("apiUrl");
      assertEquals(ConnectorCredentialsConfigFieldType.TEXT, apiUrl.getType());
      assertTrue(apiUrl.isRequired());
   }

   /**
    * Only the password is a SECRET. That type is what sends it through the platform
    * codec and keeps it out of every read-back - the login and the target field are
    * not secrets and encrypting them would only make them unreadable to the screen
    * that must show them.
    */
   @Test
   void onlyTheTechnicalSecretIsTypedSecret() {
      assertEquals(ConnectorCredentialsConfigFieldType.SECRET, describedFields().get("technicalSecret").getType());
      assertEquals(ConnectorCredentialsConfigFieldType.TEXT, describedFields().get("technicalLogin").getType());
      assertEquals(ConnectorCredentialsConfigFieldType.TEXT, describedFields().get("apiUrl").getType());
   }

   /**
    * The technical account must belong to BlueMind's global domain - a regular
    * domain's account logs in fine and then has every sudo refused, with a 200 and a
    * "Bad" status that no error message explains. The help text is the only place an
    * administrator can learn that before paying for it.
    */
   @Test
   void tellsTheAdministratorTheAccountMustBeGlobal() {
      assertNotNull(describedFields().get("technicalLogin").getHelpKey(),
                    "without help text, every installation rediscovers the global-domain rule the hard way");
   }

   /**
    * Which eXo field names the user on the remote server. A closed list, because the
    * two values are the only ones the derivation knows how to read.
    */
   @Test
   void offersTheTwoWaysOfNamingTheTargetUser() {
      ConnectorCredentialsConfigField target = describedFields().get("targetLoginField");
      assertEquals(ConnectorCredentialsConfigFieldType.CHOICE, target.getType());
      assertEquals(List.of("username", "email"), target.getOptions());
      assertTrue(target.isRequired());
   }

   /** Every field names its label through the bundle, never a literal. */
   @Test
   void everyFieldCarriesALabelKey() {
      provider.getConfigurationFields()
              .forEach(field -> assertNotNull(field.getLabelKey(), field.getKey() + " has no label key"));
   }

   /**
    * The administrator said the eXo login is the BlueMind login, so it travels as is -
    * no profile read, no remote call.
    */
   @Test
   void namesTheTargetByItsUsernameWhenConfiguredSo() {
      givenConfiguredWith("username");

      assertEquals(ALICE, provider.resolveTargetIdentity(context()));
   }

   /** And by the profile's email address when that is what the administrator chose. */
   @Test
   void namesTheTargetByItsEmailWhenConfiguredSo() throws Exception {
      givenConfiguredWith("email");
      givenEmailOf(ALICE, "alice@example.com");

      assertEquals("alice@example.com", provider.resolveTargetIdentity(context()));
   }

   /**
    * Nothing configured, nothing to say. The contract answers null rather than
    * guessing, and the connector then addresses the resource as it already does.
    */
   @Test
   void namesNobodyWhenTheConnectorIsNotConfigured() {
      lenient().when(configStorage.readWithoutSecrets(any())).thenReturn(Map.of());

      assertNull(provider.resolveTargetIdentity(context()));
   }

   /**
    * A user eXo does not know, or one whose profile carries no address, cannot be
    * named on the remote server. Null says exactly that - and the contract wants it
    * said here rather than folded into "the remote server refused" three calls later.
    */
   @Test
   void namesNobodyWhenTheProfileCarriesNoEmail() throws Exception {
      givenConfiguredWith("email");
      givenEmailOf(ALICE, "   ");

      assertNull(provider.resolveTargetIdentity(context()));
   }

   @Test
   void namesNobodyWhenTheUserIsUnknown() throws Exception {
      givenConfiguredWith("email");
      lenient().when(userHandler.findUserByName(ALICE)).thenReturn(null);

      assertNull(provider.resolveTargetIdentity(context()));
   }

   /**
    * The directory can throw - it is a remote store like any other. Naming a user is
    * not the place to surface that: the caller asked who this user is, and "I cannot
    * tell" is the answer, not a stack trace.
    */
   @Test
   void namesNobodyWhenTheDirectoryFails() throws Exception {
      givenConfiguredWith("email");
      lenient().when(userHandler.findUserByName(ALICE)).thenThrow(new RuntimeException("directory down"));

      assertNull(provider.resolveTargetIdentity(context()));
   }

   /**
    * No user in the context, nobody to name - and nothing to ask the directory, which
    * is the observable half of it: without the guard the lookup runs on a null login
    * and answers null all the same, so only the absent call tells the two apart.
    */
   @Test
   void namesNobodyWithoutAUsername() throws Exception {
      givenConfiguredWith("email");

      assertNull(provider.resolveTargetIdentity(new ConnectorCredentialsContext(2L,
                                                                                BluemindSudoCredentialsProvider.NAME,
                                                                                null,
                                                                                ConnectorCredentialsChannel.IMAP,
                                                                                "email")));
      verify(userHandler, never()).findUserByName(any());
   }

   /**
    * The whole point of the provider, in one test: the technical account opens its own
    * session, and that session - never the password - is what buys the target's.
    * <p>
    * The material pairs the <b>target</b> with the <b>target's</b> session id. A live 5.7
    * instance settled that: the same session id presented under the technical login was
    * refused with a 401, while under the target's login the server answered 207 and named
    * the target in its {@code current-user-principal}.
    */
   @Test
   void logsTheTechnicalAccountInThenBuysTheTargetSession() throws Exception {
      givenConfiguredWith("email");
      givenTheTechnicalSecret();
      givenEmailOf(ALICE, "alice@example.com");
      givenBluemindServes("alice@example.com", "sid-alice");

      ConnectorCredentials material = provider.produce(context(ConnectorCredentialsChannel.HTTP));

      assertEquals("Basic " + Base64.getEncoder()
                                    .encodeToString("alice@example.com:sid-alice".getBytes(StandardCharsets.UTF_8)),
                   ((HttpConnectorCredentials) material).getAuthorizationHeaderValue());
      InOrder sequence = inOrder(bluemind);
      sequence.verify(bluemind).login("https://bm.example.com", "admin0@global.virt", "t0ps3cret");
      sequence.verify(bluemind).sudo("https://bm.example.com", "sid-tech", "alice@example.com");
   }

   /** IMAP and SMTP get the same pair, in the shape the mail layer expects. */
   @ParameterizedTest
   @EnumSource(value = ConnectorCredentialsChannel.class, names = { "IMAP", "SMTP" })
   void servesMailChannelsWithTheSamePair(ConnectorCredentialsChannel channel) throws Exception {
      givenConfiguredWith("email");
      givenTheTechnicalSecret();
      givenEmailOf(ALICE, "alice@example.com");
      givenBluemindServes("alice@example.com", "sid-alice");

      ConnectorCredentials material = provider.produce(context(channel));

      PasswordAuthentication pair = authenticate(((MailConnectorCredentials) material).getAuthenticator());
      assertEquals("alice@example.com", pair.getUserName());
      assertEquals("sid-alice", pair.getPassword());
   }

   /**
    * <b>The contract's own invariant.</b> produce() must sudo to exactly what
    * resolveTargetIdentity() answers: a second, parallel derivation would not be an
    * authentication error but a valid session pointed at someone else's mailbox, with
    * nothing raised anywhere. Configured on the username, both must say the username.
    */
   @Test
   void sudoesToTheVeryIdentityItResolves() throws Exception {
      givenConfiguredWith("username");
      lenient().when(configStorage.readDecrypted(any()))
               .thenReturn(Map.of("apiUrl", "https://bm.example.com",
                                  "technicalLogin", "admin0@global.virt",
                                  "technicalSecret", "t0ps3cret",
                                  "targetLoginField", "username"));
      givenBluemindServes(ALICE, "sid-alice");

      ConnectorCredentials material = provider.produce(context(ConnectorCredentialsChannel.HTTP));

      assertEquals(ALICE, provider.resolveTargetIdentity(context()));
      assertEquals("Basic " + Base64.getEncoder()
                                    .encodeToString((ALICE + ":sid-alice").getBytes(StandardCharsets.UTF_8)),
                   ((HttpConnectorCredentials) material).getAuthorizationHeaderValue());
      verify(bluemind).sudo("https://bm.example.com", "sid-tech", ALICE);
   }

   /**
    * A user who cannot be named is refused here, before any remote call: sudoing to null
    * would either fail obscurely or, worse, land somewhere unintended.
    */
   @Test
   void refusesToProduceForAUserItCannotName() throws Exception {
      givenConfiguredWith("email");
      givenTheTechnicalSecret();
      givenEmailOf(ALICE, "  ");

      ConnectorCredentialsContext context = context(ConnectorCredentialsChannel.HTTP);
      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                            () -> provider.produce(context));

      assertTrue(refusal.getMessage().contains(ALICE), refusal.getMessage());
      verifyNoInteractions(bluemind);
   }

   /** BlueMind's own refusal travels as it is - it already names what was attempted. */
   @Test
   void letsBlueMindRefusalsThrough() throws Exception {
      givenConfiguredWith("email");
      givenTheTechnicalSecret();
      givenEmailOf(ALICE, "alice@example.com");
      when(bluemind.login(any(), any(), any())).thenThrow(new ConnectorCredentialsException("BlueMind refused to log admin0 in"));

      ConnectorCredentialsContext context = context(ConnectorCredentialsChannel.HTTP);
      assertThrows(ConnectorCredentialsException.class, () -> provider.produce(context));
   }

   /**
    * The technical password never reaches a message. It is read for one call and is not
    * part of anything the provider says about itself afterwards.
    */
   @Test
   void neverCarriesTheTechnicalSecretIntoAFailure() throws Exception {
      givenConfiguredWith("email");
      givenTheTechnicalSecret();
      givenEmailOf(ALICE, null);

      ConnectorCredentialsContext context = context(ConnectorCredentialsChannel.HTTP);
      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                            () -> provider.produce(context));

      assertFalse(refusal.getMessage().contains("t0ps3cret"), refusal.getMessage());
   }

   /**
    * Producing and invalidating derive the same key: an invalidation that missed the
    * entry the production wrote would be a silent no-op, and the refused session would
    * be served again until it expires.
    */
   @Test
   void invalidatesTheSessionItProduced() throws Exception {
      BluemindSessionStorage sessions = mock(BluemindSessionStorage.class);
      provider = new BluemindSudoCredentialsProvider(service, configStorage, organizationService, sessions);
      givenTheTechnicalSecret();
      givenConfiguredWith("email");
      givenEmailOf(ALICE, "alice@example.com");
      when(sessions.sudoSession(any(), any())).thenReturn(new BluemindSessionStorage.CachedSession("sid-alice", 0L));

      provider.produce(context());
      provider.invalidate(context());

      org.mockito.ArgumentCaptor<BluemindSessionStorage.SudoKey> produced = org.mockito.ArgumentCaptor.forClass(BluemindSessionStorage.SudoKey.class);
      verify(sessions).sudoSession(produced.capture(), eq("t0ps3cret"));
      verify(sessions).evictSudoSession(produced.getValue());
      assertEquals(new BluemindSessionStorage.SudoKey("https://bm.example.com", "admin0@global.virt", "alice@example.com"),
                   produced.getValue());
   }

   /**
    * The connectors call this after a 401, once: it must never throw, or one refused
    * request would turn into a broken sync.
    */
   @Test
   void invalidatingWithoutATargetIsHarmless() {
      ConnectorCredentialsContext context = context();

      assertDoesNotThrow(() -> provider.invalidate(context));
   }

   /** The material declares the moment its kept session leaves the cache. */
   @Test
   void declaresTheExpiryOfTheKeptSession() throws Exception {
      givenTheTechnicalSecret();
      givenConfiguredWith("email");
      givenEmailOf(ALICE, "alice@example.com");
      givenBluemindServes("alice@example.com", "sid-alice");

      long before = System.currentTimeMillis();
      Long expiresAt = provider.produce(context()).getExpiresAtEpochMillis();

      assertTrue(expiresAt >= before + 600_000L && expiresAt <= System.currentTimeMillis() + 600_000L, String.valueOf(expiresAt));
   }
}