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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The BlueMind sessions the sudo provider spends, kept so that producing material
 * does not cost two round-trips every time (EXO-89647).
 * <p>
 * Two stores: the technical account's session, one per BlueMind server and technical
 * login, shared by every user; and the session opened as a target account, one per
 * server, technical login and target, shared by that user's CalDAV and mail
 * connectors. The keys carry every input a session depends on as compared fields, so
 * an administrator who points a connector at another server or another technical
 * account gets fresh sessions at once; the old entries are never asked for again and
 * expire. The technical secret is a parameter of the load, never part of a key.
 * <p>
 * <b>Private memory, not the platform's cache layer</b> - the choice
 * {@code caldav-integration}'s {@code BlueMindSessionCache} made for the same kind of
 * value (EXO-90397): anything registered with the Kernel {@code CacheService} is
 * reachable by name from any code in the container, and a cache is a thing a
 * deployment may replicate. A session acts <i>as a user</i>: it stays in this node's
 * memory, and a second node opens its own.
 * <p>
 * <b>Single flight</b>, by hand: concurrent misses on one key - the IMAP prefetch
 * workers of one user, a wave of logins - wait for the one load in progress instead
 * of each opening a session; a failed load is not kept, and every waiter gets the
 * loader's own exception.
 * <p>
 * <b>Bounded</b>: {@code exo.connector.credentials.bluemind.session.ttlSeconds}
 * (default {@value #DEFAULT_TTL_SECONDS}) and
 * {@code exo.connector.credentials.bluemind.session.maxSessions} (default
 * {@value #DEFAULT_MAX_SESSIONS}) per store; past that number a session is used but
 * not kept. The sudo store needs about one entry per user active within a lifetime -
 * every mail synchronisation and every CalDAV request produces material - so the
 * default is sized for large instances; an entry is a few hundred bytes. A zero lifetime keeps nothing. Residual bound: a load already in flight
 * when its key is evicted still writes back; the lifetime is the backstop.
 */
@Component
public class BluemindSessionStorage {

   /**
    * Default entry lifetime, in seconds. Measured on BlueMind 5 (2026-09-23, a live
    * instance): a session left idle 15 minutes still answered, one left idle 30 minutes
    * was refused with a 401, and a session used every minute was still valid after an
    * hour. A kept session can sit idle in this store, so the bound that matters is the
    * inactivity timeout: the lifetime stays well below 15 minutes.
    */
   static final int                                  DEFAULT_TTL_SECONDS  = 300;

   /** Default number of sessions kept per store and per node. */
   static final int                                  DEFAULT_MAX_SESSIONS = 10000;

   private static final Log                          LOG                  = ExoLogger.getLogger(BluemindSessionStorage.class);

   private final BluemindAuthClient                  bluemindAuthClient;

   private final long                                ttlMillis;

   private final int                                 maxSessions;

   /** Where "now" comes from - a seam, so expiry is tested without waiting. */
   private final LongSupplier                        clock;

   private final Store<TechnicalKey>                 technicalSessions    = new Store<>();

   private final Store<SudoKey>                      sudoSessions         = new Store<>();

   @Autowired
   public BluemindSessionStorage(BluemindAuthClient bluemindAuthClient,
                                 @Value("${exo.connector.credentials.bluemind.session.ttlSeconds:" + DEFAULT_TTL_SECONDS + "}")
                                 int ttlSeconds,
                                 @Value("${exo.connector.credentials.bluemind.session.maxSessions:" + DEFAULT_MAX_SESSIONS + "}")
                                 int maxSessions) {
      this(bluemindAuthClient, ttlSeconds, maxSessions, System::currentTimeMillis);
   }

   BluemindSessionStorage(BluemindAuthClient bluemindAuthClient, int ttlSeconds, int maxSessions, LongSupplier clock) {
      this.bluemindAuthClient = bluemindAuthClient;
      this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
      this.maxSessions = Math.max(0, maxSessions);
      this.clock = clock;
   }

   /**
    * The technical account's session on one BlueMind server, logged in once and kept.
    *
    * @param key the server and the technical login
    * @param technicalSecret the technical account's password, used on a miss only
    * @return the session, with when it was opened
    * @throws ConnectorCredentialsException when BlueMind refuses the login or cannot be reached
    */
   public CachedSession technicalSession(TechnicalKey key, String technicalSecret) throws ConnectorCredentialsException {
      return technicalSessions.get(key, () -> {
         BluemindSession session = bluemindAuthClient.login(key.apiUrl(), key.technicalLogin(), technicalSecret);
         // Only on a miss: in troubleshooting, the absence of this line is the store working.
         LOG.debug("Opened a BlueMind technical session for {} on {}", key.technicalLogin(), key.apiUrl());
         return open(session.authKey());
      });
   }

   /**
    * The session opened as the target account, through the technical session.
    * <p>
    * A kept technical session may have expired on BlueMind's side while still kept:
    * when BlueMind refuses the sudo's own authentication (HTTP 401) with a
    * technical session this call did not open, that session is dropped and the sudo
    * tried once more with a fresh login. Any other failure - an account BlueMind does
    * not know ("status: Bad"), a BlueMind that is down - is the answer and propagates
    * as it is, without spending a login on it. Nothing is kept on a failure: the next
    * call tries again.
    *
    * @param key the server, the technical login and the target account
    * @param technicalSecret the technical account's password, used when a login is needed
    * @return the target's session, with when it was opened
    * @throws ConnectorCredentialsException when BlueMind refuses or cannot be reached
    */
   public CachedSession sudoSession(SudoKey key, String technicalSecret) throws ConnectorCredentialsException {
      return sudoSessions.get(key, () -> {
         long start = clock.getAsLong();
         CachedSession technical = technicalSession(key.technical(), technicalSecret);
         try {
            CachedSession session = open(bluemindAuthClient.sudo(key.apiUrl(), technical.sessionId(), key.target()).authKey());
            LOG.debug("Opened a BlueMind session as {} on {}", key.target(), key.apiUrl());
            return session;
         } catch (BluemindAuthenticationException e) {
            if (technical.openedAtMillis() >= start) {
               throw e;
            }
            LOG.debug("Sudo as {} refused with a kept technical session; retrying once with a fresh login", key.target(), e);
            // Only the session that was refused: another thread may already have
            // replaced it with a fresh one, which must survive this repair.
            technicalSessions.evict(key.technical(), technical);
            CachedSession fresh = technicalSession(key.technical(), technicalSecret);
            CachedSession session = open(bluemindAuthClient.sudo(key.apiUrl(), fresh.sessionId(), key.target()).authKey());
            LOG.debug("Opened a BlueMind session as {} on {} after a fresh technical login", key.target(), key.apiUrl());
            return session;
         }
      });
   }

   /**
    * Drops the session kept for one target, so the next production opens a new one.
    *
    * @param key the server, the technical login and the target account
    */
   public void evictSudoSession(SudoKey key) {
      sudoSessions.evict(key);
   }

   /**
    * Drops the technical session kept for one server and technical login.
    *
    * @param key the server and the technical login
    */
   public void evictTechnicalSession(TechnicalKey key) {
      technicalSessions.evict(key);
   }

   /**
    * When material produced from a target session should be considered stale: the
    * moment its entry expires, so a caller never holds material the store has already
    * let go of. None when the store keeps nothing (zero lifetime): the session's own
    * life is BlueMind's, and unknown here.
    *
    * @param session the target session
    * @return the expiry, in epoch milliseconds, or null when the store keeps nothing
    */
   public Long expiresAtMillis(CachedSession session) {
      return ttlMillis == 0 ? null : session.openedAtMillis() + ttlMillis;
   }

   private CachedSession open(String sessionId) {
      return new CachedSession(sessionId, clock.getAsLong());
   }

   /** A session opening that may fail the way the BlueMind client fails. */
   @FunctionalInterface
   private interface Opening {
      CachedSession open() throws ConnectorCredentialsException;
   }

   /**
    * One store: the kept sessions and the loads in flight, per key.
    *
    * @param <K> the key type, compared with {@code equals}
    */
   private final class Store<K> {

      private final Map<K, CachedSession>                    kept     = new ConcurrentHashMap<>();

      private final Map<K, CompletableFuture<CachedSession>> inFlight = new ConcurrentHashMap<>();

      /** When a full store may next scan for expired entries: a store full of live ones is not rescanned on every production. */
      private volatile long                                  nextPurgeAt;

      CachedSession get(K key, Opening opening) throws ConnectorCredentialsException {
         CachedSession session = live(key);
         if (session != null) {
            return session;
         }
         CompletableFuture<CachedSession> mine = new CompletableFuture<>();
         CompletableFuture<CachedSession> running = inFlight.putIfAbsent(key, mine);
         if (running != null) {
            return await(running);
         }
         try {
            // Re-read once the load is ours: a load that completed between the first
            // read and the claim has already kept its session.
            CachedSession alreadyKept = live(key);
            CachedSession opened = alreadyKept != null ? alreadyKept : opening.open();
            if (alreadyKept == null) {
               keep(key, opened);
            }
            mine.complete(opened);
            return opened;
         } catch (Throwable t) { // NOSONAR - every waiter must be released, whatever the loader threw
            mine.completeExceptionally(t);
            throw t;
         } finally {
            inFlight.remove(key, mine);
         }
      }

      void evict(K key) {
         kept.remove(key);
      }

      /** Drops the entry only while it is still the given session. */
      void evict(K key, CachedSession stale) {
         kept.remove(key, stale);
      }

      /** The kept session when it is still alive; an expired one is dropped on the way. */
      private CachedSession live(K key) {
         CachedSession session = kept.get(key);
         if (session == null) {
            return null;
         }
         if (expired(session)) {
            kept.remove(key, session);
            return null;
         }
         return session;
      }

      private void keep(K key, CachedSession session) {
         if (ttlMillis == 0) {
            return;
         }
         if (kept.size() >= maxSessions && !kept.containsKey(key)) {
            long now = clock.getAsLong();
            if (now >= nextPurgeAt) {
               kept.values().removeIf(BluemindSessionStorage.this::expired);
               nextPurgeAt = now + Math.max(1000L, ttlMillis / 10);
            }
            if (kept.size() >= maxSessions) {
               return;
            }
         }
         kept.put(key, session);
      }

      private CachedSession await(CompletableFuture<CachedSession> running) throws ConnectorCredentialsException {
         try {
            return running.get();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorCredentialsException("Interrupted while waiting for a BlueMind session", e);
         } catch (ExecutionException e) {
            if (e.getCause() instanceof ConnectorCredentialsException refusal) {
               throw refusal;
            }
            if (e.getCause() instanceof RuntimeException failure) {
               throw failure;
            }
            throw new ConnectorCredentialsException("Cannot open a BlueMind session", e.getCause());
         }
      }
   }

   private boolean expired(CachedSession session) {
      return clock.getAsLong() >= session.openedAtMillis() + ttlMillis;
   }

   /**
    * A BlueMind session id and when it was opened.
    *
    * @param sessionId the session id BlueMind handed back
    * @param openedAtMillis when it was opened, in epoch milliseconds
    */
   public record CachedSession(String sessionId, long openedAtMillis) {
      @Override
      public String toString() {
         // the session id is a credential: never in a log line
         return "CachedSession[openedAtMillis=" + openedAtMillis + "]";
      }
   }

   /**
    * Which technical session: a BlueMind server and the technical login used on it.
    *
    * @param apiUrl the BlueMind API URL
    * @param technicalLogin the technical account login
    */
   public record TechnicalKey(String apiUrl, String technicalLogin) {
   }

   /**
    * Which target session: the server, the technical login it is opened through, and
    * the account acted as.
    *
    * @param apiUrl the BlueMind API URL
    * @param technicalLogin the technical account login
    * @param target the account acted as
    */
   public record SudoKey(String apiUrl, String technicalLogin, String target) {
      /**
       * @return the technical session this target session is opened through
       */
      public TechnicalKey technical() {
         return new TechnicalKey(apiUrl, technicalLogin);
      }
   }
}
