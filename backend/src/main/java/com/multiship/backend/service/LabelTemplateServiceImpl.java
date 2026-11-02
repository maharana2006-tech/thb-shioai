package com.multiship.backend.service;

import com.multiship.backend.exception.FieldValidationException;

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

    // BUG-128: audit every create/update/delete so Logs > Entity type
    // LABEL_TEMPLATE actually turns up. @Autowired(required=false) so
    // pure-Mockito tests that construct the service by hand still
    // compile without wiring a mock.
    @Autowired(required = false)
    private AuditService audit;
    @Autowired(required = false)
    private com.multiship.backend.repository.ClientRepository clientRepository;

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

    /**
     * BUG-107 R7 — belt on the client-side magic-byte check in
     * LabelTemplateEditorPage.handleLogo. A direct POST (curl, bulk
     * import, future endpoint) bypasses the browser filter, so re-check
     * the first bytes here. Logo renders into generated commercial
     * invoices — an SVG with embedded JS = stored-XSS in any browser
     * that opens the invoice PDF via a viewer that executes SVG.
     *
     * Accepts: data:image/png;base64,… + PNG magic (89 50 4E 47)
     *          data:image/jpeg;base64,… + JPEG SOI (FF D8 FF)
     * Blank / null is OK — logo is optional.
     */
    static final java.util.Set<String> TEMPLATE_TYPES =
            java.util.Set.of("PACKING_SLIP", "RETURN_COVER", "COMMERCIAL_INVOICE");
    private static final java.util.regex.Pattern HEX_COLOUR = java.util.regex.Pattern.compile("#[0-9A-Fa-f]{6}");

    /** BUG-111 / BUG-115 — the screen's fixed lists are not a contract; the API checks too. */
    private void validateFields(LabelTemplate template) {
        if (!TEMPLATE_TYPES.contains(template.getTemplateType())) {
            throw new FieldValidationException("templateType",
                    "Document type must be one of " + String.join(", ", TEMPLATE_TYPES) + ".");
        }
        if (template.getPrimaryColor() != null && !template.getPrimaryColor().isBlank()
                && !HEX_COLOUR.matcher(template.getPrimaryColor().trim()).matches()) {
            throw new FieldValidationException("primaryColor", "Colour must be a hex value such as #1F150C.");
        }
        if (template.getTenantId() != null && clientRepository != null
                && !clientRepository.existsByClientCodeIgnoreCase(template.getTenantId())) {
            throw new FieldValidationException("tenantId", "Client " + template.getTenantId() + " does not exist.");
        }
    }

    private static void validateLogoBytes(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return;
        boolean png = dataUrl.startsWith("data:image/png;base64,");
        boolean jpg = dataUrl.startsWith("data:image/jpeg;base64,");
        if (!png && !jpg) {
            throw new IllegalArgumentException(
                "logo must be PNG or JPEG (received a different data URL)");
        }
        String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
        if (b64.length() < 8) {
            throw new IllegalArgumentException("logo payload too short to classify");
        }
        byte[] head;
        try {
            head = java.util.Base64.getDecoder().decode(b64.substring(0, 8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("logo base64 is not valid");
        }
        if (png) {
            boolean okPng = head.length >= 4
                    && (head[0] & 0xFF) == 0x89 && (head[1] & 0xFF) == 0x50
                    && (head[2] & 0xFF) == 0x4E && (head[3] & 0xFF) == 0x47;
            if (!okPng) throw new IllegalArgumentException(
                    "logo claims PNG MIME but bytes aren't PNG — refusing to save");
        } else {
            boolean okJpg = head.length >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF;
            if (!okJpg) throw new IllegalArgumentException(
                    "logo claims JPEG MIME but bytes aren't JPEG — refusing to save");
        }
    }

    @Override
    public LabelTemplate save(LabelTemplate template) {
        if (template.getTemplateType() == null || template.getTemplateType().isBlank()) {
            template.setTemplateType("PACKING_SLIP");
        }
        if (template.getTenantId() != null && template.getTenantId().isBlank()) {
            template.setTenantId(null);
        }
        validateLogoBytes(template.getLogoBase64());
        validateFields(template);
        LocalDateTime now = LocalDateTime.now();
        boolean isCreate = template.getId() == null;
        LabelTemplate target = template;
        if (isCreate) {
            template.setCreatedAt(now);
        } else {
            // BUG-110 — toEntity() carries no created_at, so an update used to
            // write NULL into a NOT NULL column (a generic 400). Load the row,
            // apply the editable fields, keep created_at.
            LabelTemplate existing = findById(template.getId()).orElseThrow(() ->
                    new FieldValidationException("id", "Template " + template.getId() + " no longer exists."));
            existing.setTenantId(template.getTenantId());
            existing.setTemplateType(template.getTemplateType());
            existing.setLogoBase64(template.getLogoBase64());
            existing.setPrimaryColor(template.getPrimaryColor());
            existing.setHeaderText(template.getHeaderText());
            existing.setFooterText(template.getFooterText());
            existing.setShowItems(template.getShowItems());
            existing.setLayoutJson(template.getLayoutJson());
            target = existing;
        }
        // BUG-119 — one template per (tenant, type): say so on the Type field with a 409.
        final LabelTemplate candidate = target;
        repo.findByTenantAndType(candidate.getTenantId(), candidate.getTemplateType())
                .filter(other -> other.getId() != null && !other.getId().equals(candidate.getId()))
                .ifPresent(other -> {
                    throw new FieldValidationException("templateType",
                            "A " + candidate.getTemplateType() + " template already exists for "
                                    + (candidate.getTenantId() == null ? "the platform" : candidate.getTenantId()) + ".",
                            org.springframework.http.HttpStatus.CONFLICT);
                });
        target.setUpdatedAt(now);
        LabelTemplate saved = repo.save(target);
        if (audit != null) {
            String key = (saved.getTenantId() == null ? "PLATFORM" : saved.getTenantId())
                    + "/" + saved.getTemplateType();
            audit.record(isCreate ? AuditService.CREATE : AuditService.UPDATE,
                    AuditService.LABEL_TEMPLATE, saved.getId(), key, saved,
                    isCreate ? "Template created." : "Template updated.");
        }
        return saved;
    }

    @Override
    public void delete(Long id) {
        // Sprint 50 Tier 0.5 PR H - belt on delete-by-id: load first, verify
        // the row's tenantId matches the caller's scope, then delete. Mirrors
        // the findById belt from PR F. Platform-owned templates (tenantId ==
        // null) stay operator-only via the existing controller SpEL.
        Optional<LabelTemplate> existing = repo.findById(id);
        if (tenantScope != null) {
            existing.map(LabelTemplate::getTenantId)
                    .filter(t -> t != null && !t.isBlank())
                    .ifPresent(tenantScope::requireTenantMatch);
        }
        repo.deleteById(id);
        if (audit != null && existing.isPresent()) {
            LabelTemplate row = existing.get();
            String key = (row.getTenantId() == null ? "PLATFORM" : row.getTenantId())
                    + "/" + row.getTemplateType();
            audit.record(AuditService.DELETE, AuditService.LABEL_TEMPLATE, id, key, null, "Template deleted.");
        }
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
