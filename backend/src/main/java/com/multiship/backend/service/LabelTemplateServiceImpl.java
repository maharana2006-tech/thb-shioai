package com.multiship.backend.service;

import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.repository.LabelTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class LabelTemplateServiceImpl implements LabelTemplateService {

    private final LabelTemplateRepository repo;

    @Autowired
    public LabelTemplateServiceImpl(LabelTemplateRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<LabelTemplate> findById(Long id) {
        return repo.findById(id);
    }

    @Override
    public Optional<LabelTemplate> resolve(String tenantId, String templateType) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<LabelTemplate> scoped = repo.findByTenantAndType(tenantId, templateType);
            if (scoped.isPresent()) return scoped;
        }
        return repo.findByTenantAndType(null, templateType);
    }

    @Override
    public Optional<LabelTemplate> findForTenant(String tenantId, String templateType) {
        String normalised = (tenantId == null || tenantId.isBlank()) ? null : tenantId;
        return repo.findByTenantAndType(normalised, templateType);
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
        return repo.search(s, t, h, pageable);
    }
}
