package com.multiship.backend.service.output;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Sprint 52 output-polish (follow-up #2) — bounded SFTP session cache for
 * {@link SftpDriver}. Prior behaviour opened a fresh SSH session per
 * dispatch, which was fine for burst jobs (~500 orders / batch) but is
 * wasteful for the continuous customer bulk-label profile that Sprint 52
 * unlocks (thousands of labels/hour, one upload per label). Each fresh
 * handshake is ~200ms + a new TCP + KEX round-trip that presents as a
 * "connection storm" on the customer's SFTP box.
 *
 * <p>Design:
 * <ul>
 *   <li>Caffeine cache, max 32 sessions (bounded so a many-tenant
 *       deployment can't leak file descriptors), idle TTL 60s.</li>
 *   <li>Key = {@code host|port|user|authFingerprint|knownHostsFingerprint}
 *       — the fingerprints are stable SHA-256 hashes of the plaintext
 *       secret material so we NEVER stash the plaintext in the cache key
 *       (and rotations invalidate the cache entry).</li>
 *   <li>{@link RemovalCause} → close the underlying JSch session cleanly
 *       (both explicit evict + idle expiry paths).</li>
 *   <li>Each pooled entry carries a {@link ReentrantLock}. JSch
 *       {@code Session} + {@code ChannelSftp} are NOT thread-safe for
 *       concurrent channels on the same session — callers must hold the
 *       lock across "open channel → put → disconnect channel". Simpler
 *       than pooling Session+Channel pairs and enough throughput for
 *       our workload (one upload / few hundred ms is fast enough that
 *       lock contention is not the bottleneck).</li>
 *   <li>{@link #acquire} returns a fresh session if the pooled one is
 *       no longer connected (jsch tears the socket down on server-side
 *       idle timeout without notifying us).</li>
 *   <li>{@link PreDestroy} closes every pooled session so a graceful
 *       JVM shutdown doesn't leave dangling SSH connections.</li>
 * </ul>
 */
@Slf4j
@Component
public class SftpSessionPool {

    static final int    DEFAULT_MAX_SIZE    = 32;
    static final long   DEFAULT_IDLE_TTL_S  = 60L;

    private final Cache<String, PooledSession> cache;

    public SftpSessionPool(
            @Value("${output.sftp.pool.max-size:32}") int maxSize,
            @Value("${output.sftp.pool.idle-ttl-seconds:60}") long idleTtlSeconds) {
        int max = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        long ttl = idleTtlSeconds > 0 ? idleTtlSeconds : DEFAULT_IDLE_TTL_S;
        this.cache = Caffeine.newBuilder()
                .maximumSize(max)
                .expireAfterAccess(Duration.ofSeconds(ttl))
                // Force the removal listener to run synchronously in the
                // caller thread — we NEED the session to be closed before
                // acquire() re-opens one on the same key, and before
                // shutdown() returns. Caffeine's default async executor
                // (commonPool) makes both properties best-effort.
                .executor(Runnable::run)
                .removalListener((String key, PooledSession value, RemovalCause cause) -> {
                    if (value != null) {
                        closeQuietly(value.session, key, cause);
                    }
                })
                .build();
        log.info("SftpSessionPool ready (maxSize={}, idleTtl={}s)", max, ttl);
    }

    /**
     * Return a live pooled session for the key, opening one via
     * {@code sessionFactory} if the pool has no live entry. The returned
     * handle carries a lock the caller MUST hold across channel open →
     * write → channel close; call {@link Handle#close()} in a finally
     * block to release the lock (session stays in the pool for reuse).
     *
     * <p>If the cached session is no longer connected (server idle drop,
     * network blip) it is evicted and a fresh one is opened.
     */
    public Handle acquire(String key, Supplier<Session> sessionFactory) {
        Objects.requireNonNull(key,            "key");
        Objects.requireNonNull(sessionFactory, "sessionFactory");

        while (true) {
            PooledSession pooled = cache.get(key, k -> new PooledSession(sessionFactory.get()));
            if (pooled == null) {
                // Cache returned null — factory produced null. Should not
                // happen with a well-behaved supplier; be defensive.
                throw new IllegalStateException("SftpSessionPool: session factory returned null for key");
            }
            pooled.lock.lock();
            // With the lock held: check the session is still healthy. If
            // not, evict + retry (the cache.get above will produce a fresh
            // entry on the next spin).
            if (isAlive(pooled.session)) {
                return new Handle(key, pooled);
            }
            log.debug("SftpSessionPool: pooled session for key stale/disconnected — evicting");
            // The Caffeine removal listener is the single source of
            // truth for closing the session — invalidate + cleanUp will
            // run it synchronously.
            cache.invalidate(key);
            cache.cleanUp();
            pooled.lock.unlock();
            // loop back and re-open via the factory
        }
    }

    /**
     * Build a stable cache key. Sensitive material is passed as
     * pre-hashed fingerprints (see {@link #fingerprint(String)}).
     */
    public static String buildKey(String host,
                                  int port,
                                  String username,
                                  String authFingerprint,
                                  String knownHostsFingerprint) {
        return (host == null ? "" : host)
                + "|" + port
                + "|" + (username == null ? "" : username)
                + "|" + (authFingerprint == null ? "" : authFingerprint)
                + "|" + (knownHostsFingerprint == null ? "" : knownHostsFingerprint);
    }

    /**
     * Stable SHA-256 hex fingerprint of the plaintext secret. NEVER put
     * the plaintext itself in the cache key — a heap dump / log leak of
     * the key must not disclose the password.
     */
    public static String fingerprint(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            // SHA-256 is always available on the JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Test / observability hook. */
    public long size() { return cache.estimatedSize(); }

    /** Wipe the pool (used by tests to reset state between cases). */
    public void clear() { cache.invalidateAll(); cache.cleanUp(); }

    @PreDestroy
    public void shutdown() {
        long count = cache.estimatedSize();
        cache.invalidateAll();
        cache.cleanUp();
        log.info("SftpSessionPool shutdown — closed {} pooled sessions", count);
    }

    private static boolean isAlive(Session s) {
        return s != null && s.isConnected();
    }

    private static void closeQuietly(Session s, String key, RemovalCause cause) {
        if (s == null) return;
        try {
            s.disconnect();
            log.debug("SftpSessionPool: closed session for key ({})", cause);
        } catch (Exception ex) {
            log.warn("SftpSessionPool: error closing session for key ({}): {}",
                    cause, ex.getMessage());
        }
    }

    /**
     * Cached entry — the JSch session + the lock guarding channel usage.
     * Package-private so tests can inspect state.
     */
    static final class PooledSession {
        final Session session;
        final ReentrantLock lock = new ReentrantLock();
        PooledSession(Session session) { this.session = session; }
    }

    /**
     * Handle returned by {@link #acquire}. Callers MUST close in a
     * finally block so the underlying lock is released; the session
     * itself stays in the pool for the next dispatch.
     *
     * <p>Also exposes {@link #invalidate()} for the case where the
     * dispatch fails mid-channel and the caller wants to drop the
     * session from the pool rather than risk reusing a wedged one.
     */
    public final class Handle implements AutoCloseable {
        private final String key;
        private final PooledSession entry;
        private boolean closed;

        private Handle(String key, PooledSession entry) {
            this.key = key;
            this.entry = entry;
        }

        public Session session() { return entry.session; }

        /** Evict this session from the pool. Call on any dispatch
         *  error — a wedged jsch session sometimes reports
         *  {@code isConnected() == true} but rejects new channels. The
         *  Caffeine removal listener closes the underlying session. */
        public void invalidate() {
            cache.invalidate(key);
            cache.cleanUp(); // force synchronous listener execution
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (entry.lock.isHeldByCurrentThread()) {
                entry.lock.unlock();
            }
        }
    }
}
