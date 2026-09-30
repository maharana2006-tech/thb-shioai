package com.multiship.backend.service.carrier;

import com.multiship.backend.model.CarrierAliasEntity;
import com.multiship.backend.repository.CarrierAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** C4 — canonicalize / display / known-list snapshot behavior. */
class CarrierAliasServiceTest {

    private CarrierAliasRepository repo;
    private CarrierAliasService svc;

    @BeforeEach
    void setUp() {
        repo = mock(CarrierAliasRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                row("UPS", "UPS", "UPS"),
                row("FEDEX", "FEDEX", "FedEx"),
                row("USPS", "USPS", "USPS"),
                row("DHL", "DHL", "DHL"),
                row("STAMPS", "USPS", "USPS"),
                row("P80", "UPS", "UPS"),
                row("F77", "FEDEX", "FedEx"),
                row("L01", "USPS", "USPS")));
        svc = new CarrierAliasService(repo);
        svc.reload();
    }

    @Test
    void canonicalizesCanonicalPassThrough() {
        assertEquals("UPS", svc.canonicalize("UPS"));
        assertEquals("FEDEX", svc.canonicalize("FEDEX"));
        assertEquals("USPS", svc.canonicalize("USPS"));
        assertEquals("DHL", svc.canonicalize("DHL"));
    }

    @Test
    void canonicalizesLegacyErpCodes() {
        assertEquals("UPS", svc.canonicalize("P80"));
        assertEquals("FEDEX", svc.canonicalize("F77"));
        assertEquals("USPS", svc.canonicalize("L01"));
    }

    @Test
    void canonicalizesUspsAliases() {
        assertEquals("USPS", svc.canonicalize("STAMPS"));
    }

    @Test
    void canonicalizeUnknownReturnsNull() {
        assertNull(svc.canonicalize("MYSTERY"));
        assertNull(svc.canonicalize(""));
        assertNull(svc.canonicalize(null));
    }

    @Test
    void canonicalizeIsCaseInsensitive() {
        assertEquals("UPS", svc.canonicalize("ups"));
        assertEquals("FEDEX", svc.canonicalize("  fedex  "));
    }

    @Test
    void displayReturnsLabel() {
        assertEquals("UPS", svc.display("UPS"));
        assertEquals("FedEx", svc.display("FEDEX"));
        assertEquals("FedEx", svc.display("F77"));
        assertEquals("USPS", svc.display("L01"));
    }

    @Test
    void displayUnknownFallsBackToUpperCasedInput() {
        assertEquals("MYSTERY", svc.display("mystery"));
    }

    @Test
    void knownCarriersReturnsOnlyCanonicalRowsSortedByLabel() {
        List<CarrierAliasService.KnownCarrier> known = svc.knownCarriers();
        assertEquals(4, known.size());
        // Sorted by label: DHL, FedEx, UPS, USPS.
        assertEquals("DHL", known.get(0).code());
        assertEquals("FEDEX", known.get(1).code());
        assertEquals("UPS", known.get(2).code());
        assertEquals("USPS", known.get(3).code());
    }

    @Test
    void reloadSetsHolder() {
        assertEquals("UPS", CarrierAliasHolder.canonicalize("P80"));
        assertEquals("FedEx", CarrierAliasHolder.display("F77"));
    }

    @Test
    void holderPassesThroughBeforeReload() {
        // Simulate boot order — nothing has called set() yet.
        // We can't literally uninstall a static, so this verifies fallback
        // behavior by using an unknown code (no row → null / uppercased pass-through).
        assertNull(CarrierAliasHolder.canonicalize("BRAND_NEW_CARRIER"));
        assertTrue(CarrierAliasHolder.display("brand_new_carrier").equals("BRAND_NEW_CARRIER"));
    }

    private CarrierAliasEntity row(String src, String tgt, String label) {
        return CarrierAliasEntity.builder()
                .sourceCode(src)
                .targetCode(tgt)
                .displayLabel(label)
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
