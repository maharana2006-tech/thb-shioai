package com.multiship.backend.service.output;

import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 output-polish (follow-up #1) — verifies {@link SftpSessionPool}
 * reuses connected sessions, evicts stale ones, and closes everything on
 * {@link SftpSessionPool#shutdown()}.
 */
class SftpSessionPoolTest {

    private static Session mockConnectedSession() {
        Session s = mock(Session.class);
        when(s.isConnected()).thenReturn(true);
        return s;
    }

    @Test
    void sameKeyReturnsSamePooledSession() {
        SftpSessionPool pool = new SftpSessionPool(32, 60);
        AtomicInteger factoryCalls = new AtomicInteger();
        Session s1 = mockConnectedSession();
        Supplier<Session> factory = () -> {
            factoryCalls.incrementAndGet();
            return s1;
        };

        Session first;
        try (SftpSessionPool.Handle h1 = pool.acquire("host|22|user|fp|", factory)) {
            first = h1.session();
        }
        Session second;
        try (SftpSessionPool.Handle h2 = pool.acquire("host|22|user|fp|", factory)) {
            second = h2.session();
        }

        assertSame(first, second, "second acquire on same key should reuse pooled session");
        assertEquals(1, factoryCalls.get(), "factory called exactly once — cached the first result");
    }

    @Test
    void differentKeysCreateSeparateSessions() {
        SftpSessionPool pool = new SftpSessionPool(32, 60);
        Session sA = mockConnectedSession();
        Session sB = mockConnectedSession();

        Session first;
        try (SftpSessionPool.Handle h = pool.acquire("hostA|22|u|fp|", () -> sA)) {
            first = h.session();
        }
        Session second;
        try (SftpSessionPool.Handle h = pool.acquire("hostB|22|u|fp|", () -> sB)) {
            second = h.session();
        }

        assertNotSame(first, second);
        assertEquals(2, pool.size());
    }

    @Test
    void staleSessionIsEvictedAndReplaced() {
        SftpSessionPool pool = new SftpSessionPool(32, 60);
        Session stale = mock(Session.class);
        // First call: reports connected; then after the "server dropped it"
        // any subsequent check reports disconnected.
        when(stale.isConnected()).thenReturn(true).thenReturn(false);
        Session fresh = mockConnectedSession();

        // Warm the pool with the "stale" session.
        try (SftpSessionPool.Handle h = pool.acquire("host|22|u|fp|", () -> stale)) {
            assertSame(stale, h.session());
        }
        // Second acquire: stale reports disconnected → pool evicts, factory
        // supplies the fresh one.
        try (SftpSessionPool.Handle h = pool.acquire("host|22|u|fp|", () -> fresh)) {
            assertSame(fresh, h.session());
        }
        // The stale session's disconnect() should have been called during eviction.
        verify(stale).disconnect();
    }

    @Test
    void invalidateEvictsAndDisconnects() {
        SftpSessionPool pool = new SftpSessionPool(32, 60);
        Session s = mockConnectedSession();

        try (SftpSessionPool.Handle h = pool.acquire("host|22|u|fp|", () -> s)) {
            h.invalidate();
        }
        assertEquals(0, pool.size());
        verify(s).disconnect();
    }

    @Test
    void shutdownClosesAllPooledSessions() {
        SftpSessionPool pool = new SftpSessionPool(32, 60);
        Session a = mockConnectedSession();
        Session b = mockConnectedSession();

        try (SftpSessionPool.Handle h = pool.acquire("keyA", () -> a)) { /* release */ }
        try (SftpSessionPool.Handle h = pool.acquire("keyB", () -> b)) { /* release */ }
        assertEquals(2, pool.size());

        pool.shutdown();

        assertEquals(0, pool.size());
        verify(a).disconnect();
        verify(b).disconnect();
    }

    @Test
    void fingerprintIsStableAndNonReversible() {
        String plaintext = "hunter2";
        String fp1 = SftpSessionPool.fingerprint(plaintext);
        String fp2 = SftpSessionPool.fingerprint(plaintext);
        assertEquals(fp1, fp2, "SHA-256 is deterministic");
        assertEquals(64, fp1.length(), "hex SHA-256 is 64 chars");
        // Different plaintexts produce different fingerprints.
        assertNotSame(SftpSessionPool.fingerprint("other"), fp1);
    }

    @Test
    void buildKeyIncludesAllComponents() {
        String key = SftpSessionPool.buildKey("h", 2222, "u", "authfp", "khfp");
        assertEquals("h|2222|u|authfp|khfp", key);
    }
}
