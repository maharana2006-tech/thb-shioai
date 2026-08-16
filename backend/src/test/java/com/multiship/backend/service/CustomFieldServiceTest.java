package com.multiship.backend.service;

import com.multiship.backend.model.CustomFieldDefinition;
import com.multiship.backend.model.CustomFieldDefinition.FieldType;
import com.multiship.backend.model.OrderCustomFieldValue;
import com.multiship.backend.repository.CustomFieldDefinitionRepository;
import com.multiship.backend.repository.OrderCustomFieldValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Sprint 43 — custom field service coverage: definition validation,
 * per-type value normalisation, upsert semantics.
 */
class CustomFieldServiceTest {

    private CustomFieldDefinitionRepository defRepo;
    private OrderCustomFieldValueRepository valueRepo;
    private CustomFieldServiceImpl service;

    @BeforeEach
    void setUp() {
        defRepo = mock(CustomFieldDefinitionRepository.class);
        valueRepo = mock(OrderCustomFieldValueRepository.class);
        service = new CustomFieldServiceImpl(defRepo, valueRepo);
    }

    /* -------- Sprint 50 Tier 0.5 PR E: tenant-scope -------- */

    @Test
    void scopedUserCannotListForeignTenantDefinitions() throws Exception {
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            CustomFieldServiceImpl scopedService = new CustomFieldServiceImpl(defRepo, valueRepo);
            java.lang.reflect.Field f = CustomFieldServiceImpl.class.getDeclaredField("tenantScope");
            f.setAccessible(true);
            f.set(scopedService, new TenantScopeEnforcer(new com.multiship.backend.config.AccessScopePolicy(true)));

            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.listAllForTenant("OTHER"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ===== saveDefinition =====

    @Test
    void saveDefinition_requiresKeyAndLabel() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setLabel("PO");
        assertThrows(IllegalArgumentException.class, () -> service.saveDefinition(d),
                "Missing fieldKey should reject");

        d.setFieldKey("po");
        d.setLabel(null);
        assertThrows(IllegalArgumentException.class, () -> service.saveDefinition(d),
                "Missing label should reject");
    }

    @Test
    void saveDefinition_selectRequiresOptions() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setTenantId("ARHDEV");
        d.setFieldKey("size");
        d.setLabel("Size");
        d.setFieldType(FieldType.SELECT);
        // no selectOptions
        assertThrows(IllegalArgumentException.class, () -> service.saveDefinition(d));
    }

    @Test
    void saveDefinition_normalisesBlankTenantToNullAndSetsTimestamps() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setTenantId("  ");
        d.setFieldKey("po_number");
        d.setLabel("PO Number");
        when(defRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveDefinition(d);

        ArgumentCaptor<CustomFieldDefinition> cap = ArgumentCaptor.forClass(CustomFieldDefinition.class);
        verify(defRepo).save(cap.capture());
        assertNull(cap.getValue().getTenantId(), "Blank tenantId must normalise to null");
        assertNotNull(cap.getValue().getCreatedAt());
        assertNotNull(cap.getValue().getUpdatedAt());
        assertEquals(FieldType.TEXT, cap.getValue().getFieldType(),
                "Missing fieldType should default to TEXT");
    }

    // ===== normaliseValue (static helper) =====

    @Test
    void normaliseValue_textPreservesTrimmed() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setFieldKey("po_number");
        d.setFieldType(FieldType.TEXT);
        assertEquals("PO-42", CustomFieldServiceImpl.normaliseValue(d, "  PO-42  "));
        assertNull(CustomFieldServiceImpl.normaliseValue(d, "   "));
    }

    @Test
    void normaliseValue_numberStripsTrailingZeros() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setFieldKey("weight_g");
        d.setFieldType(FieldType.NUMBER);
        assertEquals("42", CustomFieldServiceImpl.normaliseValue(d, "42.00"));
        assertEquals("3.14", CustomFieldServiceImpl.normaliseValue(d, "3.14"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomFieldServiceImpl.normaliseValue(d, "abc"));
    }

    @Test
    void normaliseValue_dateRequiresIsoFormat() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setFieldKey("promised_ship_date");
        d.setFieldType(FieldType.DATE);
        assertEquals("2026-07-27",
                CustomFieldServiceImpl.normaliseValue(d, "2026-07-27"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomFieldServiceImpl.normaliseValue(d, "27/07/2026"));
    }

    @Test
    void normaliseValue_selectMustMatchOption() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setFieldKey("size");
        d.setFieldType(FieldType.SELECT);
        d.setSelectOptions("S, M, L");
        assertEquals("M", CustomFieldServiceImpl.normaliseValue(d, "M"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomFieldServiceImpl.normaliseValue(d, "XL"));
    }

    // ===== upsertValues =====

    @Test
    void upsertValues_createsNewValueForApplicableKey() {
        CustomFieldDefinition po = def("po_number", FieldType.TEXT, "ARHDEV");
        when(defRepo.findApplicable("ARHDEV")).thenReturn(List.of(po));
        when(valueRepo.findByOrderAndKey(100, "po_number")).thenReturn(Optional.empty());
        when(valueRepo.findByOrderNo(100)).thenReturn(List.of(
                valueRow(100, "po_number", "PO-42")));
        when(valueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> saved = service.upsertValues(100, "ARHDEV",
                Map.of("po_number", "PO-42"));

        ArgumentCaptor<OrderCustomFieldValue> cap = ArgumentCaptor.forClass(OrderCustomFieldValue.class);
        verify(valueRepo).save(cap.capture());
        assertEquals("PO-42", cap.getValue().getFieldValue());
        assertEquals("po_number", cap.getValue().getFieldKey());
        assertEquals(100, cap.getValue().getOrderNo());
        assertNotNull(cap.getValue().getCreatedAt());
        assertEquals("PO-42", saved.get("po_number"));
    }

    @Test
    void upsertValues_updatesExistingValueInPlace() {
        CustomFieldDefinition po = def("po_number", FieldType.TEXT, "ARHDEV");
        OrderCustomFieldValue existing = valueRow(100, "po_number", "OLD");
        existing.setId(9L);
        java.time.LocalDateTime originalCreated = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        existing.setCreatedAt(originalCreated);

        when(defRepo.findApplicable("ARHDEV")).thenReturn(List.of(po));
        when(valueRepo.findByOrderAndKey(100, "po_number"))
                .thenReturn(Optional.of(existing));
        when(valueRepo.findByOrderNo(100))
                .thenReturn(List.of(valueRow(100, "po_number", "NEW")));
        when(valueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertValues(100, "ARHDEV", Map.of("po_number", "NEW"));

        ArgumentCaptor<OrderCustomFieldValue> cap = ArgumentCaptor.forClass(OrderCustomFieldValue.class);
        verify(valueRepo).save(cap.capture());
        assertEquals(9L, cap.getValue().getId(), "Should update existing row, not create");
        assertEquals("NEW", cap.getValue().getFieldValue());
        assertEquals(originalCreated, cap.getValue().getCreatedAt(),
                "createdAt must be preserved on update");
    }

    @Test
    void upsertValues_unknownKeyThrows() {
        when(defRepo.findApplicable("ARHDEV")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertValues(100, "ARHDEV", Map.of("mystery", "x")));
    }

    @Test
    void upsertValues_typeMismatchThrowsBeforeAnySave() {
        CustomFieldDefinition weight = def("weight_g", FieldType.NUMBER, "ARHDEV");
        when(defRepo.findApplicable("ARHDEV")).thenReturn(List.of(weight));
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertValues(100, "ARHDEV", Map.of("weight_g", "abc")));
        verify(valueRepo, never()).save(any());
    }

    // ===== loadValues =====

    @Test
    void loadValues_returnsMapKeyedByFieldKey() {
        when(valueRepo.findByOrderNo(100)).thenReturn(List.of(
                valueRow(100, "po_number", "PO-42"),
                valueRow(100, "size", "M")));

        Map<String, String> result = service.loadValues(100);

        assertEquals(2, result.size());
        assertEquals("PO-42", result.get("po_number"));
        assertEquals("M", result.get("size"));
    }

    // ===== helpers =====

    private static CustomFieldDefinition def(String key, FieldType type, String tenantId) {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setFieldKey(key);
        d.setLabel(key);
        d.setFieldType(type);
        d.setTenantId(tenantId);
        d.setActive(true);
        return d;
    }

    private static OrderCustomFieldValue valueRow(Integer orderNo, String key, String value) {
        OrderCustomFieldValue v = new OrderCustomFieldValue();
        v.setOrderNo(orderNo);
        v.setFieldKey(key);
        v.setFieldValue(value);
        return v;
    }

    /* -------- Sprint 53 gap-fill: list + delete + upsert edge cases -------- */

    @Test
    void listAllForTenant_delegatesToRepoFindAllForTenant() {
        // No scope enforcer wired → clamp is identity, normalise passes through.
        when(defRepo.findAllForTenant("ARHDEV")).thenReturn(List.of(
                def("notes", FieldType.TEXT, "ARHDEV"),
                def("priority", FieldType.SELECT, "ARHDEV")));

        List<CustomFieldDefinition> result = service.listAllForTenant("ARHDEV");

        assertEquals(2, result.size());
        verify(defRepo, times(1)).findAllForTenant("ARHDEV");
        verify(defRepo, never()).findApplicable(any());
    }

    @Test
    void listApplicable_delegatesToRepoFindApplicable() {
        when(defRepo.findApplicable("ARHDEV")).thenReturn(List.of(
                def("active-only", FieldType.TEXT, "ARHDEV")));

        List<CustomFieldDefinition> result = service.listApplicable("ARHDEV");

        assertEquals(1, result.size());
        verify(defRepo, times(1)).findApplicable("ARHDEV");
        verify(defRepo, never()).findAllForTenant(any());
    }

    @Test
    void saveDefinition_updatePath_preservesCreatedAt_advancesUpdatedAt() throws Exception {
        // Existing id → not a create; createdAt is not touched, only updatedAt.
        CustomFieldDefinition d = def("notes", FieldType.TEXT, "ARHDEV");
        d.setId(42L);
        java.time.LocalDateTime original = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        d.setCreatedAt(original);

        ArgumentCaptor<CustomFieldDefinition> cap = ArgumentCaptor.forClass(CustomFieldDefinition.class);
        when(defRepo.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Give updatedAt clock a tick so isAfter is deterministic.
        Thread.sleep(2);
        service.saveDefinition(d);

        assertEquals(original, cap.getValue().getCreatedAt(),
                "Update path must NOT overwrite createdAt.");
        assertTrue(cap.getValue().getUpdatedAt().isAfter(original),
                "updatedAt must advance beyond the createdAt baseline.");
    }

    @Test
    void deleteDefinition_existingId_delegatesToRepoDelete() {
        CustomFieldDefinition existing = def("notes", FieldType.TEXT, "ARHDEV");
        existing.setId(42L);
        when(defRepo.findById(42L)).thenReturn(Optional.of(existing));

        service.deleteDefinition(42L);

        verify(defRepo, times(1)).delete(existing);
    }

    @Test
    void deleteDefinition_unknownId_isNoop() {
        when(defRepo.findById(9999L)).thenReturn(Optional.empty());

        service.deleteDefinition(9999L);

        verify(defRepo, never()).delete(any(CustomFieldDefinition.class));
    }

    @Test
    void deleteDefinition_platformDefinition_isAllowedForScopedUser() throws Exception {
        // Platform-owned def (tenantId=null) — the filter skips
        // requireTenantMatch, so a scoped USER can drive the flow. In
        // production the controller SpEL (hasRole('ADMIN')) prevents this;
        // here we exercise the service-layer contract.
        putScopedUserAsInBaseTests();
        CustomFieldServiceImpl scoped = withEnforcer(true);
        CustomFieldDefinition platform = new CustomFieldDefinition();
        platform.setId(99L);
        platform.setTenantId(null);
        platform.setFieldKey("k");
        platform.setLabel("K");
        platform.setFieldType(FieldType.TEXT);
        when(defRepo.findById(99L)).thenReturn(Optional.of(platform));

        scoped.deleteDefinition(99L);

        verify(defRepo, times(1)).delete(platform);
        cleanContext();
    }

    @Test
    void deleteDefinition_foreignTenant_throwsAccessDenied_beforeRepoDelete() throws Exception {
        putScopedUserAsInBaseTests();
        CustomFieldServiceImpl scoped = withEnforcer(true);
        CustomFieldDefinition foreign = new CustomFieldDefinition();
        foreign.setId(99L);
        foreign.setTenantId("OTHER");
        foreign.setFieldKey("k");
        foreign.setLabel("K");
        foreign.setFieldType(FieldType.TEXT);
        when(defRepo.findById(99L)).thenReturn(Optional.of(foreign));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> scoped.deleteDefinition(99L));

        verify(defRepo, never()).delete(any(CustomFieldDefinition.class));
        cleanContext();
    }

    @Test
    void upsertValues_emptyMap_returnsLoadValues_withoutTouchingDefinitions() {
        // Documented short-circuit: empty/null map skips all validation +
        // definition lookup, just returns whatever's already stored.
        when(valueRepo.findByOrderNo(100)).thenReturn(List.of(
                valueRow(100, "existing", "kept")));

        Map<String, String> result = service.upsertValues(100, "ARHDEV", Map.of());

        assertEquals(1, result.size());
        assertEquals("kept", result.get("existing"));
        verify(defRepo, never()).findApplicable(any());
        verify(valueRepo, never()).save(any());
    }

    /** Helper: put a scoped USER (ACME) into the SecurityContext. */
    private void putScopedUserAsInBaseTests() {
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** Helper: build a service instance with the tenantScope enforcer wired. */
    private CustomFieldServiceImpl withEnforcer(boolean scopeEnforcementEnabled) throws Exception {
        CustomFieldServiceImpl scoped = new CustomFieldServiceImpl(defRepo, valueRepo);
        java.lang.reflect.Field f = CustomFieldServiceImpl.class.getDeclaredField("tenantScope");
        f.setAccessible(true);
        f.set(scoped, new TenantScopeEnforcer(
                new com.multiship.backend.config.AccessScopePolicy(scopeEnforcementEnabled)));
        return scoped;
    }

    private void cleanContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}
