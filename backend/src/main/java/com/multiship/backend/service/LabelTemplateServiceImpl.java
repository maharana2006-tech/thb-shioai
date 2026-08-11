package com.multiship.backend.service;

import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.repository.LabelTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class LabelTemplateServiceImpl implements LabelTemplateService {

    private final LabelTemplateRepository repo;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp tenantId so a scoped USER cannot
     * resolve a foreign tenant's label template. Optional so pre-PR-E
     * tests that construct the service directly still compile; also
     * PackingSlipServiceImpl passes the platform tenantId==null branch
     * (fallback template lookup) which remains valid.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Autowired
    public LabelTemplateServiceImpl(LabelTemplateRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<LabelTemplate> findById(Long id) {
        Optional<LabelTemplate> found = repo.findById(id);
        // Sprint 50 Tier 0.5 PR F - defence-in-depth: even though
        // /label-templates/{id} today allows any ADMIN/USER, a scoped
        // USER must not load a foreign tenant's row by id enumeration.
        // Platform templates (tenantId == null) remain readable by all.
        if (tenantScope != null) {
            found.map(LabelTemplate::getTenantId)
                    .filter(t -> t != null && !t.isBlank())
                    .ifPresent(tenantScope::requireTenantMatch);
        }
        return found;
    }

    @Override
    public Optional<LabelTemplate> resolve(String tenantId, String templateType) {
        // Sprint 50 Tier 0.5 PR E - clamp the caller-supplied tenantId
        // so a scoped USER cannot request a foreign tenant's template.
        String scoped = clamp(tenantId);
        if (scoped != null && !scoped.isBlank()) {
            Optional<LabelTemplate> matched = repo.findByTenantAndType(scoped, templateType);
            if (matched.isPresent()) return matched;
        }
        return repo.findByTenantAndType(null, templateType);
    }

    @Override
    public Optional<LabelTemplate> findForTenant(String tenantId, String templateType) {
        // Sprint 50 Tier 0.5 PR E - clamp before hitting the repo so a
        // scoped USER can't peek at another tenant's template.
        String scoped = clamp(tenantId);
        String normalised = (scoped == null || scoped.isBlank()) ? null : scoped;
        return repo.findByTenantAndType(normalised, templateType);
    }

    /**
     * Sprint 50 Tier 0.5 PR E - null-safe helper: when the enforcer isn't
     * wired (pure-unit tests) fall back to the raw value; otherwise clamp.
     */
    private String clamp(String tenantId) {
        return tenantScope == null ? tenantId : tenantScope.clampClientCode(tenantId);
    }

    @Override
    public LabelTemplate save(LabelTemplate template) {
        if (template.getTemplateType() == null || template.getTemplateType().isBlank()) {
            template.setTemplateType("PACKING_SLIP");
        }
        if (template.getTenantId() != null && template.getTenantId().isBlank()) {
            template.setTenantId(null);
        }
        LocalDateTime now = LocalDateTime.now();
        if (template.getId() == null) {
            template.setCreatedAt(now);
        }
        template.setUpdatedAt(now);
        return repo.save(template);
    }

    @Override
    public void delete(Long id) {
        // Sprint 50 Tier 0.5 PR H - belt on delete-by-id: load first, verify
        // the row's tenantId matches the caller's scope, then delete. Mirrors
        // the findById belt from PR F. Platform-owned templates (tenantId ==
        // null) stay operator-only via the existing controller SpEL.
        if (tenantScope != null) {
            repo.findById(id)
                    .map(LabelTemplate::getTenantId)
                    .filter(t -> t != null && !t.isBlank())
                    .ifPresent(tenantScope::requireTenantMatch);
        }
        repo.deleteById(id);
    }

    @Override
    public Page<LabelTemplate> list(String search, String templateType, String hasLogo, Pageable pageable) {
        // Normalise to empty-string sentinels so the JPQL '= ''' branches
        // skip cleanly. Nulls would bind as bytea under Postgres +
        // Hibernate and blow up any LOWER(...) call in the query.
        String s = search == null ? "" : search.trim();
        String t = templateType == null ? "" : templateType.trim();
        String h = hasLogo == null ? "" : hasLogo.trim().toUpperCase();
        if (!h.isEmpty() && !"Y".equals(h) && !"N".equals(h)) h = "";
        Page<LabelTemplate> raw = repo.search(s, t, h, pageable);
        // Sprint 50 Tier 0.5 PR H - post-fetch tenant filter so a scoped
        // USER only sees platform templates (tenantId == null) and their
        // own tenant's templates. Simpler than threading an extra param
        // through the repo signature; the page is already bounded.
        if (tenantScope == null) return raw;
        Optional<String> scope = tenantScope.resolveScope();
        if (scope.isEmpty()) return raw;
        String mine = scope.get();
        List<LabelTemplate> filtered = raw.getContent().stream()
                .filter(row -> row.getTenantId() == null
                        || row.getTenantId().isBlank()
                        || mine.equalsIgnoreCase(row.getTenantId().trim()))
                .toList();
        // Rewrap into a PageImpl carrying the same pageable + a total that
        // reflects the filtered content. Total is best-effort — a fully
        // accurate total would require a second scoped count query; the
        // audit UI treats this list as read-only so an approximate total is
        // acceptable and matches the shape of the response.
        return new PageImpl<>(filtered, pageable, filtered.size());
    }
}
