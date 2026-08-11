package com.multiship.backend.repository;

import com.multiship.backend.model.AuditLog;
import com.multiship.backend.service.TenantScopeEnforcer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 50 Tier 0.5 PR H - implementation of {@link AuditLogRepositoryCustom#search}.
 *
 * <p>Adds a tenant-scope predicate so a scoped USER only sees rows whose
 * {@code entityKey} matches their tenant OR whose {@code actor} is themselves
 * (so they can still see their own writes even if the entityKey stored isn't
 * their tenant code). Platform operators (empty scope) see everything —
 * predicate is a no-op.
 *
 * <p>Kept as a Criteria-style JPQL string with a fluent {@code AND} builder so
 * the base query mirrors the shape the previous {@code @Query} annotation had.
 */
public class AuditLogRepositoryCustomImpl implements AuditLogRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    /**
     * Optional so pure-Spring-Data tests that don't wire security still get
     * the previous org-wide behaviour. When null, the scope predicate is
     * skipped altogether.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Override
    public Page<AuditLog> search(String actor,
                                 String entityType,
                                 String action,
                                 String entityKey,
                                 LocalDateTime since,
                                 LocalDateTime until,
                                 Pageable pageable) {
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        String self = scope.isPresent() ? currentActor() : null;

        // Base predicates — same shape as the previous @Query. Kept as string
        // JPQL rather than Criteria API to preserve the empty-sentinel idiom
        // that dodges Postgres bytea-null-typing on nullable String params.
        String where = ""
                + "WHERE (:actor = '' OR LOWER(COALESCE(a.actor, '')) LIKE CONCAT('%', LOWER(:actor), '%')) "
                + "  AND (:entityType = '' OR a.entityType = :entityType) "
                + "  AND (:action = '' OR a.action = :action) "
                + "  AND (:entityKey = '' OR LOWER(COALESCE(a.entityKey, '')) LIKE CONCAT('%', LOWER(:entityKey), '%')) "
                + "  AND a.createdAt >= :since "
                + "  AND a.createdAt <= :until";
        if (scope.isPresent()) {
            // Tenant-scoped caller: rows whose entityKey matches their tenant
            // OR rows they authored themselves. entityKey typically holds the
            // client code / warehouse code so an equality check is sufficient
            // — most writes with a tenant context store the tenant code as
            // entityKey.
            where += " AND (UPPER(COALESCE(a.entityKey, '')) = UPPER(:scope) "
                    + " OR LOWER(COALESCE(a.actor, '')) = LOWER(:self))";
        }

        String orderBy = buildOrderBy(pageable.getSort());
        String selectJpql = "SELECT a FROM AuditLog a " + where + orderBy;
        String countJpql = "SELECT COUNT(a) FROM AuditLog a " + where;

        TypedQuery<AuditLog> q = em.createQuery(selectJpql, AuditLog.class);
        TypedQuery<Long> countQ = em.createQuery(countJpql, Long.class);
        bindCommon(q, actor, entityType, action, entityKey, since, until, scope, self);
        bindCommon(countQ, actor, entityType, action, entityKey, since, until, scope, self);

        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<AuditLog> content = q.getResultList();
        Long total = countQ.getSingleResult();
        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    private static void bindCommon(TypedQuery<?> q, String actor, String entityType, String action,
                                   String entityKey, LocalDateTime since, LocalDateTime until,
                                   Optional<String> scope, String self) {
        q.setParameter("actor", actor);
        q.setParameter("entityType", entityType);
        q.setParameter("action", action);
        q.setParameter("entityKey", entityKey);
        q.setParameter("since", since);
        q.setParameter("until", until);
        if (scope.isPresent()) {
            q.setParameter("scope", scope.get());
            q.setParameter("self", self == null ? "" : self);
        }
    }

    /**
     * Build an {@code ORDER BY} fragment from the pageable's sort. The
     * previous @Query relied on Spring Data appending this automatically;
     * for a hand-built JPQL string we do the same manually. Falls back to
     * {@code createdAt DESC} when the caller didn't specify a sort.
     */
    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY a.createdAt DESC";
        }
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;
        for (Sort.Order o : sort) {
            if (!first) sb.append(", ");
            // Only whitelist known-safe property names to avoid injection.
            String prop = switch (o.getProperty()) {
                case "createdAt" -> "a.createdAt";
                case "actor" -> "a.actor";
                case "action" -> "a.action";
                case "entityType" -> "a.entityType";
                case "entityKey" -> "a.entityKey";
                default -> "a.createdAt";
            };
            sb.append(prop).append(o.isAscending() ? " ASC" : " DESC");
            first = false;
        }
        return sb.toString();
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }
}
