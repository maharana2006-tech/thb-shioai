package com.multiship.backend.service.carriers.platform;

import com.multiship.backend.model.CarrierPlatformEntity;
import com.multiship.backend.repository.CarrierPlatformRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V112 — cache + helper lookups + enabled gate behaviour. */
class CarrierPlatformServiceTest {

    private CarrierPlatformRepository repo;
    private CarrierPlatformService service;

    @BeforeEach
    void setUp() {
        repo = mock(CarrierPlatformRepository.class);
        service = new CarrierPlatformService(repo);
    }

    @Test
    void isEnabledReturnsTrueForUnknownCarrier() {
        when(repo.findAll()).thenReturn(List.of());
        service.reload();
        // Unknown codes default to true — zero behaviour change before seed.
        assertTrue(service.isEnabled("FEDEX"));
        assertTrue(service.isEnabled("MADE_UP"));
    }

    @Test
    void isEnabledReturnsFalseWhenRowFlagged() {
        when(repo.findAll()).thenReturn(List.of(
                CarrierPlatformEntity.builder().carrierCode("FEDEX").enabled(false).mode("LIVE").build()));
        service.reload();
        assertFalse(service.isEnabled("FEDEX"));
        assertFalse(service.isEnabled("fedex"));  // case-insensitive
        assertTrue(service.isEnabled("UPS"));     // other carriers unaffected
    }

    @Test
    void getFamilyReadsThroughCache() {
        when(repo.findAll()).thenReturn(List.of(
                CarrierPlatformEntity.builder().carrierCode("STAMPS_COM").family("USPS").build(),
                CarrierPlatformEntity.builder().carrierCode("USPS_DIRECT").family("USPS").build(),
                CarrierPlatformEntity.builder().carrierCode("FEDEX").family("FEDEX").build()));
        service.reload();
        assertEquals(Optional.of("USPS"), service.getFamily("stamps_com"));
        assertTrue(service.isUspsFamily("USPS_DIRECT"));
        assertFalse(service.isUspsFamily("FEDEX"));
    }

    @Test
    void updateEnabledAndModeWritesThroughAndReloadsCache() {
        CarrierPlatformEntity existing = CarrierPlatformEntity.builder()
                .carrierCode("FEDEX").enabled(true).mode("LIVE").family("FEDEX").build();
        when(repo.findById("FEDEX")).thenReturn(Optional.of(existing));
        when(repo.save(any(CarrierPlatformEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findAll()).thenReturn(List.of(existing));

        CarrierPlatformEntity updated = service.updateEnabledAndMode("fedex", false, "TEST");

        assertFalse(updated.getEnabled());
        assertEquals("TEST", updated.getMode());
        verify(repo).save(any(CarrierPlatformEntity.class));
        verify(repo, times(1)).findAll();  // reload fired
    }

    @Test
    void updateRejectsInvalidMode() {
        when(repo.findById("FEDEX")).thenReturn(Optional.of(
                CarrierPlatformEntity.builder().carrierCode("FEDEX").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateEnabledAndMode("FEDEX", null, "YOLO"));
    }

    @Test
    void updateRejectsUnknownCarrier() {
        when(repo.findById("UNKNOWN")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateEnabledAndMode("UNKNOWN", true, null));
    }

    @Test
    void reloadSwallowsRepoFailureAndKeepsPriorCache() {
        when(repo.findAll()).thenReturn(List.of(
                CarrierPlatformEntity.builder().carrierCode("FEDEX").enabled(true).build()));
        service.reload();
        assertTrue(service.isEnabled("FEDEX"));

        when(repo.findAll()).thenThrow(new RuntimeException("DB down"));
        service.reload();  // must not throw
        assertTrue(service.isEnabled("FEDEX"), "cache keeps prior state on DB failure");
    }
}
