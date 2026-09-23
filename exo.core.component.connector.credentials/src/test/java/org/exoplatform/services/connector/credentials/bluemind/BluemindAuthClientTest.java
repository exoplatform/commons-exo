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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The BlueMind authentication protocol, exercised against canned answers.
 * <p>
 * Every expectation here was read off a live 5.7 instance and off the vendor's own
 * OpenAPI model, not guessed: the login carries the password alone as a JSON string,
 * the sudo route is {@code _su} with an underscore and no body at all, and the token
 * travels in {@code X-BM-ApiKey}.
 */
@ExtendWith(MockitoExtension.class)
class BluemindAuthClientTest {

   private static final String API_URL = "https://bm.example.com";

   private HttpClient          transport;

   private BluemindAuthClient  client;

   @BeforeEach
   void setUp() {
      transport = mock(HttpClient.class);
      client = new BluemindAuthClient(transport);
   }

   /**
    * The login request, exactly as the vendor's model declares it: the credentials are
    * not a JSON object with fields - the body <i>is</i> the password, as a JSON string,
    * and the account travels in the query.
    */
   @Test
   void logsInWithThePasswordAloneInTheBody() throws Exception {
      givenAnswer(200, "{\"status\":\"Ok\",\"authKey\":\"sid-1\",\"latd\":\"exo.service@19d43481671.internal\"}");

      BluemindSession session = client.login(API_URL, "exo.service@acme.com", "s3cr3t");

      assertEquals("sid-1", session.authKey());
      assertEquals("exo.service@19d43481671.internal", session.latd());
      HttpRequest sent = captureRequest();
      assertEquals("POST", sent.method());
      assertEquals("https://bm.example.com/api/auth/login?login=exo.service%40acme.com&origin=exo-platform",
                   sent.uri().toString());
      assertEquals("\"s3cr3t\"", bodyOf(sent));
      assertEquals("application/json", sent.headers().firstValue("Content-Type").orElse(null));
   }

   /** A password with a quote in it is escaped, not sent raw into the JSON. */
   @Test
   void escapesThePasswordIntoItsJsonString() throws Exception {
      givenAnswer(200, "{\"status\":\"Ok\",\"authKey\":\"sid-1\"}");

      client.login(API_URL, "exo.service@acme.com", "a\"b\\c");

      assertTrue(bodyOf(captureRequest()).contains("a\\\"b\\\\c"), "the password must travel as a valid JSON string");
   }

   /**
    * The sudo request: the route carries an underscore, the target travels in the query,
    * the technical account's token in {@code X-BM-ApiKey}, and there is no body at all -
    * the session being asked for is named by the query, not described in a document.
    */
   @Test
   void sudoesToTheTargetWithTheTokenInTheHeader() throws Exception {
      givenAnswer(200, "{\"status\":\"Ok\",\"authKey\":\"sid-alice\",\"latd\":\"alice@19d43481671.internal\"}");

      BluemindSession session = client.sudo(API_URL, "sid-tech", "alice@acme.com");

      assertEquals("sid-alice", session.authKey());
      HttpRequest sent = captureRequest();
      assertEquals("POST", sent.method());
      assertEquals("https://bm.example.com/api/auth/_su?login=alice%40acme.com", sent.uri().toString());
      assertEquals("sid-tech", sent.headers().firstValue("X-BM-ApiKey").orElse(null));
      assertEquals(0L, sent.bodyPublisher().orElseThrow().contentLength());
   }

   /**
    * <b>The answer that cost an afternoon.</b> On one live 5.7 instance a technical
    * account logged in perfectly, its token worked on other endpoints, and every sudo came
    * back <i>HTTP 200</i> carrying {@code status: Bad} - while on a second instance the
    * same shape of account was allowed. Whatever the right turns out to depend on, the
    * refusal travels as a 200: reading the status code alone would hand the connector an
    * empty session and turn a permissions problem into an incomprehensible failure three
    * layers up.
    */
   @Test
   void refusesTheSudoBlueMindAnswersBadTo() throws Exception {
      givenAnswer(200,
                  "{\"status\":\"Bad\",\"message\":\"Only token from global domain are allowed to do this.\"}");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                            () -> client.sudo(API_URL, "sid-tech", "alice@acme.com"));

      assertTrue(refusal.getMessage().contains("Bad"), refusal.getMessage());
      assertTrue(refusal.getMessage().contains("alice@acme.com"), refusal.getMessage());
   }

   /** The configured URL may end with a slash; the path must not double it. */
   @Test
   void joinsTheConfiguredUrlWhateverItsTrailingSlash() throws Exception {
      givenAnswer(200, "{\"status\":\"Ok\",\"authKey\":\"sid-alice\"}");

      client.sudo("https://bm.example.com/", "sid-tech", "alice@acme.com");

      assertEquals("https://bm.example.com/api/auth/_su?login=alice%40acme.com", captureRequest().uri().toString());
   }

   /** The same 200-is-not-a-success rule on the login side. */
   @Test
   void refusesALoginBlueMindAnswersBadTo() throws Exception {
      givenAnswer(200, "{\"status\":\"Bad\",\"message\":\"Bad login or password\"}");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                            () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));

      assertTrue(refusal.getMessage().contains("exo.service@acme.com"), refusal.getMessage());
   }

   /**
    * A refusal names the account and never the password. The message travels into logs
    * and, through the connectors, into administrator-visible errors - it is the one
    * place a secret leaks without anybody meaning to write it down.
    */
   @Test
   void neverCarriesThePasswordIntoAFailure() throws Exception {
      givenAnswer(200, "{\"status\":\"Bad\",\"message\":\"Bad login or password\"}");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                            () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));

      assertFalse(refusal.getMessage().contains("s3cr3t"), refusal.getMessage());
   }

   /** An HTTP status outside 2xx is a failure even when a body follows. */
   @Test
   void refusesANonSuccessHttpStatus() throws Exception {
      givenAnswer(500, "{\"status\":\"Ok\",\"authKey\":\"sid-1\"}");

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                            () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));

      assertTrue(refusal.getMessage().contains("500"), refusal.getMessage());
   }

   /** An unreachable server is the connector's failure, not a mysterious one. */
   @Test
   void refusesWhenBlueMindCannotBeReached() throws Exception {
      when(transport.<String> send(any(), any())).thenThrow(new IOException("connection refused"));

      assertThrows(ConnectorCredentialsException.class, () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));
   }

   /**
    * A BlueMind that accepts the connection and never answers must not hold the thread:
    * both requests carry a deadline, the transport connects within a bounded time, and a
    * timeout surfaces as the connector's failure.
    */
   @Test
   void neitherCallWaitsForeverOnASilentServer() throws Exception {
      assertEquals(BluemindAuthClient.CONNECT_TIMEOUT, BluemindAuthClient.defaultTransport().connectTimeout().orElse(null));

      givenAnswer(200, "{\"status\":\"Ok\",\"authKey\":\"sid-tech\"}");
      client.login(API_URL, "exo.service@acme.com", "s3cr3t");
      assertEquals(BluemindAuthClient.REQUEST_TIMEOUT, captureRequest().timeout().orElse(null), "the login has a deadline");

      transport = mock(HttpClient.class);
      client = new BluemindAuthClient(transport);
      givenAnswer(200, "{\"status\":\"Ok\",\"authKey\":\"sid-alice\"}");
      client.sudo(API_URL, "sid-tech", "alice@acme.com");
      assertEquals(BluemindAuthClient.REQUEST_TIMEOUT, captureRequest().timeout().orElse(null), "the sudo has a deadline");

      transport = mock(HttpClient.class);
      client = new BluemindAuthClient(transport);
      when(transport.<String> send(any(), any())).thenThrow(new HttpTimeoutException("request timed out"));
      assertThrows(ConnectorCredentialsException.class, () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));
   }

   /** A proxy's HTML error page is not a session either. */
   @Test
   void refusesAnAnswerThatIsNotJson() throws Exception {
      givenAnswer(200, "<html>Gateway timeout</html>");

      assertThrows(ConnectorCredentialsException.class, () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));
   }

   /**
    * An interrupted call restores the flag before it throws: swallowing it would leave
    * the sync thread believing it was never asked to stop.
    */
   @Test
   void restoresTheInterruptFlagBeforeFailing() throws Exception {
      when(transport.<String> send(any(), any())).thenThrow(new InterruptedException("stop"));

      assertThrows(ConnectorCredentialsException.class, () -> client.login(API_URL, "exo.service@acme.com", "s3cr3t"));
      assertTrue(Thread.interrupted(), "the interrupt flag must survive the translation");
   }

   /**
    * A configuration with a hole in it is refused here, before anything is sent: a blank
    * URL would otherwise address {@code /api/auth/login} on nothing at all, and a blank
    * login would ask BlueMind to authenticate the empty account. The administrator is
    * told which piece is missing - the password is named, never shown.
    */
   @Test
   void refusesAnIncompleteLogin() {
      assertTrue(assertThrows(ConnectorCredentialsException.class,
                              () -> client.login(null, "exo.service@acme.com", "s3cr3t")).getMessage().contains("URL"));
      assertTrue(assertThrows(ConnectorCredentialsException.class,
                              () -> client.login(API_URL, "  ", "s3cr3t")).getMessage().contains("login"));
      assertTrue(assertThrows(ConnectorCredentialsException.class,
                              () -> client.login(API_URL, "exo.service@acme.com", "")).getMessage().contains("password"));
      verifyNoInteractions(transport);
   }

   /** The same, on the sudo side - including a token the login step failed to produce. */
   @Test
   void refusesAnIncompleteSudo() {
      assertTrue(assertThrows(ConnectorCredentialsException.class,
                              () -> client.sudo(null, "sid-tech", "alice@acme.com")).getMessage().contains("URL"));
      assertTrue(assertThrows(ConnectorCredentialsException.class,
                              () -> client.sudo(API_URL, null, "alice@acme.com")).getMessage().contains("token"));
      assertTrue(assertThrows(ConnectorCredentialsException.class,
                              () -> client.sudo(API_URL, "sid-tech", " ")).getMessage().contains("account"));
      verifyNoInteractions(transport);
   }

   private void givenAnswer(int status, String body) throws Exception {
      HttpResponse<String> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(status);
      // A non-2xx answer is refused before its body is read, so this one is lenient.
      lenient().when(response.body()).thenReturn(body);
      when(transport.<String> send(any(), any())).thenReturn(response);
   }

   private HttpRequest captureRequest() throws Exception {
      ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
      verify(transport).send(captor.capture(), any());
      return captor.getValue();
   }

   /**
    * The body of a recorded request, drained from its publisher - the only way to see
    * what actually went on the wire.
    *
    * @param request the recorded request
    * @return the body text
    */
   private String bodyOf(HttpRequest request) {
      Flow.Publisher<ByteBuffer> publisher = request.bodyPublisher().orElseThrow();
      StringBuilder body = new StringBuilder();
      publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
         @Override
         public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
         }

         @Override
         public void onNext(ByteBuffer item) {
            body.append(StandardCharsets.UTF_8.decode(item));
         }

         @Override
         public void onError(Throwable throwable) {
            // nothing to do: an in-memory publisher does not fail
         }

         @Override
         public void onComplete() {
            // nothing to do: the builder already holds everything
         }
      });
      return body.toString();
   }
}
