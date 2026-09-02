package com.multiship.backend.service;

import com.multiship.backend.dto.PackageDetailDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V33 (issue #545) — locks in {@code label_batch.packages_json} round-trip.
 * The auto label path ({@link CarrierServiceImpl#generateLabel}) rebuilds
 * a multi-box {@code ShipmentRequestDTO} from this column on retry /
 * regenerate; if serialisation drops fields, the retry silently reverts
 * to a single-box shipment (the pre-V33 regression pattern).
 */
class CarrierServiceImplPackagesJsonTest {

    @Test
    void null_input_serialises_to_null() {
        assertNull(CarrierServiceImpl.serializePackagesJson(null));
    }

    @Test
    void empty_list_serialises_to_null() {
        // Null (not "[]") so the label_batch.packages_json column stays NULL
        // on single-box legacy orders — a stored "[]" would falsely signal
        // "0 packages intended" to the auto-path reader.
        assertNull(CarrierServiceImpl.serializePackagesJson(List.of()));
    }

    @Test
    void null_json_deserialises_to_empty_list() {
        assertTrue(CarrierServiceImpl.deserializePackagesJson(null).isEmpty());
    }

    @Test
    void blank_json_deserialises_to_empty_list() {
        assertTrue(CarrierServiceImpl.deserializePackagesJson("").isEmpty());
        assertTrue(CarrierServiceImpl.deserializePackagesJson("   ").isEmpty());
    }

    @Test
    void malformed_json_deserialises_to_empty_list_not_throws() {
        // Data corruption safety — a single bad row must not break every
        // subsequent label request for the order.
        assertTrue(CarrierServiceImpl.deserializePackagesJson("{not-json").isEmpty());
    }

    @Test
    void round_trip_preserves_per_box_fields() {
        // The exact fields the auto-path shipment builder feeds through — a
        // missing field here would silently degrade the regenerate to
        // shipmentRequest top-level defaults.
        PackageDetailDTO pkg1 = new PackageDetailDTO();
        pkg1.setSequenceNumber(1);
        pkg1.setWeight(new BigDecimal("2.5"));
        pkg1.setWeightUnit("LB");
        pkg1.setLength(new BigDecimal("10.0"));
        pkg1.setWidth(new BigDecimal("8.0"));
        pkg1.setHeight(new BigDecimal("6.0"));
        pkg1.setDimUnit("IN");
        pkg1.setPackageType("YOUR_PACKAGING");
        pkg1.setDeclaredValue(new BigDecimal("100.00"));
        pkg1.setReference("SKU-42");
        pkg1.setDescription("Widget A");

        PackageDetailDTO pkg2 = new PackageDetailDTO();
        pkg2.setSequenceNumber(2);
        pkg2.setWeight(new BigDecimal("1.75"));
        pkg2.setWeightUnit("LB");
        pkg2.setDeclaredValue(new BigDecimal("50.00"));

        String json = CarrierServiceImpl.serializePackagesJson(List.of(pkg1, pkg2));
        assertTrue(json != null && !json.isBlank());

        List<PackageDetailDTO> restored = CarrierServiceImpl.deserializePackagesJson(json);
        assertEquals(2, restored.size());

        PackageDetailDTO r1 = restored.get(0);
        assertEquals(1, r1.getSequenceNumber());
        assertEquals(0, new BigDecimal("2.5").compareTo(r1.getWeight()));
        assertEquals("LB", r1.getWeightUnit());
        assertEquals(0, new BigDecimal("10.0").compareTo(r1.getLength()));
        assertEquals("YOUR_PACKAGING", r1.getPackageType());
        assertEquals("SKU-42", r1.getReference());
        assertEquals("Widget A", r1.getDescription());

        PackageDetailDTO r2 = restored.get(1);
        assertEquals(2, r2.getSequenceNumber());
        assertEquals(0, new BigDecimal("1.75").compareTo(r2.getWeight()));
        assertEquals(0, new BigDecimal("50.00").compareTo(r2.getDeclaredValue()));
    }
}
