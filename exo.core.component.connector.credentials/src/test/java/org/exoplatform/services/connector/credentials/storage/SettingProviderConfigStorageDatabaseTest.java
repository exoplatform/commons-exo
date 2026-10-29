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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.component.test.ConfigurationUnit;
import org.exoplatform.component.test.ConfiguredBy;
import org.exoplatform.component.test.ContainerScope;
import org.exoplatform.component.test.KernelBootstrap;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigFieldType;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.web.security.codec.CodecInitializer;

/**
 * store() and delete() against the platform's own JPA settings store on an in-memory
 * database, where a save can be seen not to commit. The unit test mocks
 * {@code SettingService}, so it cannot see a transaction at all.
 * <p>
 * This test boots a portal container on purpose, and does not mock
 * {@code ExoContainerContext}: the woven {@code @ContainerTransactional} advice must find
 * the container's {@code EntityManagerService} to open the transaction under test.
 * <p>
 * The failure is injected between the storage and the real store: the entries before
 * the failing one go through {@code JPASettingServiceImpl} and its {@code @ExoTransactional}
 * advice, exactly as on a server.
 */
@ConfiguredBy({
  @ConfigurationUnit(scope = ContainerScope.ROOT, path = "conf/configuration.xml"),
  @ConfigurationUnit(scope = ContainerScope.PORTAL, path = "conf/portal/configuration.xml"),
  @ConfigurationUnit(scope = ContainerScope.PORTAL, path = "conf/connector-credentials/settings-store-configuration.xml")
})
class SettingProviderConfigStorageDatabaseTest {

   private static final String          PROVIDER = "bluemind-sudo";

   private static final Scope           SCOPE    = Scope.APPLICATION.id("connectorCredentials");

   private static KernelBootstrap       bootstrap;

   private static SettingService        settings;

   private final ConnectorCredentialsContext context = new ConnectorCredentialsContext(7L,
                                                                                      PROVIDER,
                                                                                      null,
                                                                                      ConnectorCredentialsChannel.HTTP,
                                                                                      "email");

   private String                       failingSetKey;

   private int                          failingRemoveCall;

   private SettingProviderConfigStorage storage;

   @BeforeAll
   static void bootThePortal() {
      bootstrap = new KernelBootstrap();
      bootstrap.addConfiguration(SettingProviderConfigStorageDatabaseTest.class);
      bootstrap.boot();
      settings = bootstrap.getContainer().getComponentInstanceOfType(SettingService.class);
   }

   @AfterAll
   static void stopThePortal() {
      bootstrap.dispose();
   }

   @BeforeEach
   void setUp() throws Exception {
      failingSetKey = null;
      failingRemoveCall = 0;
      CodecInitializer codecInitializer = mock(CodecInitializer.class);
      AbstractCodec codec = mock(AbstractCodec.class);
      when(codecInitializer.getCodec()).thenReturn(codec);
      when(codec.encode(anyString())).thenAnswer(call -> "ENC(" + call.getArgument(0) + ")");
      ConnectorCredentialsService credentialsService = new ConnectorCredentialsService();
      credentialsService.register(provider());
      storage = new SettingProviderConfigStorage(failingWhereAsked(settings), codecInitializer, credentialsService);
      new SettingProviderConfigStorage(settings, codecInitializer, credentialsService).delete(context);
   }

   /**
    * The secret's write fails after the address's and the login's: the save writes
    * nothing, so the stored address still goes with the secret it was typed with. With
    * each entry committed on its own, the new address would have been paired with the
    * old secret, and the next call would send that secret to the new host.
    */
   @Test
   void aSaveThatFailsOnItsLastEntryCommitsNoneOfIt() throws Exception {
      storage.store(context, configuration("https://old.example.org", "user1", "secret1"));

      failingSetKey = "technicalSecret";
      assertThrows(IllegalStateException.class,
                   () -> storage.store(context, configuration("https://new.example.org", "user2", "secret2")));

      assertEquals("https://old.example.org", stored("apiUrl"), "the address of a save that failed was committed");
      assertEquals("user1", stored("technicalLogin"), "the login of a save that failed was committed");
      assertEquals("ENC(secret1)", stored("technicalSecret"));
   }

   /** A removal that fails on its second key removes none of them. */
   @Test
   void aDeleteThatFailsPartWayRemovesNothing() throws Exception {
      storage.store(context, configuration("https://old.example.org", "user1", "secret1"));

      failingRemoveCall = 2;
      assertThrows(IllegalStateException.class, () -> storage.delete(context));

      assertEquals("https://old.example.org", stored("apiUrl"), "a key of a delete that failed was removed");
      assertEquals("user1", stored("technicalLogin"), "a key of a delete that failed was removed");
      assertEquals("ENC(secret1)", stored("technicalSecret"), "a key of a delete that failed was removed");
   }

   private String stored(String field) {
      SettingValue<?> value = settings.get(Context.GLOBAL, SCOPE, PROVIDER + "/email/7/" + field);
      return value == null ? null : (String) value.getValue();
   }

   private Map<String, String> configuration(String apiUrl, String login, String secret) {
      return Map.of("apiUrl", apiUrl, "technicalLogin", login, "technicalSecret", secret);
   }

   /**
    * The real store, failing on the set of the named field or on the given remove call,
    * before that call reaches the database.
    */
   private SettingService failingWhereAsked(SettingService real) {
      AtomicInteger removeCalls = new AtomicInteger();
      return (SettingService) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                     new Class<?>[] { SettingService.class },
                                                     (proxy, method, args) -> {
                                                        if ("set".equals(method.getName()) && failingSetKey != null
                                                            && String.valueOf(args[2]).endsWith("/" + failingSetKey)) {
                                                           throw new IllegalStateException("the database refused " + args[2]);
                                                        }
                                                        if ("remove".equals(method.getName()) && args.length == 3
                                                            && failingRemoveCall > 0
                                                            && removeCalls.incrementAndGet() == failingRemoveCall) {
                                                           throw new IllegalStateException("the database refused " + args[2]);
                                                        }
                                                        try {
                                                           return method.invoke(real, args);
                                                        } catch (InvocationTargetException e) {
                                                           throw e.getCause();
                                                        }
                                                     });
   }

   /** An address, a login and a secret, written in that order. */
   private ConnectorCredentialsProvider provider() {
      ConnectorCredentialsProvider provider = mock(ConnectorCredentialsProvider.class);
      when(provider.getName()).thenReturn(PROVIDER);
      when(provider.getSupportedChannels()).thenReturn(EnumSet.allOf(ConnectorCredentialsChannel.class));
      when(provider.getConfigurationFields()).thenReturn(List.of(field("apiUrl", ConnectorCredentialsConfigFieldType.TEXT),
                                                                 field("technicalLogin",
                                                                       ConnectorCredentialsConfigFieldType.TEXT),
                                                                 field("technicalSecret",
                                                                       ConnectorCredentialsConfigFieldType.SECRET)));
      return provider;
   }

   private ConnectorCredentialsConfigField field(String key, ConnectorCredentialsConfigFieldType type) {
      return new ConnectorCredentialsConfigField(key, type, "label." + key, null, true, List.of());
   }

}
