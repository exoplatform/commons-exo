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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

/**
 * Speaks BlueMind's {@code /api/auth} endpoints, and nothing else.
 * <p>
 * Every shape here was read off the vendor's own OpenAPI model and confirmed against a
 * live 5.7 instance - the route is {@code _su} with an underscore, the login body is
 * the password alone as a JSON string, the token travels in {@code X-BM-ApiKey}.
 * <p>
 * <b>An HTTP 200 is not a success.</b> BlueMind answers a refused login and a refused
 * sudo alike with 200 and a {@code status} of {@code Bad} in the body, so the status
 * code is only half the check and the body carries the other half.
 * <p>
 * <b>Only a 401 refuses the request's own authentication.</b> Observed on a live
 * instance: a dead or logged-out session is refused with 401 and
 * {@code AUTHENTICATION_FAIL}; a sudo the account has no right to, or to an unknown
 * account, is 200 with {@code status: Bad}; a 403 came from the load balancer in
 * front of BlueMind, not from BlueMind, so it says nothing about the session.
 * <p>
 * <b>Bounded in time</b>: {@code exo.connector.credentials.bluemind.connectTimeoutSeconds}
 * (default {@value #DEFAULT_CONNECT_TIMEOUT_SECONDS}) and
 * {@code exo.connector.credentials.bluemind.requestTimeoutSeconds} (default
 * {@value #DEFAULT_REQUEST_TIMEOUT_SECONDS}). The session store makes every miss on a
 * key wait for the one call in flight, so a BlueMind that never answers must fail
 * that call rather than park all its waiters.
 */
@Component
public class BluemindAuthClient {

   /** The application name BlueMind records against the session. */
   private static final String ORIGIN = "exo-platform";

   private static final String OK     = "Ok";

   /** Default connect timeout, in seconds. */
   static final int            DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

   /** Default per-request timeout, in seconds. */
   static final int            DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;

   private final HttpClient    httpClient;

   /** How long one call may wait for BlueMind's answer. */
   private final Duration      requestTimeout;

   private final ObjectMapper  mapper = new ObjectMapper();

   @Autowired
   public BluemindAuthClient(@Value("${exo.connector.credentials.bluemind.connectTimeoutSeconds:"
       + DEFAULT_CONNECT_TIMEOUT_SECONDS + "}")
   int connectTimeoutSeconds,
                             @Value("${exo.connector.credentials.bluemind.requestTimeoutSeconds:"
                                 + DEFAULT_REQUEST_TIMEOUT_SECONDS + "}")
                             int requestTimeoutSeconds) {
      this(httpClient(connectTimeoutSeconds), requestTimeoutSeconds);
   }

   BluemindAuthClient(HttpClient httpClient, int requestTimeoutSeconds) {
      this.httpClient = httpClient;
      this.requestTimeout = seconds(requestTimeoutSeconds);
   }

   /**
    * The transport, which never follows a redirect and gives up connecting after the
    * configured delay.
    *
    * @param connectTimeoutSeconds the connect timeout, at least one second
    * @return the client
    */
   static HttpClient httpClient(int connectTimeoutSeconds) {
      return HttpClient.newBuilder()
                       .followRedirects(HttpClient.Redirect.NEVER)
                       .connectTimeout(seconds(connectTimeoutSeconds))
                       .build();
   }

   /**
    * A timeout of at least one second: the JDK refuses a zero or negative one.
    *
    * @param value the configured number of seconds
    * @return the timeout
    */
   private static Duration seconds(int value) {
      return Duration.ofSeconds(Math.max(1, value));
   }

   /**
    * Opens a session for an account, from its own password.
    *
    * @param apiUrl the BlueMind base URL, as the administrator configured it
    * @param login the account to authenticate, {@code user@domain}
    * @param password that account's password
    * @return the session BlueMind opened
    * @throws ConnectorCredentialsException when BlueMind refuses, or cannot be reached
    */
   public BluemindSession login(String apiUrl, String login, String password) throws ConnectorCredentialsException {
      required(apiUrl, "the BlueMind API URL");
      required(login, "the technical account login");
      required(password, "the technical account password");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(URI.create(base(apiUrl) + "/api/auth/login?login=" + escape(login) + "&origin="
                                           + ORIGIN))
                                       .header("Content-Type", "application/json")
                                       .header("Accept", "application/json")
                                       // The body is the password itself, as a JSON string - not an object
                                       // carrying it. Jackson writes it, so a quote or a backslash in the
                                       // password travels escaped rather than breaking the document.
                                       .POST(HttpRequest.BodyPublishers.ofString(asJsonString(password)))
                                       .timeout(requestTimeout)
                                       .build();
      return session(send(request), "log " + login + " in");
   }

   /**
    * Opens a session <i>in the name of</i> another account, from a technical account's
    * own session - BlueMind's sudo.
    * <p>
    * <b>Not every account may sudo</b>, and which ones may is not settled: one live 5.7
    * instance refused every sudo from a technical account of a regular domain (BlueMind's
    * own message points at the global domain), while another accepted them from an
    * account equally outside {@code global.virt}. So the code assumes nothing about the
    * right and reads the answer: a refusal comes back as <b>HTTP 200</b> carrying
    * {@code status: Bad}, and surfaces as this method's exception - the one place an
    * administrator can act on it.
    *
    * @param apiUrl the BlueMind base URL, as the administrator configured it
    * @param apiKey the technical account's own session token
    * @param targetLogin the account to act as
    * @return the session BlueMind opened for that account
    * @throws ConnectorCredentialsException when BlueMind refuses, or cannot be reached
    */
   public BluemindSession sudo(String apiUrl, String apiKey, String targetLogin) throws ConnectorCredentialsException {
      required(apiUrl, "the BlueMind API URL");
      required(apiKey, "the technical account session token");
      required(targetLogin, "the account to act as");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(URI.create(base(apiUrl) + "/api/auth/_su?login=" + escape(targetLogin)))
                                       .header("X-BM-ApiKey", apiKey)
                                       .header("Accept", "application/json")
                                       .POST(HttpRequest.BodyPublishers.noBody())
                                       .timeout(requestTimeout)
                                       .build();
      return session(send(request), "act as " + targetLogin);
   }

   /**
    * The answer of one authentication call, or the reason there is none.
    *
    * @param body the response body
    * @param attempt what was being attempted, for the failure message
    * @return the session BlueMind opened
    * @throws ConnectorCredentialsException when the body says anything but Ok
    */
   private BluemindSession session(String body, String attempt) throws ConnectorCredentialsException {
      JsonNode answer;
      try {
         answer = mapper.readTree(body);
      } catch (Exception e) {
         throw new ConnectorCredentialsException("BlueMind answered something that is not JSON when asked to " + attempt, e);
      }
      String status = text(answer, "status");
      if (!OK.equals(status)) {
         throw new ConnectorCredentialsException("BlueMind refused to " + attempt + ": status " + status + ", "
             + text(answer, "message"));
      }
      return new BluemindSession(text(answer, "authKey"), text(answer, "latd"));
   }

   /**
    * Sends a request and answers its body.
    *
    * @param request the request to send
    * @return the response body
    * @throws ConnectorCredentialsException when the transport fails or the status is not 2xx
    */
   private String send(HttpRequest request) throws ConnectorCredentialsException {
      try {
         HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
         if (response.statusCode() == 401) {
            throw new BluemindAuthenticationException("BlueMind answered HTTP " + response.statusCode() + " on "
                + request.uri().getPath());
         }
         if (response.statusCode() / 100 != 2) {
            throw new ConnectorCredentialsException("BlueMind answered HTTP " + response.statusCode() + " on "
                + request.uri().getPath());
         }
         return response.body();
      } catch (IOException e) {
         throw new ConnectorCredentialsException("Cannot reach BlueMind on " + request.uri().getPath(), e);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new ConnectorCredentialsException("Interrupted while calling BlueMind on " + request.uri().getPath(), e);
      }
   }

   /**
    * Refuses a missing piece of configuration before anything is sent.
    * <p>
    * Named, never shown: the message travels into logs and administrator-visible errors,
    * so it says <i>which</i> value is missing and never what it holds.
    *
    * @param value the value to check
    * @param what how to name it to an administrator
    * @throws ConnectorCredentialsException when the value is blank
    */
   private void required(String value, String what) throws ConnectorCredentialsException {
      if (StringUtils.isBlank(value)) {
         throw new ConnectorCredentialsException("Cannot call BlueMind: " + what + " is not configured");
      }
   }

   /**
    * The base URL without its trailing slash, so the paths below concatenate cleanly.
    *
    * @param apiUrl the configured URL
    * @return the same URL, never ending with a slash
    */
   private String base(String apiUrl) {
      return apiUrl.replaceAll("/+$", "");
   }

   /**
    * A value fit for a query string.
    *
    * @param value the raw value
    * @return its percent-encoded form
    */
   private String escape(String value) {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
   }

   /**
    * A value as a JSON string literal, quotes included.
    *
    * @param value the raw value
    * @return the JSON document holding just that string
    */
   private String asJsonString(String value) {
      try {
         return mapper.writeValueAsString(value);
      } catch (Exception e) {
         // Jackson cannot fail writing a String; the checked signature says otherwise.
         throw new IllegalStateException(e); // NOSONAR
      }
   }

   /**
    * One text field of an answer, or null when it carries none.
    *
    * @param node the parsed answer
    * @param field the field to read
    * @return its text, or null
    */
   private String text(JsonNode node, String field) {
      // readTree never answers null - an empty document is a MissingNode - so the only
      // absence to handle is the field's.
      JsonNode value = node.get(field);
      return value == null || value.isNull() ? null : value.asText();
   }
}
