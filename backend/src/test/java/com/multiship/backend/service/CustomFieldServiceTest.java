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
}
