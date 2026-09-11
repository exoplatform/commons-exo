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
package org.exoplatform.services.connector.credentials.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.meeds.common.ContainerTransactional;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigFieldType;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.web.security.codec.CodecInitializer;

/**
 * The storage validates a configuration against the descriptor of the provider it is
 * for, then keeps one setting entry per field - encrypting the ones the descriptor
 * types SECRET, and only those.
 */
public class SettingProviderConfigStorageTest {

   private static final String       PROVIDER = "bluemind-sudo";

   private SettingService            settingService;

   private CodecInitializer          codecInitializer;

   private ConnectorCredentialsService credentialsService;

   private SettingProviderConfigStorage storage;

   private AbstractCodec             codec;

   private MockedStatic<ExoContainerContext> containerContext;

   /**
    * States a container the woven aspect can work with, the mockStatic discipline
    * email-connector's EmailSyncDispatcherTest and the CalDAV tests already follow.
    * store() and delete() are @ContainerTransactional and the aspect is woven into
    * them, so it runs here too: left to itself it takes the current container, finds
    * the RootContainer, and reaches for PortalContainer.getInstance() - which in a unit
    * test boots a real portal, starts CodecInitializer, and fails on a key file it
    * cannot write inside a jar. A stated container that is not the root one keeps the
    * advice a no-op, which is all a unit test needs from it.
    */
   @BeforeEach
   void setUp() throws Exception {
      containerContext = mockStatic(ExoContainerContext.class);
      containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(mock(ExoContainer.class));
      settingService = mock(SettingService.class);
      codecInitializer = mock(CodecInitializer.class);
      codec = mock(AbstractCodec.class);
      lenient().when(codecInitializer.getCodec()).thenReturn(codec);
      lenient().when(codec.encode(anyString())).thenAnswer(call -> "ENC(" + call.getArgument(0) + ")");
      lenient().when(codec.decode(anyString()))
               .thenAnswer(call -> call.getArgument(0, String.class).replaceAll("^ENC\\((.*)\\)$", "$1"));
      credentialsService = new ConnectorCredentialsService();
      credentialsService.register(sudoProvider());
      storage = new SettingProviderConfigStorage(settingService, codecInitializer, credentialsService);
   }

   /** Takes the stated container away again. */
   @AfterEach
   void forgetTheContainer() {
      containerContext.close();
   }

   /** The three fields EXO-89648 describes for the sudo provider. */
   private ConnectorCredentialsProvider sudoProvider() {
      ConnectorCredentialsProvider provider = mock(ConnectorCredentialsProvider.class);
      when(provider.getName()).thenReturn(PROVIDER);
      when(provider.getSupportedChannels()).thenReturn(EnumSet.allOf(ConnectorCredentialsChannel.class));
      when(provider.getConfigurationFields())
                                             .thenReturn(List.of(new ConnectorCredentialsConfigField("technicalLogin",
                                                                                                     ConnectorCredentialsConfigFieldType.TEXT,
                                                                                                     "label.technicalLogin",
                                                                                                     null,
                                                                                                     true,
                                                                                                     List.of()),
                                                                 new ConnectorCredentialsConfigField("technicalSecret",
                                                                                                     ConnectorCredentialsConfigFieldType.SECRET,
                                                                                                     "label.technicalSecret",
                                                                                                     "help.technicalSecret",
                                                                                                     true,
                                                                                                     List.of()),
                                                                 new ConnectorCredentialsConfigField("targetLoginField",
                                                                                                     ConnectorCredentialsConfigFieldType.CHOICE,
                                                                                                     "label.targetLoginField",
                                                                                                     null,
                                                                                                     true,
                                                                                                     List.of("username", "email"))));
      return provider;
   }

   private ConnectorCredentialsContext context() {
      return context(2L, "email");
   }

   private ConnectorCredentialsContext context(long connectorId, String connectorKind) {
      return new ConnectorCredentialsContext(connectorId, PROVIDER, null, ConnectorCredentialsChannel.HTTP, connectorKind);
   }

   /**
    * A key no descriptor declares is refused, and this is a security guard rather than
    * tidiness: an accepted unknown key would be stored under no type, therefore
    * unencrypted - which is exactly how a secret ends up in the clear.
    */
   @Test
   void aKeyNoDescriptorDeclaresIsRefused() {
      Map<String, String> values = Map.of("technicalLogin", "svc2",
                                          "technicalSecret", "s3cret",
                                          "targetLoginField", "email",
                                          "smuggled", "whatever");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                           () -> storage.store(context(), values));

      assertEquals("connector.credentials.unknownConfigurationField", refusal.getMessage());
   }


   /**
    * A required field with nothing in it is refused at save time rather than at the
    * first remote call: a sudo with no technical account is a call addressed to nobody,
    * and its failure surfaces far from the screen that caused it.
    */
   @Test
   void aRequiredFieldLeftEmptyIsRefused() {
      Map<String, String> values = new HashMap<>(Map.of("technicalLogin", "svc2", "targetLoginField", "email"));
      values.put("technicalSecret", "");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                           () -> storage.store(context(), values));

      assertEquals("connector.credentials.missingConfigurationField", refusal.getMessage());
   }

   /**
    * The whole reason CHOICE exists rather than free text: a value outside the admitted
    * list is caught here, where the administrator can still read why.
    */
   @Test
   void aValueOutsideAClosedListIsRefused() {
      Map<String, String> values = Map.of("technicalLogin", "svc2",
                                          "technicalSecret", "s3cret",
                                          "targetLoginField", "displayName");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                           () -> storage.store(context(), values));

      assertEquals("connector.credentials.invalidConfigurationValue", refusal.getMessage());
   }

   private Map<String, String> aValidConfiguration() {
      return Map.of("technicalLogin", "svc2", "technicalSecret", "s3cret", "targetLoginField", "email");
   }

   private String settingWritten(String key) {
      ArgumentCaptor<SettingValue> captor = ArgumentCaptor.forClass(SettingValue.class);
      verify(settingService).set(eq(Context.GLOBAL), any(Scope.class), eq(key), captor.capture());
      return (String) captor.getValue().getValue();
   }

   /**
    * Only the field the descriptor types SECRET goes through the codec. This is the
    * invariant EXO-89650 audits, and the reason the type carries the encryption rather
    * than a list maintained beside it: a field cannot be secret for the form and clear
    * for the storage.
    */
   @Test
   void onlyTheSecretFieldIsEncrypted() throws Exception {
      storage.store(context(), aValidConfiguration());

      assertEquals("ENC(s3cret)", settingWritten("bluemind-sudo/email/2/technicalSecret"));
      assertEquals("svc2", settingWritten("bluemind-sudo/email/2/technicalLogin"), "a login is not a secret");
      assertEquals("email", settingWritten("bluemind-sudo/email/2/targetLoginField"));
      verify(codec).encode("s3cret");
      verify(codec, never()).encode("svc2");
      verify(codec, never()).encode("email");
   }

   /**
    * The provider gets its secret in the clear - it has a sudo to perform with it.
    */
   @Test
   void readDecryptedHandsTheProviderTheClearSecret() throws Exception {
      givenStored("technicalLogin", "svc2");
      givenStored("technicalSecret", "ENC(s3cret)");
      givenStored("targetLoginField", "email");

      assertEquals(Map.of("technicalLogin", "svc2", "technicalSecret", "s3cret", "targetLoginField", "email"),
                   storage.readDecrypted(context()));
   }

   /**
    * The administration screen gets everything but the secret, and no decryption
    * happens on that path at all: a value the UI never receives is a value that cannot
    * leak through it.
    */
   @Test
   void readWithoutSecretsOmitsTheSecretAndNeverDecryptsIt() {
      givenStored("technicalLogin", "svc2");
      givenStored("technicalSecret", "ENC(s3cret)");
      givenStored("targetLoginField", "email");

      Map<String, String> answered = storage.readWithoutSecrets(context());

      assertEquals(Map.of("technicalLogin", "svc2", "targetLoginField", "email"), answered);
      verify(codec, never()).decode(anyString());
   }

   private void givenStored(String key, String value) {
      lenient().when(settingService.get(eq(Context.GLOBAL), any(Scope.class), eq("bluemind-sudo/email/2/" + key)))
               .thenReturn((SettingValue) SettingValue.create(value));
   }

   private Map<String, String> configurationWithSecret(String secret) {
      Map<String, String> values = new HashMap<>(aValidConfiguration());
      values.put("technicalSecret", secret);
      return values;
   }

   /**
    * The administration form never receives the stored secret (readWithoutSecrets omits
    * it), so it always posts that field back empty. Taking that emptiness literally
    * would wipe the secret on every unrelated edit - the login, the target field - and
    * the connector would stop authenticating for a reason nothing in the screen shows.
    */
   @Test
   void aSecretLeftBlankOnUpdateKeepsTheStoredOne() throws Exception {
      givenStored("technicalSecret", "ENC(s3cret)");

      storage.store(context(), configurationWithSecret(""));

      verify(settingService, never()).set(eq(Context.GLOBAL),
                                          any(Scope.class),
                                          eq("bluemind-sudo/email/2/technicalSecret"),
                                          any(SettingValue.class));
      verify(codec, never()).encode("");
   }

   /** Nothing stored yet: an empty required secret is the first-save case, and missing. */
   @Test
   void aSecretLeftBlankWithNothingStoredIsStillMissing() {
      ConnectorCredentialsException thrown = assertThrows(ConnectorCredentialsException.class,
                                                          () -> storage.store(context(), configurationWithSecret("")));

      assertEquals(SettingProviderConfigStorage.MISSING_FIELD, thrown.getMessage());
      verify(settingService, never()).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
   }

   /**
    * Removing a connector must leave nothing of its configuration behind - a stale
    * technical secret is a credential nobody administers any more. It goes key by key:
    * the scope-wide remove would take every other connector's configuration with it,
    * which is the failure the isolation test below guards.
    */
   @Test
   void deleteRemovesEveryFieldOfThatConnectorOnly() {
      storage.delete(context());

      verify(settingService).remove(eq(Context.GLOBAL), any(Scope.class), eq("bluemind-sudo/email/2/technicalLogin"));
      verify(settingService).remove(eq(Context.GLOBAL), any(Scope.class), eq("bluemind-sudo/email/2/technicalSecret"));
      verify(settingService).remove(eq(Context.GLOBAL), any(Scope.class), eq("bluemind-sudo/email/2/targetLoginField"));
      verify(settingService, never()).remove(any(Context.class), any(Scope.class));
      verify(settingService, never()).remove(any(Context.class));
   }

   /**
    * The scenario the design was verified against: two email connectors and two CalDAV
    * connectors, all four on the same provider, two of them sharing the same service
    * account. Four independent configurations, so the key carries the kind and the
    * connector id - not the provider alone.
    */
   @Test
   void fourConnectorsOnOneProviderKeepFourIndependentConfigurations() throws Exception {
      storage.store(context(1L, "email"), configurationOf("user1", "password1"));
      storage.store(context(2L, "email"), configurationOf("user2", "password2"));
      storage.store(context(1L, "caldav"), configurationOf("user3", "password3"));
      storage.store(context(2L, "caldav"), configurationOf("user1", "password1"));

      assertEquals("user1", settingWritten("bluemind-sudo/email/1/technicalLogin"));
      assertEquals("user2", settingWritten("bluemind-sudo/email/2/technicalLogin"));
      assertEquals("user3", settingWritten("bluemind-sudo/caldav/1/technicalLogin"));
      assertEquals("user1", settingWritten("bluemind-sudo/caldav/2/technicalLogin"));
      assertEquals("ENC(password2)", settingWritten("bluemind-sudo/email/2/technicalSecret"));
      assertEquals("ENC(password1)", settingWritten("bluemind-sudo/caldav/2/technicalSecret"));
   }

   private Map<String, String> configurationOf(String login, String secret) {
      return Map.of("technicalLogin", login, "technicalSecret", secret, "targetLoginField", "username");
   }

   /**
    * The storage is consumed from other WARs - email-connector and caldav-integration -
    * and only {@code @Service} makes a bean visible across the Spring contexts the
    * kernel bridge merges. Under {@code @Component} it would exist, resolve its own
    * dependencies, and be undefined for every connector add-on that injects it.
    */
   @Test
   void theStorageIsAServiceSoOtherAddonsCanInjectIt() {
      assertNotNull(SettingProviderConfigStorage.class.getAnnotation(Service.class),
                    "@Component would keep this bean invisible to email-connector and caldav-integration");
   }

   /**
    * One configuration is several setting entries, so a write must be all or nothing:
    * a failure on the second field would otherwise leave a login pointing at a secret
    * that was never stored. Same for the removal.
    * <p>
    * The aspect is woven into these two methods at build time - they are the first
    * annotated methods in the whole of commons-exo, where the parent pom's ajc had been
    * running against no join point at all until now. What that costs the suite is
    * handled in setUp, not here. The assertion stays on the annotation because the
    * atomicity itself is unobservable against a mocked SettingService, and the
    * annotation is what the aspect keys on.
    */
   @Test
   void writesAreTransactional() throws Exception {
      assertNotNull(SettingProviderConfigStorage.class.getMethod("store",
                                                                 ConnectorCredentialsContext.class,
                                                                 Map.class)
                                                      .getAnnotation(ContainerTransactional.class),
                    "a half-written configuration is a connector that authenticates nobody");
      assertNotNull(SettingProviderConfigStorage.class.getMethod("delete", ConnectorCredentialsContext.class)
                                                      .getAnnotation(ContainerTransactional.class),
                    "a half-removed configuration leaves a technical secret nobody administers");
   }

  /**
   * The three refusals store() makes, made without writing - what a connector calls
   * before it creates itself.
   */
   @Test
   void validateRefusesWhatStoreWouldRefuse() {
      assertEquals(SettingProviderConfigStorage.UNKNOWN_FIELD,
                   assertThrows(ConnectorCredentialsException.class,
                                () -> storage.validate(context(), Map.of("nosuchfield", "x"))).getMessage());
      assertEquals(SettingProviderConfigStorage.MISSING_FIELD,
                   assertThrows(ConnectorCredentialsException.class,
                                () -> storage.validate(context(), configurationWithSecret(""))).getMessage());
      Map<String, String> outOfOptions = new HashMap<>(aValidConfiguration());
      outOfOptions.put("targetLoginField", "exoLogin");
      assertEquals(SettingProviderConfigStorage.INVALID_VALUE,
                   assertThrows(ConnectorCredentialsException.class,
                                () -> storage.validate(context(), outOfOptions)).getMessage());
   }

   /** And it writes nothing, whether it accepts or refuses. */
   @Test
   void validateWritesNothing() throws Exception {
      storage.validate(context(), aValidConfiguration());
      assertThrows(ConnectorCredentialsException.class, () -> storage.validate(context(), Map.of("nosuchfield", "x")));

      verify(settingService, never()).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
      verify(settingService, never()).remove(any(Context.class), any(Scope.class), anyString());
   }

   /**
    * validate() answers for the context it is given, so it applies store()'s own
    * retention rule: with a secret already stored for this connector, a blank one
    * means "unchanged" and is acceptable.
    * <p>
    * The two cannot diverge. A caller validates in order to decide whether to write
    * at all, and a validate stricter than the store it guards would refuse an update
    * the store would have accepted - which is how an administrator renaming a
    * connector was told to retype a password nobody asked to change.
    */
   @Test
   void validateAcceptsABlankSecretWhenOneIsStored() {
      givenStored("technicalSecret", "ENC(s3cret)");

      assertDoesNotThrow(() -> storage.validate(context(), configurationWithSecret("")));
   }

   /** And refuses it when nothing is stored: that is the first save, and it is missing. */
   @Test
   void validateRefusesABlankSecretWhenNoneIsStored() {
      assertEquals(SettingProviderConfigStorage.MISSING_FIELD,
                   assertThrows(ConnectorCredentialsException.class,
                                () -> storage.validate(context(), configurationWithSecret(""))).getMessage());
   }
}
