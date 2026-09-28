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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import org.springframework.stereotype.Service;

import org.exoplatform.services.connector.credentials.ConnectorCredentials;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigFieldType;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.ConnectorProviderConfigStorage;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;
import org.exoplatform.services.connector.credentials.MailConnectorCredentials;
import org.exoplatform.services.connector.credentials.bluemind.BluemindSessionStorage.CachedSession;
import org.exoplatform.services.connector.credentials.bluemind.BluemindSessionStorage.SudoKey;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;

import jakarta.annotation.PostConstruct;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;

/**
 * Authenticates through a BlueMind technical account acting on each user's behalf.
 * <p>
 * The sequence is two calls, not one: the technical account opens its own session,
 * then asks BlueMind for a session <i>in the name of</i> the target user - a "sudo".
 * What comes back is that user's session id, which the connector then presents as a
 * password on IMAP, SMTP or CalDAV. Nothing of the user's own is ever needed.
 * <p>
 * <b>Confirmed end to end against a live 5.7 instance</b>, and these four facts are what
 * {@link #produce(ConnectorCredentialsContext)} is built on:
 * <ul>
 * <li>the session id <b>is</b> accepted as a password on <b>all three channels</b>: a
 * {@code PROPFIND} on the CalDAV root answered 207, an IMAP {@code LOGIN} answered
 * {@code a OK [...] User logged in.} and an SMTP {@code AUTH PLAIN} answered
 * {@code 235 2.0.0 OK} - the mail endpoints being their own hosts, not the webmail's.
 * It opens a <b>REST session</b> too: {@code POST /api/auth/login} with the session id
 * as the password answers {@code Ok}, which is what caldav-integration's own BlueMind
 * conversations (ACL, calendar sharing) ride on - a session id mints another;</li>
 * <li>the Basic login must be the <b>target</b> user, not the technical account: the same
 * session id presented under the technical login was refused with a 401. The identity
 * that authenticates and the identity being served are one and the same, which is why
 * {@link #resolveTargetIdentity(ConnectorCredentialsContext)} feeds both;</li>
 * <li>the target's name is <b>not</b> required in the URL: the server root answered with
 * the target's {@code current-user-principal}, so discovery does the rest;</li>
 * <li>the technical session survives every sudo, and two users' session ids coexist -
 * so one technical session plus one id per user is a sound cache shape (EXO-89647).</li>
 * </ul>
 * <p>
 * Annotated {@code @Service} rather than {@code @Component} for the reason
 * {@link ConnectorCredentialsService} carries: the connector add-ons that resolve
 * through this provider live in other WARs, and only {@code @Service} makes a bean
 * visible across the Spring contexts the kernel bridge merges.
 */
@Service
public class BluemindSudoCredentialsProvider implements ConnectorCredentialsProvider {

   public static final String                NAME = "bluemind-sudo";

   private static final Log                  LOG  = ExoLogger.getLogger(BluemindSudoCredentialsProvider.class);

   private final ConnectorCredentialsService connectorCredentialsService;

   private final ConnectorProviderConfigStorage configStorage;

   private final OrganizationService         organizationService;

   private final BluemindSessionStorage      bluemindSessionStorage;

   public BluemindSudoCredentialsProvider(ConnectorCredentialsService connectorCredentialsService,
                                          ConnectorProviderConfigStorage configStorage,
                                          OrganizationService organizationService,
                                          BluemindSessionStorage bluemindSessionStorage) {
      this.connectorCredentialsService = connectorCredentialsService;
      this.configStorage = configStorage;
      this.organizationService = organizationService;
      this.bluemindSessionStorage = bluemindSessionStorage;
   }

   @PostConstruct
   public void register() {
      connectorCredentialsService.register(this);
      LOG.info("Registered the {} credentials provider", NAME);
   }

   @Override
   public String getName() {
      return NAME;
   }

   /**
    * All three. The vendor confirmed the sudo session is accepted by IMAP and by
    * CalDAV, and SMTP authenticates against the same account.
    */
   @Override
   public Set<ConnectorCredentialsChannel> getSupportedChannels() {
      return EnumSet.allOf(ConnectorCredentialsChannel.class);
   }

   /**
    * False, where the Personal provider returns true - and that single difference is
    * what the whole default-connector feature rests on (EXO-89651 to EXO-89656): a
    * population can be connected without anyone being asked to type a password.
    */
   @Override
   public boolean requiresUserAction() {
      return false;
   }

   /**
    * What an administrator must give for this provider to work on one connector.
    * <p>
    * Four values, and the first is the one the task did not foresee: the connector's
    * own URLs address IMAP, SMTP or CalDAV, never the REST API the sudo is asked for,
    * so the API's address has nowhere else to come from.
    * <p>
    * The help text on the login is load-bearing rather than decorative. Only a token
    * of BlueMind's <b>global</b> domain may sudo - the vendor's own API documentation
    * says so - and an account of a regular domain fails in the most confusing way
    * there is: it logs in, its token is accepted everywhere else in the API, and every
    * sudo answers HTTP 200 with a "Bad" status and no message. Without that sentence
    * on the screen, each installation pays for the discovery.
    */
   @Override
   public List<ConnectorCredentialsConfigField> getConfigurationFields() {
      return List.of(new ConnectorCredentialsConfigField("apiUrl",
                                                         ConnectorCredentialsConfigFieldType.TEXT,
                                                         "credentialsProviderConfig.field.apiUrl",
                                                         "credentialsProviderConfig.field.apiUrl.help",
                                                         true,
                                                         List.of()),
                     new ConnectorCredentialsConfigField("technicalLogin",
                                                         ConnectorCredentialsConfigFieldType.TEXT,
                                                         "credentialsProviderConfig.field.technicalLogin",
                                                         "credentialsProviderConfig.field.technicalLogin.help",
                                                         true,
                                                         List.of()),
                     new ConnectorCredentialsConfigField("technicalSecret",
                                                         ConnectorCredentialsConfigFieldType.SECRET,
                                                         "credentialsProviderConfig.field.technicalSecret",
                                                         "credentialsProviderConfig.field.technicalSecret.help",
                                                         true,
                                                         List.of()),
                     new ConnectorCredentialsConfigField("targetLoginField",
                                                         ConnectorCredentialsConfigFieldType.CHOICE,
                                                         "credentialsProviderConfig.field.targetLoginField",
                                                         "credentialsProviderConfig.field.targetLoginField.help",
                                                         true,
                                                         List.of("username", "email")));
   }

   /** The configuration key naming which eXo field holds the BlueMind login. */
   private static final String TARGET_LOGIN_FIELD = "targetLoginField";

   private static final String API_URL            = "apiUrl";

   private static final String TECHNICAL_LOGIN    = "technicalLogin";

   private static final String TECHNICAL_SECRET   = "technicalSecret";

   /** Its two admitted values. */
   private static final String BY_USERNAME        = "username";

   private static final String BY_EMAIL           = "email";

   /**
    * The BlueMind login of the user this connector is working for.
    * <p>
    * <b>The single derivation of the whole provider.</b>
    * {@link #produce(ConnectorCredentialsContext)} calls this very method to know what
    * to sudo to, as the contract requires: a second, parallel derivation would let the
    * sudo request and the URL the connector addresses drift apart, and that is not an
    * authentication error - it is a valid session pointed at someone else's mailbox,
    * with nothing raised anywhere.
    * <p>
    * Reads the configuration without its secrets: naming a user needs the target field,
    * never the password, and a value not read is a value that cannot leak.
    * <p>
    * Answers null for every case where the user cannot be named - no configuration, no
    * username in the context, a directory that does not know them, a profile with no
    * address, a directory that is down. The contract asks for exactly that: the caller
    * wanted to know who this user is, and "I cannot tell" is an answer, not a failure
    * to propagate.
    *
    * @param context the connector and user the material would be produced for
    * @return the login to sudo to, or null when the user cannot be named
    */
   @Override
   public String resolveTargetIdentity(ConnectorCredentialsContext context) {
      if (context == null || StringUtils.isBlank(context.getUsername())) {
         return null;
      }
      String targetField = configStorage.readWithoutSecrets(context).get(TARGET_LOGIN_FIELD);
      if (BY_USERNAME.equals(targetField)) {
         return context.getUsername();
      }
      if (BY_EMAIL.equals(targetField)) {
         return emailOf(context.getUsername());
      }
      return null;
   }

   /**
    * The address the directory holds for a user, or null when it holds none.
    * <p>
    * The null-user guard is not observable: without it the call below throws an NPE
    * that the very catch underneath turns back into the same null, so no test can
    * separate the two. It is there so an unknown user is answered as an unknown user
    * rather than journalised as a directory failure - a WARN per connection attempt,
    * on a normal situation.
    *
    * @param username the eXo login
    * @return the email address, or null
    */
   private String emailOf(String username) {
      try {
         User user = organizationService.getUserHandler().findUserByName(username);
         return user == null || StringUtils.isBlank(user.getEmail()) ? null : user.getEmail();
      } catch (Exception e) { // NOSONAR - findUserByName declares Exception itself
         LOG.warn("Cannot read the email of user {} to name it on the remote server", username, e);
         return null;
      }
   }

   @Override
   public ConnectorCredentials produce(ConnectorCredentialsContext context) throws ConnectorCredentialsException {
      // The same derivation the connectors call to build their URLs. Deriving the target
      // a second time here is the one mistake this provider cannot afford: it would not
      // fail, it would authenticate someone else's mailbox.
      String target = resolveTargetIdentity(context);
      if (StringUtils.isBlank(target)) {
         throw new ConnectorCredentialsException("Cannot act on BlueMind for user " + context.getUsername()
             + ": no target account could be derived from the configured field");
      }
      Map<String, String> configuration = configStorage.readDecrypted(context);
      // Two calls on a miss - the technical login, then the sudo, which spends the
      // session rather than the password - and none on a hit: both sessions are kept
      // (EXO-89647). The technical secret is handed to the load and never keyed on.
      CachedSession session = bluemindSessionStorage.sudoSession(sudoKey(configuration, target),
                                                                 configuration.get(TECHNICAL_SECRET));
      return material(context.getChannel(), target, session.sessionId(), bluemindSessionStorage.expiresAtMillis(session));
   }

   /**
    * The key of a target session, from the connector's configuration: the same
    * derivation for producing and for invalidating, or an eviction would miss.
    *
    * @param configuration the connector's decrypted configuration
    * @param target the account acted as
    * @return the key
    */
   private SudoKey sudoKey(Map<String, String> configuration, String target) {
      return new SudoKey(configuration.get(API_URL), configuration.get(TECHNICAL_LOGIN), target);
   }

   /**
    * The session id, in the shape the asking channel consumes.
    * <p>
    * The pair is always <i>the target</i> and <i>the target's</i> session id, never the
    * technical account's: a live 5.7 instance refused the same session id presented under
    * the technical login with a 401, and answered 207 under the target's, naming the
    * target in its {@code current-user-principal}.
    * <p>
    * The declared expiry is the moment the kept session leaves the cache: a caller
    * never holds material the cache has already let go of.
    *
    * @param channel the channel the material is produced for
    * @param target the account the session belongs to
    * @param sessionId the session id BlueMind handed back
    * @param expiresAtMillis when the material should be considered stale, null when unknown
    * @return the material for that channel
    */
   private ConnectorCredentials material(ConnectorCredentialsChannel channel,
                                         String target,
                                         String sessionId,
                                         Long expiresAtMillis) {
      if (channel == ConnectorCredentialsChannel.HTTP) {
         String token = Base64.getEncoder().encodeToString((target + ":" + sessionId).getBytes(StandardCharsets.UTF_8));
         return new HttpConnectorCredentials("Basic " + token, expiresAtMillis);
      }
      Authenticator authenticator = new Authenticator() {
         @Override
         protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(target, sessionId);
         }
      };
      return new MailConnectorCredentials(channel, authenticator, expiresAtMillis);
   }

   /**
    * Drops the target session kept for this user, so the next production opens a new
    * one - what a caller does once when BlueMind refuses material that was produced
    * from the cache (EXO-89649). Never throws: an invalidation that cannot derive its
    * key has nothing to drop, and the entry expires anyway.
    * <p>
    * The eviction is unconditional - the contract does not say which material was
    * refused - so when BlueMind drops the sessions of an active user (a restart, an
    * idle timeout), each of that user's consumers refused at that moment invalidates
    * and opens a session of its own: at most one sudo per concurrent consumer, once per
    * such event, never a loop. Accepted rather than widening the contract to carry
    * the refused material.
    */
   @Override
   public void invalidate(ConnectorCredentialsContext context) {
      try {
         String target = resolveTargetIdentity(context);
         if (StringUtils.isNotBlank(target)) {
            // The key reads the two non-secret fields only: no need to decrypt the secret.
            bluemindSessionStorage.evictSudoSession(sudoKey(configStorage.readWithoutSecrets(context), target));
         }
      } catch (Exception e) {
         LOG.debug("Nothing invalidated for user {}: the session key could not be derived", context.getUsername(), e);
      }
   }
}
