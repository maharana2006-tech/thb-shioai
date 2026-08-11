package com.multiship.backend.service;

import com.multiship.backend.model.CustomFieldDefinition;
import com.multiship.backend.model.CustomFieldDefinition.FieldType;
import com.multiship.backend.model.OrderCustomFieldValue;
import com.multiship.backend.repository.CustomFieldDefinitionRepository;
import com.multiship.backend.repository.OrderCustomFieldValueRepository;
import com.multiship.backend.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CustomFieldServiceImpl implements CustomFieldService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CustomFieldDefinitionRepository defRepo;
    private final OrderCustomFieldValueRepository valueRepo;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp tenantId so a scoped USER cannot
     * list / mutate a foreign tenant's custom-field definitions or values.
     * Optional so pre-PR-E tests that construct the service directly
     * without Spring don't have to plumb another dep.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;
    /**
     * Sprint 50 Tier 0.5 PR E - cross-repo tenant lookup on the passed
     * orderNo (belt-and-braces guard in addition to the tenantId clamp).
     * Optional so pre-PR-E tests still compile.
     */
    @Autowired(required = false)
    private OrderRepository orderRepository;

    @Autowired
    public CustomFieldServiceImpl(CustomFieldDefinitionRepository defRepo,
                                  OrderCustomFieldValueRepository valueRepo) {
        this.defRepo = defRepo;
        this.valueRepo = valueRepo;
    }

    @Override
    public List<CustomFieldDefinition> listAllForTenant(String tenantId) {
        return defRepo.findAllForTenant(normalise(clamp(tenantId)));
    }

    @Override
    public List<CustomFieldDefinition> listApplicable(String tenantId) {
        return defRepo.findApplicable(normalise(clamp(tenantId)));
    }

    @Override
    public CustomFieldDefinition saveDefinition(CustomFieldDefinition d) {
        if (d.getFieldKey() == null || d.getFieldKey().isBlank()) {
            throw new IllegalArgumentException("fieldKey is required");
        }
        if (d.getLabel() == null || d.getLabel().isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        d.setTenantId(normalise(d.getTenantId()));
        d.setFieldKey(d.getFieldKey().trim());
        if (d.getFieldType() == null) d.setFieldType(FieldType.TEXT);
        if (d.getRequired() == null) d.setRequired(false);
        if (d.getPosition() == null) d.setPosition(100);
        if (d.getActive() == null) d.setActive(true);
        if (d.getFieldType() == FieldType.SELECT
                && (d.getSelectOptions() == null || d.getSelectOptions().isBlank())) {
            throw new IllegalArgumentException("SELECT fields require selectOptions");
        }
        LocalDateTime now = LocalDateTime.now();
        if (d.getId() == null) d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return defRepo.save(d);
    }

    @Override
    public void deleteDefinition(Long id) {
        defRepo.deleteById(id);
    }

    @Override
    public Map<String, String> loadValues(Integer orderNo) {
        List<OrderCustomFieldValue> values = valueRepo.findByOrderNo(orderNo);
        Map<String, String> out = new LinkedHashMap<>();
        for (OrderCustomFieldValue v : values) {
            out.put(v.getFieldKey(), v.getFieldValue());
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, String> upsertValues(Integer orderNo,
                                            String tenantId,
                                            Map<String, String> values) {
        if (values == null || values.isEmpty()) return loadValues(orderNo);

        // Sprint 50 Tier 0.5 PR E - clamp the caller-supplied tenantId to
        // the caller's own tenant. A scoped USER cannot use another
        // tenant's custom-field definitions.
        String scoped = clamp(tenantId);
        // Sprint 50 Tier 0.5 PR E - defense in depth: also verify the
        // order the caller is writing values on belongs to the caller's
        // tenant, in case tenantId was omitted / manipulated.
        if (tenantScope != null && orderRepository != null && orderNo != null) {
            orderRepository.findByOrderNo(orderNo).ifPresent(o ->
                    tenantScope.requireTenantMatch(
                            StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));
        }
        List<CustomFieldDefinition> applicable = defRepo.findApplicable(normalise(scoped));
        Map<String, CustomFieldDefinition> byKey = applicable.stream()
                .collect(Collectors.toMap(CustomFieldDefinition::getFieldKey, d -> d));

        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String raw = entry.getValue();
            CustomFieldDefinition def = byKey.get(key);
            if (def == null) {
                throw new IllegalArgumentException("Unknown custom field: " + key);
            }
            String normalised = normaliseValue(def, raw);

            OrderCustomFieldValue existing = valueRepo
                    .findByOrderAndKey(orderNo, key)
                    .orElseGet(() -> {
                        OrderCustomFieldValue v = new OrderCustomFieldValue();
                        v.setOrderNo(orderNo);
                        v.setFieldKey(key);
                        v.setCreatedAt(now);
                        return v;
                    });
            existing.setFieldValue(normalised);
            existing.setUpdatedAt(now);
            valueRepo.save(existing);
        }
        return loadValues(orderNo);
    }

    /**
     * Type-check a raw value against a definition. Returns the canonical
     * string form (e.g. numbers strip trailing zeros, dates land on
     * ISO-8601). Blank inputs return null; required-field enforcement
     * happens at commit time, not here.
     */
    static String normaliseValue(CustomFieldDefinition def, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        switch (def.getFieldType()) {
            case TEXT -> {
                return trimmed;
            }
            case NUMBER -> {
                try {
                    return new BigDecimal(trimmed).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid number for " + def.getFieldKey() + ": " + trimmed);
                }
            }
            case DATE -> {
                try {
                    return LocalDate.parse(trimmed, ISO_DATE).toString();
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Invalid date for " + def.getFieldKey() + " (expected yyyy-MM-dd): " + trimmed);
                }
            }
            case SELECT -> {
                Set<String> allowed = Arrays.stream(
                                (def.getSelectOptions() == null ? "" : def.getSelectOptions()).split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());
                if (!allowed.contains(trimmed)) {
                    throw new IllegalArgumentException(
                            def.getFieldKey() + " must be one of " + allowed);
                }
                return trimmed;
            }
        }
        return trimmed;
    }

    private static String normalise(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? null : tenantId;
    }

    /**
     * Sprint 50 Tier 0.5 PR E - null-safe helper: when the enforcer isn't
     * wired (pure-unit tests) fall back to the raw value; otherwise clamp.
     */
    private String clamp(String tenantId) {
        return tenantScope == null ? tenantId : tenantScope.clampClientCode(tenantId);
    }

    /**
     * Loading map surfaced from tests without going through Spring. Kept
     * for pure-unit access.
     */
    static Map<String, String> loadValuesFor(List<OrderCustomFieldValue> values) {
        Map<String, String> out = new HashMap<>();
        for (OrderCustomFieldValue v : values) out.put(v.getFieldKey(), v.getFieldValue());
        return out;
    }
}
