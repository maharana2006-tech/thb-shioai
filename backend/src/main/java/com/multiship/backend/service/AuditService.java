package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Central hook every settings-write endpoint calls to record an
 * audit-trail row. Non-transactional on purpose — the caller decides
 * whether to include it in their own transaction. In practice callers
 * are @Transactional and record() is invoked at the tail of the write
 * so rollback drops the audit line too.
 *
 * <p>Actor is pulled from the SecurityContext (JWT username). Null
 * means system-initiated (background jobs, migrations).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    // ---- Action constants — kept as String not enum so future
    //      call sites can add a new one without a schema change.
    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String TOGGLE_ACTIVE = "TOGGLE_ACTIVE";
    public static final String CASCADE_DISABLE = "CASCADE_DISABLE";
    public static final String CASCADE_ENABLE = "CASCADE_ENABLE";

    // ---- Entity types
    public static final String CLIENT = "CLIENT";
    public static final String WAREHOUSE = "WAREHOUSE";
    public static final String CARRIER_ACCOUNT = "CARRIER_ACCOUNT";
    public static final String LABEL_TEMPLATE = "LABEL_TEMPLATE";
    public static final String ROUTING_RULE = "ROUTING_RULE";
    public static final String CODE_MAP = "CODE_MAP";
    public static final String CUSTOMS_PROFILE = "CUSTOMS_PROFILE";
    public static final String CLIENT_POLICY = "CLIENT_POLICY";
    public static final String CLIENT_MARKUP = "CLIENT_MARKUP";
    public static final String ORDER = "ORDER";
    public static final String IMPORT_BATCH = "IMPORT_BATCH";
    public static final String AUTH = "AUTH";

    // ---- Log categories (Logs page tabs). NULL rows read as ACTIVITY.
    public static final String CAT_ACTIVITY = "ACTIVITY";
    public static final String CAT_SHIPMENT = "SHIPMENT";
    public static final String CAT_ERROR = "ERROR";
    public static final String CAT_SYSTEM = "SYSTEM";

    // ---- Severities
    public static final String SEV_INFO = "INFO";
    public static final String SEV_WARN = "WARN";
    public static final String SEV_ERROR = "ERROR";

    // ---- Event actions (shipment lifecycle + activity)
    public static final String LABEL_GENERATED = "LABEL_GENERATED";
    public static final String LABEL_REGENERATED = "LABEL_REGENERATED";
    public static final String CARRIER_REJECTED = "CARRIER_REJECTED";
    public static final String LABEL_VOIDED = "LABEL_VOIDED";
    public static final String IMPORT_SAVED = "IMPORT_SAVED";
    public static final String IMPORT_GENERATED = "IMPORT_GENERATED";
    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";

    private final AuditLogRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** For the REQUIRES_NEW event writes — optional so plain unit tests
     *  (no Spring tx infrastructure) fall back to a direct save. */
    @Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager txManager;

    // Optional so unit tests that instantiate AuditService by hand (no
    // Spring context) still work. When absent, clientCode falls back to
    // NULL — same as a system-initiated event, harmless for the write path.
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    /**
     * Convenience overload for typical writes: pass an object that
     * will be serialized to JSON in the changes field, plus a
     * short human-readable note.
     */
    public AuditLog record(String action, String entityType, Object entityId,
                           String entityKey, Object changesObject, String notes) {
        String changesJson = null;
        if (changesObject != null) {
            try {
                changesJson = objectMapper.writeValueAsString(changesObject);
            } catch (Exception ex) {
                // Serialization failure should never break the caller's
                // write — audit is a secondary signal. Log + fall through.
                log.warn("Audit changes serialization failed: {}", ex.getMessage());
            }
        }
        AuditLog row = AuditLog.builder()
                .actor(currentActor())
                .action(action)
                .entityType(entityType)
                .entityId(entityId == null ? null : String.valueOf(entityId))
                .entityKey(truncate(entityKey, 200))
                .changes(changesJson)
                .notes(truncate(notes, 500))
                .clientCode(currentClientCode())
                .build();
        return repo.save(row);
    }

    /** Same as {@link #record(String, String, Object, String, Object, String)}
     *  but for actions with no changes payload (delete + notes-only rows). */
    public AuditLog record(String action, String entityType, Object entityId,
                           String entityKey, String notes) {
        return record(action, entityType, entityId, entityKey, null, notes);
    }

    /**
     * Event-log write for the Logs page (shipment lifecycle, errors,
     * activity). Unlike {@link #record}, this is BEST-EFFORT in its own
     * REQUIRES_NEW transaction: a log write must never break (or be rolled
     * back with) the business operation it describes — a carrier rejection
     * marks the ambient tx rollback-only, and the ERROR row must survive it.
     *
     * @param clientCodeOverride the tenant the EVENT belongs to (e.g. the
     *   order's client) — falls back to the acting user's scope. Without it
     *   a platform operator generating for ACME would write a NULL-scope row
     *   that ACME's own users can never see.
     * @param actorOverride actor when the SecurityContext isn't populated
     *   (login endpoint); null → SecurityContext username.
     */
    public void logEvent(String category, String severity, String action,
                         String entityType, Object entityId, String entityKey,
                         Integer orderNo, String notes,
                         String clientCodeOverride, String actorOverride) {
        try {
            AuditLog row = AuditLog.builder()
                    .actor(actorOverride != null ? actorOverride : currentActor())
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId == null ? null : String.valueOf(entityId))
                    .entityKey(truncate(entityKey, 200))
                    .notes(truncate(notes, 500))
                    .clientCode(clientCodeOverride != null && !clientCodeOverride.isBlank()
                            ? clientCodeOverride.trim() : currentClientCode())
                    .category(category)
                    .severity(severity)
                    .orderNo(orderNo)
                    .build();
            if (txManager != null) {
                var tpl = new org.springframework.transaction.support.TransactionTemplate(txManager);
                tpl.setPropagationBehavior(
                        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tpl.executeWithoutResult(st -> repo.save(row));
            } else {
                repo.save(row);
            }
        } catch (Exception ex) {
            // Logging is a secondary signal — never let it break the caller.
            log.warn("Event-log write failed ({} {}): {}", category, action, ex.getMessage());
        }
    }

    /** Shipment-lifecycle event (INFO). */
    public void logShipment(String action, Integer orderNo, String clientCode, String key, String notes) {
        logEvent(CAT_SHIPMENT, SEV_INFO, action, ORDER, orderNo, key, orderNo, notes, clientCode, null);
    }

    /** Error event tied to an order (carrier rejection etc.). */
    public void logOrderError(String action, Integer orderNo, String clientCode, String key, String notes) {
        logEvent(CAT_ERROR, SEV_ERROR, action, ORDER, orderNo, key, orderNo, notes, clientCode, null);
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }

    /**
     * Tenant scope of the acting user, or NULL when the caller is a
     * platform operator or the enforcer is not wired (unit test / system
     * event). NULL rows are only visible to platform operators, which
     * matches the "ADMIN-only view of system events" semantics.
     */
    private String currentClientCode() {
        if (tenantScope == null) return null;
        Optional<String> scope = tenantScope.resolveScope();
        return scope.orElse(null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
