package com.multiship.backend.service.intake;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientDestCountryMap;
import com.multiship.backend.model.ClientPackageCodeMap;
import com.multiship.backend.model.ClientServiceCodeMap;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientDestCountryMapRepository;
import com.multiship.backend.repository.ClientPackageCodeMapRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.service.resolution.ShipmentResolutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the intake code-
 * translation service. This runs FIRST on every incoming shipment,
 * translating ERP shipvia / service / country / package codes into
 * canonical platform values. A silent bug here misroutes shipments
 * to the wrong carrier or wrong destination.
 *
 * <p>Guards the design contract:
 * blank inputs → Optional.empty(), missing alias table → empty,
 * hit → canonical value, miss with populated table → strict throw
 * with UNKNOWN_* code.
 */
class ClientCodeTranslationServiceTest {

    private ClientShipviaCodeMapRepository shipviaRepo;
    private ClientServiceCodeMapRepository serviceRepo;
    private ClientDestCountryMapRepository destRepo;
    private ClientPackageCodeMapRepository packageRepo;
    private ClientCodeTranslationService service;

    @BeforeEach
    void setUp() {
        shipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        serviceRepo = mock(ClientServiceCodeMapRepository.class);
        destRepo = mock(ClientDestCountryMapRepository.class);
        packageRepo = mock(ClientPackageCodeMapRepository.class);
        service = new ClientCodeTranslationService(shipviaRepo, serviceRepo, destRepo, packageRepo);
    }

    /* -------- happy paths -------- */

    @Test
    void translateShipviaHitReturnsServiceId() {
        ClientShipviaCodeMap row = new ClientShipviaCodeMap();
        row.setClientCode("ACME");
        row.setErpCode("UPS_GRND");
        row.setServiceId(7L);
        when(shipviaRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc("ACME"))
                .thenReturn(List.of(row));
        when(shipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase("ACME", "UPS_GRND"))
                .thenReturn(Optional.of(row));

        Optional<Long> resolved = service.translateShipvia("ACME", "UPS_GRND");
        assertTrue(resolved.isPresent());
        assertEquals(7L, resolved.get());
    }

    @Test
    void translateDestCountryFastPathForCanonicalTwoLetter() {
        // Canonical ISO-2 skips the repo lookup entirely.
        Optional<String> resolved = service.translateDestCountry("ACME", "us");
        assertTrue(resolved.isPresent());
        assertEquals("US", resolved.get());
    }

    /* -------- empty inputs / empty tables -------- */

    @Test
    void blankInputsReturnEmpty() {
        assertTrue(service.translateShipvia(null, "UPS").isEmpty());
        assertTrue(service.translateShipvia("ACME", null).isEmpty());
        assertTrue(service.translateShipvia("ACME", "  ").isEmpty());
        assertTrue(service.translateServiceCode("ACME", "").isEmpty());
    }

    @Test
    void emptyAliasTableReturnsEmpty() {
        // No aliases configured → caller falls through to legacy resolution.
        when(shipviaRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(anyString()))
                .thenReturn(List.of());
        assertTrue(service.translateShipvia("ACME", "UPS_GRND").isEmpty());
    }

    /* -------- strict rejection: populated table, no hit -------- */

    @Test
    void populatedTableWithNoMatchThrowsUnknownShipvia() {
        ClientShipviaCodeMap someRow = new ClientShipviaCodeMap();
        someRow.setClientCode("ACME");
        someRow.setErpCode("UPS_OTHER");
        when(shipviaRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc("ACME"))
                .thenReturn(List.of(someRow));
        when(shipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase("ACME", "UPS_GRND"))
                .thenReturn(Optional.empty());

        ShipmentResolutionException ex = assertThrows(ShipmentResolutionException.class,
                () -> service.translateShipvia("ACME", "UPS_GRND"));
        assertEquals(ErrorCode.UNKNOWN_SHIPVIA_CODE, ex.getErrorCode());
    }

    @Test
    void populatedTableWithNoMatchThrowsUnknownPackage() {
        ClientPackageCodeMap someRow = new ClientPackageCodeMap();
        someRow.setClientCode("ACME");
        someRow.setErpCode("OTHER");
        when(packageRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc("ACME"))
                .thenReturn(List.of(someRow));
        when(packageRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase("ACME", "BOX_SM"))
                .thenReturn(Optional.empty());

        ShipmentResolutionException ex = assertThrows(ShipmentResolutionException.class,
                () -> service.translatePackage("ACME", "BOX_SM"));
        assertEquals(ErrorCode.UNKNOWN_PACKAGE_CODE, ex.getErrorCode());
    }

    @Test
    void populatedDestTableWithNoMatchThrowsUnknownDest() {
        ClientDestCountryMap row = new ClientDestCountryMap();
        row.setClientCode("ACME");
        row.setErpCode("USA");
        row.setIso2("US");
        when(destRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc("ACME"))
                .thenReturn(List.of(row));
        when(destRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase("ACME", "GBR"))
                .thenReturn(Optional.empty());

        ShipmentResolutionException ex = assertThrows(ShipmentResolutionException.class,
                () -> service.translateDestCountry("ACME", "GBR"));
        assertEquals(ErrorCode.UNKNOWN_DEST_CODE, ex.getErrorCode());
    }
}
