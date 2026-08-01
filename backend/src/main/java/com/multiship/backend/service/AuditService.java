package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

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

    private final AuditLogRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                .build();
        return repo.save(row);
    }

    /** Same as {@link #record(String, String, Object, String, Object, String)}
     *  but for actions with no changes payload (delete + notes-only rows). */
    public AuditLog record(String action, String entityType, Object entityId,
                           String entityKey, String notes) {
        return record(action, entityType, entityId, entityKey, null, notes);
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
