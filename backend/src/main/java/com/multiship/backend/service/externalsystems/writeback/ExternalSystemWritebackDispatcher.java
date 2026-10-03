package com.multiship.backend.service.externalsystems.writeback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.model.WritebackJournalEntity;
import com.multiship.backend.service.TenantSettingsService;
import com.multiship.backend.service.externalsystems.ExternalSystemConfigService;
import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * V89 — bridges the label-generate / label-void success paths to the
 * external-systems framework. Two entry points:
 *
 * <ul>
 *   <li>{@link #dispatchOnGenerate} — called AFTER a label persists
 *       successfully. Redacts unflagged fields, dials the connector.</li>
 *   <li>{@link #dispatchOnClear} — called AFTER a successful void.
 *       Same connection-resolution + flag-matrix rules.</li>
 * </ul>
 *
 * <p><b>Fire-and-forget.</b> Both methods are {@code @Async} on the
 * shared {@code taskExecutor} pool ({@link com.multiship.backend.config.AsyncConfig})
 * so the label-generate path never waits on an external round-trip and
 * a writeback failure never propagates back to the label caller. Every
 * exception is caught and logged at WARN with the connection name +
 * payload keys (never plaintext secrets).
 *
 * <p><b>Connection resolution.</b> The connection to dispatch to is
 * picked in this order (first hit wins):
 * <ol>
 *   <li>tenant setting {@code writebackConnection} for the order's
 *       clientCode (via {@link TenantSettingsService})</li>
 *   <li>whichever row has {@code is_default_writeback_target = TRUE}
 *       (X1 / V110 — admin-flipped from /settings/external-systems;
 *       prior to V110 this was a compile-time literal "nds-default")</li>
 * </ol>
 * A blank clientCode + no default-marked row = silent skip (no external
 * system wired). No side effects — this is the common case for the
 * many tenants that don't use writeback at all.
 *
 * <p><b>Symmetric flags.</b> The six per-connection booleans gate BOTH
 * sides. If {@code writeback_carrier=false}, the carrier is neither
 * sent on generate NOR nulled on void. Redaction happens on
 * {@link WritebackPayload#withRedacted} before the connector sees
 * anything — connectors never inspect flags directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalSystemWritebackDispatcher {

    /** Tenant-setting key naming the writeback connection for this client. */
    public static final String SETTING_WRITEBACK_CONNECTION = "writebackConnection";

    private final ExternalSystemRegistry registry;
    private final ExternalSystemConfigService config;
    private final TenantSettingsService tenantSettings;
    // D1 — every dispatch attempt persists to external_system_writeback_journal.
    private final WritebackJournalService journal;
    // D1b — redispatch() deserializes stored payloads for sweeper + admin retry.
    private final ObjectMapper objectMapper;

    /**
     * Async post-generate hook. The caller (CarrierServiceImpl) invokes
     * this in a try/catch of its own so even bean-lookup failures on
     * the dispatcher can't fail a label.
     *
     * <p>Passing {@code null} payload or a payload whose orderNo is null
     * short-circuits without dialling the connector.
     */
    @Async
    public void dispatchOnGenerate(WritebackPayload payload) {
        dispatchOnGenerate(payload, null);
    }

    /** D1b — chaining overload used by the retry sweeper + admin retry. */
    @Async
    public void dispatchOnGenerate(WritebackPayload payload, Long retryOfId) {
        if (payload == null) return;
        try {
            String name = resolveConnectionName(payload.clientCode());
            if (name == null) {
                log.debug("writeback: skipping generate for order={} client={} — no connection wired",
                        payload.orderNo(), payload.clientCode());
                return;
            }
            Optional<ExternalSystemConnection> row = config.findByName(name);
            if (row.isEmpty()) {
                log.debug("writeback: skipping generate — connection '{}' does not exist", name);
                return;
            }
            if (!row.get().isActive()) {
                log.debug("writeback: skipping generate — connection '{}' inactive", name);
                return;
            }
            if (!isSourceAllowed(payload.source(), row.get())) {
                log.info("writeback: skipping generate on '{}' order={} — source '{}' not enabled",
                        name, payload.orderNo(), payload.source());
                return;
            }
            if (!isChannelAllowed(payload.channel(), row.get())) {
                log.info("writeback: skipping generate on '{}' order={} — channel '{}' not enabled",
                        name, payload.orderNo(), payload.channel());
                return;
            }
            WritebackPayload redacted = redactByFlags(payload, row.get());
            if (allFieldsRedacted(redacted)) {
                log.debug("writeback: skipping generate on '{}' — no flags enabled", name);
                return;
            }
            // D1 — journal the attempt around the connector call. Failure
            // inside the connector still updates the journal to FAILED
            // (WritebackAck.Status.FAILED path); an exception thrown out
            // is caught below and journalled via recordFailure.
            Long journalId = null;
            long start = System.currentTimeMillis();
            try {
                journalId = journal.recordPending(name, row.get().getSystemType(),
                        WritebackJournalService.MODE_GENERATE, redacted, retryOfId).getId();
                WritebackAck ack = registry.writeShipment(name, redacted);
                journal.recordAck(journalId, ack, (int)(System.currentTimeMillis() - start));
                logAck("generate", name, payload.orderNo(), ack);
            } catch (RuntimeException connErr) {
                journal.recordFailure(journalId, connErr.getMessage(),
                        (int)(System.currentTimeMillis() - start));
                throw connErr;  // fall through to the outer catch for logging
            }
        } catch (Exception e) {
            // Ponytail rule: writeback failure MUST NOT bubble. Log at
            // WARN so ops sees it, then swallow.
            log.warn("writeback: generate dispatch failed for order={}: {}",
                    payload.orderNo(), e.getMessage());
        }
    }

    /**
     * Async post-void hook. Same connection-resolution + flag-matrix
     * as {@link #dispatchOnGenerate}.
     */
    @Async
    public void dispatchOnClear(WritebackClearRequest req) {
        dispatchOnClear(req, null);
    }

    /** D1b — chaining overload used by the retry sweeper + admin retry. */
    @Async
    public void dispatchOnClear(WritebackClearRequest req, Long retryOfId) {
        if (req == null) return;
        try {
            String name = resolveConnectionName(req.clientCode());
            if (name == null) {
                log.debug("writeback: skipping clear for order={} client={} — no connection wired",
                        req.orderNo(), req.clientCode());
                return;
            }
            Optional<ExternalSystemConnection> row = config.findByName(name);
            if (row.isEmpty() || !row.get().isActive()) {
                log.debug("writeback: skipping clear — connection '{}' missing/inactive", name);
                return;
            }
            if (noFlagsEnabled(row.get())) {
                log.debug("writeback: skipping clear on '{}' — no flags enabled", name);
                return;
            }
            if (!isSourceAllowed(req.source(), row.get())) {
                log.info("writeback: skipping clear on '{}' order={} — source '{}' not enabled",
                        name, req.orderNo(), req.source());
                return;
            }
            if (!isChannelAllowed(req.channel(), row.get())) {
                log.info("writeback: skipping clear on '{}' order={} — channel '{}' not enabled",
                        name, req.orderNo(), req.channel());
                return;
            }
            // Thread the flag matrix through so per-column connectors
            // (NDS) honour symmetry — same flags gate generate + clear.
            ExternalSystemConnection r = row.get();
            WritebackClearRequest named = new WritebackClearRequest(name, req.trackingNumber(),
                    req.orderNo(), req.clientCode(), req.containerIds(), req.orderNos(),
                    req.connectionSpecific(),
                    Boolean.TRUE.equals(r.getWritebackTracking()),
                    Boolean.TRUE.equals(r.getWritebackShipDate()),
                    Boolean.TRUE.equals(r.getWritebackStatus()),
                    Boolean.TRUE.equals(r.getWritebackCarrier()),
                    Boolean.TRUE.equals(r.getWritebackService()),
                    Boolean.TRUE.equals(r.getWritebackFreight()),
                    req.source(),
                    req.channel());
            // D1 — same journal-around-call pattern as generate.
            Long journalId = null;
            long start = System.currentTimeMillis();
            try {
                journalId = journal.recordPending(name, r.getSystemType(), named, retryOfId).getId();
                WritebackAck ack = registry.clearShipment(name, named);
                journal.recordAck(journalId, ack, (int)(System.currentTimeMillis() - start));
                logAck("clear", name, req.orderNo(), ack);
            } catch (RuntimeException connErr) {
                journal.recordFailure(journalId, connErr.getMessage(),
                        (int)(System.currentTimeMillis() - start));
                throw connErr;
            }
        } catch (Exception e) {
            log.warn("writeback: clear dispatch failed for order={}: {}",
                    req.orderNo(), e.getMessage());
        }
    }

    /**
     * D1b — deserialize a stored journal row's payload and re-fire it
     * through the correct dispatch entry point, with the chain linkage
     * ({@code retryOfId = row.id}, {@code attemptNumber = row.attemptNumber+1})
     * applied by {@link WritebackJournalService#recordPending}.
     *
     * <p>Shared by the admin controller's manual retry and the
     * {@link WritebackJournalSweeper} scheduled bean. Any deserialization
     * failure throws — callers decide how to surface (controller returns
     * 422; sweeper logs and skips).
     */
    public void redispatch(WritebackJournalEntity row) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (row == null || row.getPayloadJson() == null || row.getPayloadJson().isBlank()) {
            throw new IllegalArgumentException("Journal row has no persisted payload");
        }
        if (WritebackJournalService.MODE_GENERATE.equals(row.getMode())) {
            WritebackPayload payload = objectMapper.readValue(row.getPayloadJson(), WritebackPayload.class);
            dispatchOnGenerate(payload, row.getId());
        } else {
            WritebackClearRequest req = objectMapper.readValue(row.getPayloadJson(), WritebackClearRequest.class);
            dispatchOnClear(req, row.getId());
        }
    }

    // ─── internals ─────────────────────────────────────────────────

    /**
     * Resolve which connection to write to. Per-tenant setting wins;
     * fall back to the well-known default. Returns null when nothing
     * is wired (the common case for tenants that don't use writeback).
     */
    String resolveConnectionName(String clientCode) {
        if (clientCode != null && !clientCode.isBlank()) {
            Optional<String> perTenant = tenantSettings.getSetting(clientCode, SETTING_WRITEBACK_CONNECTION);
            if (perTenant.isPresent() && !perTenant.get().isBlank()) {
                return perTenant.get().trim();
            }
        }
        // X1 — DB-flagged default. Null when no row is marked (silent
        // skip is correct for installs that never set one).
        return config.findDefaultWritebackTarget().map(ExternalSystemConnection::getName).orElse(null);
    }

    WritebackPayload redactByFlags(WritebackPayload src, ExternalSystemConnection row) {
        return src.withRedacted(
                Boolean.TRUE.equals(row.getWritebackTracking()),
                Boolean.TRUE.equals(row.getWritebackShipDate()),
                Boolean.TRUE.equals(row.getWritebackStatus()),
                Boolean.TRUE.equals(row.getWritebackCarrier()),
                Boolean.TRUE.equals(row.getWritebackService()),
                Boolean.TRUE.equals(row.getWritebackFreight()));
    }

    static boolean allFieldsRedacted(WritebackPayload p) {
        return p.trackingNumber() == null && p.shipDate() == null && p.status() == null
                && p.carrierCode() == null && p.serviceCode() == null
                && p.freightAmount() == null;
    }

    /**
     * V90 — source gate. {@code null} / blank source is ungated (fires),
     * on the theory that auto/queue paths that couldn't determine
     * origin should default to firing. An unknown / unmapped source
     * (anything outside MANUAL / BULK / API) is also ungated so we
     * don't silently drop future new origins.
     */
    static boolean isSourceAllowed(String source, ExternalSystemConnection row) {
        if (source == null || source.isBlank()) return true;
        return switch (source.trim().toUpperCase()) {
            case "MANUAL" -> Boolean.TRUE.equals(row.getWritebackSourceManual());
            case "BULK"   -> Boolean.TRUE.equals(row.getWritebackSourceBulk());
            case "API"    -> Boolean.TRUE.equals(row.getWritebackSourceApi());
            case "WMS"    -> Boolean.TRUE.equals(row.getWritebackSourceWms());
            case "DTC"    -> Boolean.TRUE.equals(row.getWritebackSourceDtc());
            default -> true;
        };
    }

    /** V90 — channel gate; same null-is-ungated semantics as source. */
    static boolean isChannelAllowed(String channel, ExternalSystemConnection row) {
        if (channel == null || channel.isBlank()) return true;
        return switch (channel.trim().toUpperCase()) {
            case "D2C" -> Boolean.TRUE.equals(row.getWritebackChannelD2c());
            case "B2B" -> Boolean.TRUE.equals(row.getWritebackChannelB2b());
            default -> true;
        };
    }

    static boolean noFlagsEnabled(ExternalSystemConnection row) {
        return !Boolean.TRUE.equals(row.getWritebackTracking())
                && !Boolean.TRUE.equals(row.getWritebackShipDate())
                && !Boolean.TRUE.equals(row.getWritebackStatus())
                && !Boolean.TRUE.equals(row.getWritebackCarrier())
                && !Boolean.TRUE.equals(row.getWritebackService())
                && !Boolean.TRUE.equals(row.getWritebackFreight());
    }

    private static void logAck(String op, String connectionName, Integer orderNo, WritebackAck ack) {
        if (ack == null) {
            log.warn("writeback[{}]: null ack from connection '{}' order={}", op, connectionName, orderNo);
            return;
        }
        switch (ack.status()) {
            case OK -> log.info("writeback[{}]: {} order={} OK: {}", op, connectionName, orderNo, ack.detail());
            case SKIPPED -> log.debug("writeback[{}]: {} order={} SKIPPED: {}", op, connectionName, orderNo, ack.detail());
            case FAILED -> log.warn("writeback[{}]: {} order={} FAILED: {}", op, connectionName, orderNo, ack.detail());
        }
    }
}
