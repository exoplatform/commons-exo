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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Set;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;

import org.junit.jupiter.api.Test;

public class PersonalCredentialsProviderTest {

   private static final String TEST_USER = "testuser";

   private PersonalCredentialsSource emailSource(RawCredentials credentials) {
      PersonalCredentialsSource source = mock(PersonalCredentialsSource.class);
      when(source.getConnectorKind()).thenReturn("email");
      when(source.getCredentials(TEST_USER)).thenReturn(credentials);
      return source;
   }

   private PasswordAuthentication authenticate(Authenticator authenticator) throws Exception {
      Method method = Authenticator.class.getDeclaredMethod("getPasswordAuthentication");
      method.setAccessible(true);
      return (PasswordAuthentication) method.invoke(authenticator);
   }

   /**
    * A provider with the given sources already announced - through the real
    * {@link PersonalCredentialsProvider#register(PersonalCredentialsSource)},
    * not a stub: registration is now the only way a source reaches the provider,
    * so every test rides that path.
    *
    * @param sources the sources to announce
    * @return the provider under test
    */
   private static PersonalCredentialsProvider providerWith(PersonalCredentialsSource... sources) {
      PersonalCredentialsProvider provider = new PersonalCredentialsProvider(new ConnectorCredentialsService());
      for (PersonalCredentialsSource source : sources) {
         provider.register(source);
      }
      return provider;
   }

   /**
    * The first registration for a kind wins and a second is refused, because
    * replacing it would make which storage serves a connector's users depend on
    * the order the platform happened to deploy its WARs in.
    */
   @Test
   public void testASecondSourceForOneKindDoesNotReplaceTheFirst() {
      PersonalCredentialsSource first = emailSource(new RawCredentials("first@acme.com", "s1"));
      PersonalCredentialsSource second = emailSource(new RawCredentials("second@acme.com", "s2"));
      PersonalCredentialsProvider provider = providerWith(first, second);

      ConnectorCredentialsContext context = new ConnectorCredentialsContext(1L,
                                                                            PersonalCredentialsProvider.NAME,
                                                                            TEST_USER,
                                                                            ConnectorCredentialsChannel.IMAP,
                                                                            "email");

      assertEquals("first@acme.com", provider.resolveTargetIdentity(context));
   }

   @Test
   public void testGetName() {
      PersonalCredentialsProvider provider = providerWith();
      assertEquals("personal", provider.getName());
   }

   @Test
   public void testGetSupportedChannels() {
      PersonalCredentialsProvider provider = providerWith();
      Set<ConnectorCredentialsChannel> channels = provider.getSupportedChannels();
      assertTrue(channels.contains(ConnectorCredentialsChannel.IMAP));
      assertTrue(channels.contains(ConnectorCredentialsChannel.SMTP));
      assertTrue(channels.contains(ConnectorCredentialsChannel.HTTP));
   }

   @Test
   public void testRequiresUserAction() {
      PersonalCredentialsProvider provider = providerWith();
      assertTrue(provider.requiresUserAction());
   }

   @Test
   public void testProduceImapWrapsMailConnectorCredentials() throws Exception {
      PersonalCredentialsSource source = emailSource(new RawCredentials("user@example.com", "secret"));
      PersonalCredentialsProvider provider = providerWith(source);

      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");
      ConnectorCredentials credentials = provider.produce(context);

      assertTrue(credentials instanceof MailConnectorCredentials);
      assertEquals(ConnectorCredentialsChannel.IMAP, credentials.getChannel());
      PasswordAuthentication auth = authenticate(((MailConnectorCredentials) credentials).getAuthenticator());
      assertEquals("user@example.com", auth.getUserName());
      assertEquals("secret", auth.getPassword());
   }

   @Test
   public void testProduceHttpWrapsHttpConnectorCredentials() throws Exception {
      PersonalCredentialsSource source = emailSource(new RawCredentials("caldavUser", "secret"));
      PersonalCredentialsProvider provider = providerWith(source);

      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.HTTP, "email");
      ConnectorCredentials credentials = provider.produce(context);

      assertTrue(credentials instanceof HttpConnectorCredentials);
      String expected = "Basic " + Base64.getEncoder().encodeToString("caldavUser:secret".getBytes());
      assertEquals(expected, ((HttpConnectorCredentials) credentials).getAuthorizationHeaderValue());
   }

   @Test
   public void testProduceThrowsWhenNoSourceForConnectorKind() {
      PersonalCredentialsProvider provider = providerWith();

      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");
      assertThrows(ConnectorCredentialsException.class, () -> provider.produce(context));
   }

   @Test
   public void testProduceThrowsWhenSourceHasNoCredentials() {
      PersonalCredentialsSource source = emailSource(null);
      PersonalCredentialsProvider provider = providerWith(source);

      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");
      assertThrows(ConnectorCredentialsException.class, () -> provider.produce(context));
   }

   @Test
   public void testInvalidateIsANoOp() {
      PersonalCredentialsProvider provider = providerWith();
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");
      provider.invalidate(context);
   }

   @Test
   public void testResolveTargetIdentityAnswersTheStoredRemoteAccount() {
      PersonalCredentialsSource source = emailSource(new RawCredentials("user@example.com", "secret"));
      PersonalCredentialsProvider provider = providerWith(source);

      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.HTTP, "email");
      assertEquals("user@example.com", provider.resolveTargetIdentity(context));
   }

   /**
    * The account named here has to be the one produce() authenticates as, or a
    * connector would address one account while signing in as another - no error, the
    * wrong data. Both read the same source, and this pins that.
    */
   @Test
   public void testResolveTargetIdentityMatchesWhatProduceAuthenticatesAs() throws Exception {
      PersonalCredentialsSource source = emailSource(new RawCredentials("user@example.com", "secret"));
      PersonalCredentialsProvider provider = providerWith(source);

      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");
      PasswordAuthentication auth = authenticate(((MailConnectorCredentials) provider.produce(context)).getAuthenticator());

      assertEquals(auth.getUserName(), provider.resolveTargetIdentity(context));
   }

   /**
    * Null rather than an exception: the caller decides whether having no account to
    * name is fatal for what it was building.
    */
   @Test
   public void testResolveTargetIdentityAnswersNullWhenNothingIsConfigured() {
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.HTTP, "email");

      assertNull(providerWith().resolveTargetIdentity(context));
      assertNull(providerWith(emailSource(null)).resolveTargetIdentity(context));
   }

}
