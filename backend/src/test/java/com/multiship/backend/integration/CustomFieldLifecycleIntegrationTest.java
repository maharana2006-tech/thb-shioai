package com.multiship.backend.integration;

import com.multiship.backend.model.CustomFieldDefinition;
import com.multiship.backend.model.CustomFieldDefinition.FieldType;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.CustomFieldDefinitionRepository;
import com.multiship.backend.repository.OrderCustomFieldValueRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.service.CustomFieldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 customfields-be-integration — full custom-field lifecycle
 * exercised against real Postgres via Testcontainers.
 *
 * <p>Covers `/settings/custom-fields` write + read + upsert paths
 * end-to-end: create definition → list → applicable filter → upsert
 * per-order values → get values → delete definition + values.
 *
 * <p>Anti-fallback: reuses {@link MockCarrierConnectorsTestConfig} +
 * {@link ForbidOutboundHttpTestConfig}. Custom-field CRUD is pure DB —
 * no HTTP path — but the guard is belt-and-suspenders for future
 * outbound refactors.
 *
 * <p>Rows namespaced with {@link #TENANT} + {@code CFIT_*} field keys
 * so re-runs stay clean.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class CustomFieldLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "CFIT";
    private static final String KEY_PREFIX = "CFIT_";

    @Autowired
    private CustomFieldService service;
    @Autowired
    private CustomFieldDefinitionRepository defRepo;
    @Autowired
    private OrderCustomFieldValueRepository valueRepo;
    @Autowired
    private OrderRepository orderRepo;

    private Integer testOrderNo;

    @BeforeEach
    void setUp() {
        // Wipe this class' definitions + values so re-runs start clean.
        defRepo.findAll().stream()
                .filter(d -> d.getFieldKey() != null && d.getFieldKey().startsWith(KEY_PREFIX))
                .forEach(d -> defRepo.deleteById(d.getId()));
        valueRepo.findAll().stream()
                .filter(v -> v.getFieldKey() != null && v.getFieldKey().startsWith(KEY_PREFIX))
                .forEach(v -> valueRepo.deleteById(v.getId()));

        // Seed an order this class writes values on. Reuse across tests
        // via a unique orderNo per run to avoid collisions with siblings.
        testOrderNo = 900000 + (int) (System.nanoTime() % 90000);
        Order o = new Order();
        o.setOrderNo(testOrderNo);
        o.setCustNo(TENANT);
        o.setTenantId(TENANT);
        orderRepo.save(o);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup: remove the test order and any lingering values
        // (definitions cascade via constraint; failed intermediate saves
        // shouldn't leak into sibling ITs).
        valueRepo.findByOrderNo(testOrderNo).forEach(v -> valueRepo.deleteById(v.getId()));
        orderRepo.findByOrderNo(testOrderNo).ifPresent(orderRepo::delete);
        SecurityContextHolder.clearContext();
    }

    // ================ helpers ================

    private CustomFieldDefinition textDef(String key, String label) {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setTenantId(TENANT);
        d.setFieldKey(KEY_PREFIX + key);
        d.setLabel(label);
        d.setFieldType(FieldType.TEXT);
        d.setActive(true);
        return d;
    }

    // ================ 1. CREATE + LIST ================

    @Test
    void create_persistsDefinition_andListReturnsIt() {
        CustomFieldDefinition saved = service.saveDefinition(textDef("notes", "Notes"));

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        List<CustomFieldDefinition> listed = service.listAllForTenant(TENANT);
        assertTrue(listed.stream().anyMatch(d -> saved.getId().equals(d.getId())));
    }

    // ================ 2. APPLICABLE (active-only) ================

    @Test
    void applicable_filtersOutInactiveDefinitions() {
        CustomFieldDefinition active = service.saveDefinition(textDef("active", "Active field"));
        CustomFieldDefinition inactive = textDef("inactive", "Inactive field");
        inactive.setActive(false);
        service.saveDefinition(inactive);

        List<CustomFieldDefinition> applicable = service.listApplicable(TENANT);

        List<Long> ourApplicable = applicable.stream()
                .filter(d -> d.getFieldKey() != null && d.getFieldKey().startsWith(KEY_PREFIX))
                .map(CustomFieldDefinition::getId)
                .toList();
        assertTrue(ourApplicable.contains(active.getId()),
                "Active def must be in applicable list.");
        assertFalse(ourApplicable.contains(defRepo.findAll().stream()
                        .filter(d -> (KEY_PREFIX + "inactive").equals(d.getFieldKey()))
                        .findFirst().get().getId()),
                "Inactive def must be excluded from applicable list.");
    }

    // ================ 3. UPDATE (id preserved) ================

    @Test
    void update_preservesId_andCreatedAt() throws Exception {
        CustomFieldDefinition saved = service.saveDefinition(textDef("upd", "Original label"));
        var originalCreatedAt = saved.getCreatedAt();

        // 2ms guarantee for the isAfter comparison.
        Thread.sleep(2);
        saved.setLabel("Updated label");
        CustomFieldDefinition updated = service.saveDefinition(saved);

        assertEquals(saved.getId(), updated.getId());
        assertEquals("Updated label", updated.getLabel());
        assertEquals(originalCreatedAt, updated.getCreatedAt(),
                "Update path must NOT overwrite createdAt.");
        assertTrue(updated.getUpdatedAt().isAfter(originalCreatedAt));
    }

    // ================ 4. VALIDATION ================

    @Test
    void save_blankFieldKey_throwsIllegalArgumentException() {
        CustomFieldDefinition d = textDef("", "Label");
        d.setFieldKey(""); // override
        assertThrows(IllegalArgumentException.class, () -> service.saveDefinition(d));
    }

    @Test
    void save_selectWithoutOptions_throwsIllegalArgumentException() {
        CustomFieldDefinition d = textDef("sel", "Select field");
        d.setFieldType(FieldType.SELECT);
        // no selectOptions
        assertThrows(IllegalArgumentException.class, () -> service.saveDefinition(d));
    }

    // ================ 5. UPSERT VALUES + LOAD VALUES ================

    @Test
    void upsertValues_createsNewValues_andLoadValuesReturnsThem() {
        service.saveDefinition(textDef("notes", "Notes"));

        Map<String, String> saved = service.upsertValues(testOrderNo, TENANT,
                Map.of(KEY_PREFIX + "notes", "fragile"));

        assertEquals("fragile", saved.get(KEY_PREFIX + "notes"));

        Map<String, String> loaded = service.loadValues(testOrderNo);
        assertEquals("fragile", loaded.get(KEY_PREFIX + "notes"));
    }

    @Test
    void upsertValues_updatesExistingValueInPlace() {
        service.saveDefinition(textDef("notes", "Notes"));
        service.upsertValues(testOrderNo, TENANT, Map.of(KEY_PREFIX + "notes", "old"));

        // Second upsert with the same key — updates in place, no duplicate row.
        service.upsertValues(testOrderNo, TENANT, Map.of(KEY_PREFIX + "notes", "new"));

        // Only ONE row per (orderNo, fieldKey).
        long rowsForKey = valueRepo.findByOrderNo(testOrderNo).stream()
                .filter(v -> (KEY_PREFIX + "notes").equals(v.getFieldKey()))
                .count();
        assertEquals(1, rowsForKey);
        assertEquals("new", service.loadValues(testOrderNo).get(KEY_PREFIX + "notes"));
    }

    @Test
    void upsertValues_unknownKey_throwsIllegalArgumentException() {
        // No definition seeded for KEY_PREFIX + "mystery" — should reject.
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertValues(testOrderNo, TENANT,
                        Map.of(KEY_PREFIX + "mystery", "x")));
    }

    // ================ 6. DELETE ================

    @Test
    void delete_removesDefinitionFromDb() {
        CustomFieldDefinition saved = service.saveDefinition(textDef("del", "To delete"));
        assertTrue(defRepo.existsById(saved.getId()));

        service.deleteDefinition(saved.getId());

        assertFalse(defRepo.existsById(saved.getId()));
    }

    @Test
    void delete_unknownId_isNoop() {
        // Just doesn't throw.
        service.deleteDefinition(9999999L);
    }
}
