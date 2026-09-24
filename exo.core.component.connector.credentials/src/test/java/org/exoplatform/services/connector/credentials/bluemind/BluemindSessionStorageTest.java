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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.bluemind.BluemindSessionStorage.CachedSession;
import org.exoplatform.services.connector.credentials.bluemind.BluemindSessionStorage.SudoKey;
import org.exoplatform.services.connector.credentials.bluemind.BluemindSessionStorage.TechnicalKey;

/**
 * EXO-89647. The private session store, with a real store and a controlled clock: the
 * BlueMind client is the mock, and counting its calls is what says whether a session
 * was kept.
 */
class BluemindSessionStorageTest {

   private static final String  API    = "https://bm.example.com";

   private static final String  TECH   = "admin0@global.virt";

   private static final String  SECRET = "t0ps3cret";

   private static final SudoKey ALICE  = new SudoKey(API, TECH, "alice@example.com");

   private static final SudoKey BOB    = new SudoKey(API, TECH, "bob@example.com");

   private final AtomicLong     now    = new AtomicLong(1_000_000L);

   private BluemindAuthClient   bluemind;

   private BluemindSessionStorage storage;

   @BeforeEach
   void bluemindAnswers() throws ConnectorCredentialsException {
      bluemind = mock(BluemindAuthClient.class);
      when(bluemind.login(API, TECH, SECRET)).thenReturn(new BluemindSession("sid-tech", "latd"));
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenReturn(new BluemindSession("sid-alice", "latd"));
      when(bluemind.sudo(API, "sid-tech", "bob@example.com")).thenReturn(new BluemindSession("sid-bob", "latd"));
      storage = new BluemindSessionStorage(bluemind, 600, 10000, now::get);
   }

   /** The point of the task: the second production spends no round-trip at all. */
   @Test
   void keepsTheTargetSessionAcrossProductions() throws Exception {
      CachedSession first = storage.sudoSession(ALICE, SECRET);
      CachedSession second = storage.sudoSession(ALICE, SECRET);

      assertSame(first, second);
      assertEquals("sid-alice", second.sessionId());
      verify(bluemind, times(1)).login(API, TECH, SECRET);
      verify(bluemind, times(1)).sudo(API, "sid-tech", "alice@example.com");
   }

   /** One technical login serves every target: a wave of new users spends one login. */
   @Test
   void sharesTheTechnicalSessionBetweenTargets() throws Exception {
      assertEquals("sid-alice", storage.sudoSession(ALICE, SECRET).sessionId());
      assertEquals("sid-bob", storage.sudoSession(BOB, SECRET).sessionId());

      verify(bluemind, times(1)).login(API, TECH, SECRET);
      verify(bluemind, times(2)).sudo(anyString(), anyString(), anyString());
   }

   /** Past its lifetime a session is opened again - and so is the technical one. */
   @Test
   void reopensASessionPastItsLifetime() throws Exception {
      storage.sudoSession(ALICE, SECRET);
      now.addAndGet(600_000L);

      storage.sudoSession(ALICE, SECRET);

      verify(bluemind, times(2)).login(API, TECH, SECRET);
      verify(bluemind, times(2)).sudo(API, "sid-tech", "alice@example.com");
   }

   /**
    * Single flight: concurrent misses on one target - the IMAP prefetch workers of one
    * user - load once. The sudo is slowed down so that every thread misses while the
    * first load is still in flight.
    */
   @Test
   void loadsOnceUnderConcurrentMisses() throws Exception {
      AtomicInteger sudoCalls = new AtomicInteger();
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenAnswer(invocation -> {
         sudoCalls.incrementAndGet();
         Thread.sleep(200);
         return new BluemindSession("sid-alice", "latd");
      });
      List<Future<CachedSession>> sessions = runConcurrently(8);
      for (Future<CachedSession> session : sessions) {
         assertEquals("sid-alice", session.get(5, TimeUnit.SECONDS).sessionId());
      }
      assertEquals(1, sudoCalls.get());
      verify(bluemind, times(1)).login(API, TECH, SECRET);
   }

   /**
    * A waiter interrupted while the load is in flight gives up alone: it restores its
    * interrupt flag and fails, while the load completes and keeps its session for the
    * next call.
    */
   @Test
   void anInterruptedWaiterGivesUpAloneAndTheLoadCompletes() throws Exception {
      CountDownLatch loading = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenAnswer(invocation -> {
         loading.countDown();
         release.await(5, TimeUnit.SECONDS);
         return new BluemindSession("sid-alice", "latd");
      });
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
         Future<CachedSession> loader = pool.submit(() -> storage.sudoSession(ALICE, SECRET));
         assertTrue(loading.await(5, TimeUnit.SECONDS));
         AtomicReference<Throwable> failure = new AtomicReference<>();
         AtomicBoolean interrupted = new AtomicBoolean();
         Thread waiter = new Thread(() -> {
            try {
               storage.sudoSession(ALICE, SECRET);
            } catch (Throwable t) { // NOSONAR - the test records whatever the waiter got
               failure.set(t);
               interrupted.set(Thread.currentThread().isInterrupted());
            }
         });
         waiter.start();
         long deadline = System.currentTimeMillis() + 5_000L;
         while (waiter.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
         }
         waiter.interrupt();
         waiter.join(5_000L);

         assertTrue(failure.get() instanceof ConnectorCredentialsException, String.valueOf(failure.get()));
         assertTrue(failure.get().getMessage().contains("Interrupted"), failure.get().getMessage());
         assertTrue(interrupted.get());
         release.countDown();
         assertEquals("sid-alice", loader.get(5, TimeUnit.SECONDS).sessionId());
         assertEquals("sid-alice", storage.sudoSession(ALICE, SECRET).sessionId());
         verify(bluemind, times(1)).sudo(API, "sid-tech", "alice@example.com");
      } finally {
         release.countDown();
         pool.shutdownNow();
      }
   }

   /** Every thread waiting on a load that fails gets the loader's own exception. */
   @Test
   void everyWaiterGetsTheRefusal() throws Exception {
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenAnswer(invocation -> {
         Thread.sleep(200);
         throw new ConnectorCredentialsException("BlueMind refused to act as alice@example.com: status Bad");
      });
      for (Future<CachedSession> session : runConcurrently(4)) {
         ExecutionException failure = assertThrows(ExecutionException.class, () -> session.get(5, TimeUnit.SECONDS));
         assertTrue(failure.getCause() instanceof ConnectorCredentialsException, String.valueOf(failure.getCause()));
      }
      verify(bluemind, times(1)).sudo(API, "sid-tech", "alice@example.com");
   }

   /**
    * The eviction reaches the entry the load wrote: the next production opens a new
    * target session, through the technical session still kept.
    */
   @Test
   void evictionDropsTheTargetSessionOnly() throws Exception {
      storage.sudoSession(ALICE, SECRET);
      clearInvocations(bluemind);

      storage.evictSudoSession(new SudoKey(API, TECH, "alice@example.com"));
      storage.sudoSession(ALICE, SECRET);

      verify(bluemind, times(1)).sudo(API, "sid-tech", "alice@example.com");
      verify(bluemind, never()).login(anyString(), anyString(), anyString());
   }

   /** Another technical account is another key: fresh sessions at once. */
   @Test
   void aChangedTechnicalAccountGetsItsOwnSessions() throws Exception {
      when(bluemind.login(API, "other@global.virt", SECRET)).thenReturn(new BluemindSession("sid-other", "latd"));
      when(bluemind.sudo(API, "sid-other", "alice@example.com")).thenReturn(new BluemindSession("sid-alice-2", "latd"));

      String before = storage.sudoSession(ALICE, SECRET).sessionId();
      String after = storage.sudoSession(new SudoKey(API, "other@global.virt", "alice@example.com"), SECRET).sessionId();

      assertNotEquals(before, after);
      verify(bluemind, times(1)).login(API, "other@global.virt", SECRET);
   }

   /**
    * A technical session that expired on BlueMind's side while still kept: the sudo's
    * authentication is refused with it, the kept technical session is dropped, one
    * fresh login, one more sudo.
    */
   @Test
   void retriesOnceWithAFreshLoginWhenTheKeptTechnicalSessionIsStale() throws Exception {
      storage.technicalSession(new TechnicalKey(API, TECH), SECRET);
      now.addAndGet(1_000L);
      when(bluemind.login(API, TECH, SECRET)).thenReturn(new BluemindSession("sid-tech-2", "latd"));
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenThrow(new BluemindAuthenticationException("BlueMind answered HTTP 401 on /api/auth/_su"));
      when(bluemind.sudo(API, "sid-tech-2", "alice@example.com")).thenReturn(new BluemindSession("sid-alice", "latd"));

      assertEquals("sid-alice", storage.sudoSession(ALICE, SECRET).sessionId());

      verify(bluemind, times(2)).login(API, TECH, SECRET);
   }

   /**
    * A refusal BlueMind states in its answer - an account it does not know - is the
    * answer: no retry and no extra login, even with a kept technical session; the
    * loader's own exception comes out; nothing is kept, so the next call asks again,
    * still through the kept technical session.
    */
   @Test
   void aRefusalIsNotRetriedNorKeptAndKeepsItsType() throws Exception {
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenThrow(new ConnectorCredentialsException("BlueMind refused to act as alice@example.com: status Bad"));

      ConnectorCredentialsException refusal = assertThrows(ConnectorCredentialsException.class,
                                                           () -> storage.sudoSession(ALICE, SECRET));
      assertEquals("BlueMind refused to act as alice@example.com: status Bad", refusal.getMessage());
      now.addAndGet(1_000L);
      assertThrows(ConnectorCredentialsException.class, () -> storage.sudoSession(ALICE, SECRET));

      verify(bluemind, times(2)).sudo(API, "sid-tech", "alice@example.com");
      verify(bluemind, times(1)).login(API, TECH, SECRET);
   }

   /**
    * An authentication refusal with a technical session this very call opened is not a
    * stale session: no second login, the refusal propagates.
    */
   @Test
   void anAuthenticationRefusalWithAFreshTechnicalSessionIsNotRetried() throws Exception {
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenThrow(new BluemindAuthenticationException("BlueMind answered HTTP 401 on /api/auth/_su"));

      assertThrows(BluemindAuthenticationException.class, () -> storage.sudoSession(ALICE, SECRET));

      verify(bluemind, times(1)).login(API, TECH, SECRET);
   }

   /** Past the bound a session is used but not kept: the store never grows unbounded. */
   @Test
   void usesButDoesNotKeepPastItsBound() throws Exception {
      storage = new BluemindSessionStorage(bluemind, 600, 1, now::get);
      storage.sudoSession(ALICE, SECRET);

      assertEquals("sid-bob", storage.sudoSession(BOB, SECRET).sessionId());
      storage.sudoSession(BOB, SECRET);

      verify(bluemind, times(2)).sudo(API, "sid-tech", "bob@example.com");
      verify(bluemind, times(1)).sudo(API, "sid-tech", "alice@example.com");
   }

   /** The shipped defaults: five minutes, and room for a large instance's active users. */
   @Test
   void shipsDefaultsSizedForLargeInstances() {
      assertEquals(300, BluemindSessionStorage.DEFAULT_TTL_SECONDS);
      assertEquals(10_000, BluemindSessionStorage.DEFAULT_MAX_SESSIONS);
   }

   /** A zero lifetime keeps nothing: every production opens its sessions, as before EXO-89647. */
   @Test
   void aZeroLifetimeKeepsNothing() throws Exception {
      storage = new BluemindSessionStorage(bluemind, 0, 10000, now::get);
      storage.sudoSession(ALICE, SECRET);
      storage.sudoSession(ALICE, SECRET);

      verify(bluemind, times(2)).login(API, TECH, SECRET);
   }

   /** The declared expiry is the moment the kept session leaves the store. */
   @Test
   void declaresTheExpiryTheStoreKeeps() throws Exception {
      CachedSession session = storage.sudoSession(ALICE, SECRET);

      assertEquals(Long.valueOf(session.openedAtMillis() + 600_000L), storage.expiresAtMillis(session));
   }

   /** The session id is a credential: it never shows in a log line. */
   @Test
   void neverPrintsTheSessionId() throws Exception {
      assertFalse(storage.sudoSession(ALICE, SECRET).toString().contains("sid-alice"));
   }

   /**
    * Round 1: the repair of a stale technical session drops that session only. Here
    * another thread already replaced it while this call's sudo was being refused: the
    * replacement survives, and no third login is spent.
    */
   @Test
   void repairingAStaleTechnicalSessionSparesTheOneAnotherThreadOpened() throws Exception {
      TechnicalKey technicalKey = new TechnicalKey(API, TECH);
      storage.technicalSession(technicalKey, SECRET);
      now.addAndGet(1_000L);
      when(bluemind.login(API, TECH, SECRET)).thenReturn(new BluemindSession("sid-tech-2", "latd"));
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenAnswer(invocation -> {
         // Another consumer, refused a moment earlier, has already repaired it.
         storage.evictTechnicalSession(technicalKey);
         storage.technicalSession(technicalKey, SECRET);
         throw new BluemindAuthenticationException("BlueMind answered HTTP 401 on /api/auth/_su");
      });
      when(bluemind.sudo(API, "sid-tech-2", "alice@example.com")).thenReturn(new BluemindSession("sid-alice", "latd"));

      assertEquals("sid-alice", storage.sudoSession(ALICE, SECRET).sessionId());

      verify(bluemind, times(2)).login(API, TECH, SECRET);
   }

   /** Round 1: a loader failing with an Error releases every waiter instead of leaving them blocked. */
   @Test
   void anErrorInTheLoaderReleasesEveryWaiter() throws Exception {
      when(bluemind.sudo(API, "sid-tech", "alice@example.com")).thenAnswer(invocation -> {
         Thread.sleep(200);
         throw new AssertionError("loader broke");
      });
      for (Future<CachedSession> session : runConcurrently(3)) {
         assertThrows(ExecutionException.class, () -> session.get(5, TimeUnit.SECONDS));
      }
   }

   /**
    * Round 1: a full store makes room by dropping its expired entries - without it, a
    * store that has once been full would keep nothing any more.
    */
   @Test
   void aFullStoreMakesRoomByDroppingExpiredEntries() throws Exception {
      storage = new BluemindSessionStorage(bluemind, 600, 1, now::get);
      storage.sudoSession(ALICE, SECRET);
      now.addAndGet(600_000L);

      storage.sudoSession(BOB, SECRET);
      storage.sudoSession(BOB, SECRET);

      verify(bluemind, times(1)).sudo(org.mockito.ArgumentMatchers.eq(API), anyString(), org.mockito.ArgumentMatchers.eq("bob@example.com"));
   }

   /** Round 1: a store that keeps nothing declares no expiry for what it produced. */
   @Test
   void aZeroLifetimeDeclaresNoExpiry() throws Exception {
      storage = new BluemindSessionStorage(bluemind, 0, 10000, now::get);

      assertEquals(null, storage.expiresAtMillis(storage.sudoSession(ALICE, SECRET)));
   }

   private List<Future<CachedSession>> runConcurrently(int threads) {
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<CachedSession>> sessions = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
         sessions.add(pool.submit(() -> {
            go.await();
            return storage.sudoSession(ALICE, SECRET);
         }));
      }
      go.countDown();
      pool.shutdown();
      return sessions;
   }
}
