package com.multiship.backend.service;

import com.multiship.backend.repository.ImportBatchRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saved imports are checked against the settings of the day they were checked: a
 * row failing "clientCode POB250 is not registered" kept failing after POB250 was
 * added, until someone clicked Validate all. When a client, carrier account,
 * client–warehouse link or ship via mapping changes, the imports it can affect
 * are checked again in the background (the app's own checks; UPS isn't asked).
 *
 * <p>Changes are gathered for a moment and run after their transaction commits,
 * one pass at a time. ponytail: one background thread and a full re-check per
 * import — a global mapping change re-checks every unfinished import, fine at
 * today's volumes; queue per import if that ever takes minutes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportRevalidator {

    /** The running instance, for JPA entity listeners (Hibernate doesn't inject them). */
    private static volatile ImportRevalidator instance;
    /** Pending client codes; "" means every client (a platform-wide setting changed). */
    private static final String EVERY_CLIENT = "";

    private final ImportBatchRepository importBatchRepository;
    private final OrderImportServiceImpl orderImportService;

    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "import-revalidator");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void register() {
        instance = this;
    }

    /** Once at startup: settings changed while the server was down (or before this existed) catch up. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void catchUp() {
        queue(EVERY_CLIENT);
    }

    @PreDestroy
    void stop() {
        instance = null;
        executor.shutdownNow();
    }

    /** A setting a row's check depends on changed for this client (null / blank: for every client). */
    public static void settingsChanged(String clientCode) {
        ImportRevalidator self = instance;
        if (self == null) return;
        String key = clientCode == null || clientCode.isBlank() ? EVERY_CLIENT : clientCode.trim().toUpperCase(Locale.ROOT);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.queue(key);
                }
            });
        } else {
            self.queue(key);
        }
    }

    void queue(String key) {
        pending.add(key);
        if (scheduled.compareAndSet(false, true)) {
            executor.schedule(this::run, 2, TimeUnit.SECONDS);
        }
    }

    void run() {
        scheduled.set(false);
        Set<String> keys = new HashSet<>(pending);
        pending.removeAll(keys);
        if (keys.isEmpty()) return;
        try {
            List<Long> ids = keys.contains(EVERY_CLIENT)
                    ? importBatchRepository.findIdsToRecheck()
                    : importBatchRepository.findIdsToRecheckForClients(keys);
            if (!ids.isEmpty()) log.info("Settings changed for {} — checking {} import(s) again", keys, ids.size());
            for (Long id : ids) {
                try {
                    orderImportService.recheckInBackground(id);
                } catch (RuntimeException e) {
                    log.warn("Import {}: background re-check failed: {}", id, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Background re-check of imports failed: {}", e.getMessage());
        }
    }
}
