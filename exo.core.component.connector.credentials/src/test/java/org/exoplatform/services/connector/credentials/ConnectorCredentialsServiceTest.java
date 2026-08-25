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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class ConnectorCredentialsServiceTest {

   private static final String TEST_USER = "testuser";

   private ConnectorCredentialsProvider provider(String name, Set<ConnectorCredentialsChannel> channels) {
      ConnectorCredentialsProvider provider = mock(ConnectorCredentialsProvider.class);
      when(provider.getName()).thenReturn(name);
      when(provider.getSupportedChannels()).thenReturn(channels);
      return provider;
   }

   /**
    * A service with the given providers already announced - through the real
    * {@link ConnectorCredentialsService#register(ConnectorCredentialsProvider)},
    * since registration is now the only way a provider reaches the service.
    *
    * @param providers the providers to announce
    * @return the service under test
    */
   private static ConnectorCredentialsService serviceWith(ConnectorCredentialsProvider... providers) {
      ConnectorCredentialsService service = new ConnectorCredentialsService();
      for (ConnectorCredentialsProvider provider : providers) {
         service.register(provider);
      }
      return service;
   }

   @Test
   public void testRegisterThrowsOnDuplicateProviderName() {
      ConnectorCredentialsProvider first = provider("personal", EnumSet.allOf(ConnectorCredentialsChannel.class));
      ConnectorCredentialsProvider second = provider("personal", EnumSet.allOf(ConnectorCredentialsChannel.class));

      assertThrows(IllegalStateException.class, () -> serviceWith(first, second));
   }

   @Test
   public void testProduceDelegatesToResolvedProvider() throws Exception {
      ConnectorCredentialsProvider provider = provider("personal", EnumSet.of(ConnectorCredentialsChannel.IMAP));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");
      ConnectorCredentials expected = mock(ConnectorCredentials.class);
      when(provider.produce(context)).thenReturn(expected);

      ConnectorCredentialsService service = serviceWith(provider);

      assertSame(expected, service.produce(context));
   }

   @Test
   public void testProduceThrowsWhenNoProviderRegisteredForName() {
      ConnectorCredentialsService service = serviceWith();
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");

      assertThrows(ConnectorCredentialsException.class, () -> service.produce(context));
   }

   @Test
   public void testProduceThrowsWhenProviderDoesNotSupportChannel() {
      ConnectorCredentialsProvider provider = provider("bluemind-sudo", EnumSet.of(ConnectorCredentialsChannel.HTTP));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "bluemind-sudo", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");

      ConnectorCredentialsService service = serviceWith(provider);

      assertThrows(ConnectorCredentialsException.class, () -> service.produce(context));
   }

   @Test
   public void testInvalidateDelegatesToResolvedProvider() {
      ConnectorCredentialsProvider provider = provider("personal", EnumSet.allOf(ConnectorCredentialsChannel.class));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "personal", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");

      ConnectorCredentialsService service = serviceWith(provider);
      service.invalidate(context);

      verify(provider).invalidate(context);
   }

   @Test
   public void testInvalidateIsANoOpWhenNoProviderRegisteredForName() {
      ConnectorCredentialsProvider provider = provider("personal", EnumSet.allOf(ConnectorCredentialsChannel.class));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "unknown", TEST_USER, ConnectorCredentialsChannel.IMAP, "email");

      ConnectorCredentialsService service = serviceWith(provider);
      service.invalidate(context);

      verify(provider, never()).invalidate(context);
   }

   @Test
   public void testResolveTargetIdentityDelegatesToResolvedProvider() throws Exception {
      ConnectorCredentialsProvider provider = provider("bluemind-sudo", EnumSet.of(ConnectorCredentialsChannel.HTTP));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "bluemind-sudo", TEST_USER, ConnectorCredentialsChannel.HTTP, "caldav");
      when(provider.resolveTargetIdentity(context)).thenReturn("mary@corp.com");

      ConnectorCredentialsService service = serviceWith(provider);

      assertEquals("mary@corp.com", service.resolveTargetIdentity(context));
   }

   /**
    * Unlike produce, no channel check: the account a provider would address does not
    * depend on the channel it is asked to speak. A caller resolving an identity for a
    * URL must not be refused because the provider happens not to serve, say, SMTP.
    */
   @Test
   public void testResolveTargetIdentityDoesNotCheckTheChannel() throws Exception {
      ConnectorCredentialsProvider provider = provider("bluemind-sudo", EnumSet.of(ConnectorCredentialsChannel.HTTP));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "bluemind-sudo", TEST_USER, ConnectorCredentialsChannel.SMTP, "caldav");
      when(provider.resolveTargetIdentity(context)).thenReturn("mary@corp.com");

      ConnectorCredentialsService service = serviceWith(provider);

      assertEquals("mary@corp.com", service.resolveTargetIdentity(context));
   }

   @Test
   public void testResolveTargetIdentityThrowsWhenNoProviderRegisteredForName() {
      ConnectorCredentialsProvider provider = provider("personal", EnumSet.allOf(ConnectorCredentialsChannel.class));
      ConnectorCredentialsContext context =
                                          new ConnectorCredentialsContext(1L, "unknown", TEST_USER, ConnectorCredentialsChannel.HTTP, "caldav");

      ConnectorCredentialsService service = serviceWith(provider);

      assertThrows(ConnectorCredentialsException.class, () -> service.resolveTargetIdentity(context));
   }

}
