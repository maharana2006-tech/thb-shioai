package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.service.TenantScopeEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link NdsShipmentLookupService}. Mocks the repository
 * + shipvia repos so no Oracle / Postgres round-trip is needed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NdsShipmentLookupServiceTest {

    @Mock NdsShipmentLookupRepository repository;
    @Mock ClientShipviaCodeMapRepository clientShipviaRepo;
    @Mock ShipViaMappingRepository shipviaMappingRepo;
    @Mock TenantScopeEnforcer tenantScopeEnforcer;

    @InjectMocks NdsShipmentLookupService service;

    private static final String CLIENT = "ACME";
    private static final String ORDER_NO = "123456";
    private static final String ORDER_SUFFIX = "1";
    private static final String CONTAINER_ID = "77777";

    @BeforeEach
    void setUp() {
        when(tenantScopeEnforcer.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
        // Default: no notify emails, no international items — service still works.
        when(repository.findNotifyEmails(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(repository.findInternationalItems(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(repository.findShipMethod(anyString(), anyString()))
                .thenReturn(Optional.of(new NdsShipmentLookupRepository.ShipMethod(
                        "P80", "UPS Ground")));
        when(clientShipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(newClientMap(42L)));
    }

    @Test
    void returnsEmptyWhenContainerUnknown() {
        when(repository.findContainerOwner(CONTAINER_ID)).thenReturn(Optional.empty());
        assertTrue(service.lookup(".X" + CONTAINER_ID).isEmpty());
    }

    @Test
    void directLookupHappyPathReturnsOk() {
        stubDirectHappy("P80", "N", "N", "US");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.OK, p.status());
        assertEquals(NdsShipmentPrefill.Scope.DIRECT, p.scope());
        assertEquals(CLIENT, p.clientCode());
        assertEquals(1, p.packages().size());
        assertTrue(p.packages().get(0).isScanned(), "scanned container must be flagged");
        assertNull(p.international(), "US recipient → international block omitted");
        assertEquals(42L, p.shipMethod().mappedServiceId());
    }

    @Test
    void blocksWhenShipviaIsHold() {
        stubDirectHappy("HLD", "N", "N", "US");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.BLOCKED, p.status());
        assertTrue(p.messages().stream().anyMatch(m -> m.text().contains("hold")),
                "BLOCKED message must reference hold");
    }

    @Test
    void blocksWhenShippedFlagYes() {
        stubDirectHappy("P80", "Y", "N", "US");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.BLOCKED, p.status());
        assertTrue(p.messages().stream().anyMatch(m -> m.text().toLowerCase().contains("shipped")));
    }

    @Test
    void warningWhenShipviaUnmapped() {
        when(clientShipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(shipviaMappingRepo.findByShipviaCdIgnoreCase(anyString())).thenReturn(List.of());
        stubDirectHappy("XYZ", "N", "N", "US");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.WARNING, p.status());
        assertNull(p.shipMethod().mappedServiceId());
    }

    @Test
    void internationalDetectedForNonUsCountry() {
        stubDirectHappy("P80", "N", "N", "GB");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertNotNull(p.international());
        assertTrue(p.international().international());
    }

    @Test
    void internationalDetectedForUsTerritory() {
        stubDirectHappy("P80", "N", "N", "PR");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertNotNull(p.international(), "US territories must be treated as international");
    }

    @Test
    void phoneDefaultedWhenNdsPhoneIsGarbage() {
        stubDirectHappy("P80", "N", "N", "US");
        when(repository.findOrderHeader(CLIENT, ORDER_NO, ORDER_SUFFIX))
                .thenReturn(Optional.of(header("P80", "N", "N", "US", /*phone*/ "x")));
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertTrue(p.recipient().phoneDefaulted());
        assertEquals(NdsPhoneNormalizer.DEFAULT_PHONE, p.recipient().phone());
        assertTrue(p.defaultedFields().contains("recipient.phone"));
    }

    @Test
    void emailDefaultedWhenNotifyRowsEmpty() {
        stubDirectHappy("P80", "N", "N", "US");
        NdsShipmentPrefill p = service.lookup(".X" + CONTAINER_ID).orElseThrow();
        assertTrue(p.notifyBlock().emailDefaulted());
        assertEquals(NdsShipmentLookupService.DEFAULT_NOTIFY_EMAIL, p.notifyBlock().sendTo());
        assertTrue(p.defaultedFields().contains("notify.sendTo"));
    }

    // ───── batch (.Y) path ─────

    @Test
    void batchLookupUsesLowestContainerAsAnchor() {
        String batchId = "B42";
        when(repository.findBatchOwner(batchId))
                .thenReturn(Optional.of(new NdsShipmentLookupRepository.BatchOwner("FF", CLIENT)));
        when(repository.findBatchContents(batchId))
                .thenReturn(List.of(
                        new NdsShipmentLookupRepository.BatchContainer("100", "999999", "1"),
                        new NdsShipmentLookupRepository.BatchContainer("200", "888888", "2")));
        when(repository.findOrderHeader(CLIENT, "999999", "1"))
                .thenReturn(Optional.of(headerFor("999999", "1", "P80", "N", "N", "US", "6165551212")));
        when(repository.findBatchPackagesGrouped(CLIENT, List.of("100", "200")))
                .thenReturn(List.of(
                        pkg("100", "999999", "1"),
                        pkg("200", "888888", "2")));
        NdsShipmentPrefill p = service.lookup(".Y" + batchId).orElseThrow();
        assertEquals(NdsShipmentPrefill.Scope.BATCH, p.scope());
        assertEquals(batchId, p.batchId());
        assertEquals(2, p.packages().size());
        assertEquals(999999, p.orders().get(0).orderNo(),
                "anchor order (lowest container id) supplies the DTO");
    }

    // ───── invalid-input path ─────

    @Test
    void unparseableScanBubblesIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.lookup(".Zbad"));
    }

    // ───── helpers ─────

    private void stubDirectHappy(String shipvia, String shipped, String hold, String country) {
        when(repository.findContainerOwner(CONTAINER_ID)).thenReturn(Optional.of(
                new NdsShipmentLookupRepository.ContainerOwner(CLIENT, ORDER_NO, ORDER_SUFFIX)));
        when(repository.findOrderHeader(CLIENT, ORDER_NO, ORDER_SUFFIX))
                .thenReturn(Optional.of(header(shipvia, shipped, hold, country, "6165551212")));
        when(repository.findContainers(CLIENT, ORDER_NO, ORDER_SUFFIX))
                .thenReturn(List.of(new NdsShipmentLookupRepository.ContainerRow(
                        CONTAINER_ID, ORDER_NO, ORDER_SUFFIX,
                        new BigDecimal("2.5"), new BigDecimal("12"),
                        new BigDecimal("6"), new BigDecimal("4"), "BOX")));
    }

    private NdsShipmentLookupRepository.OrderHeader header(String shipvia, String shipped,
                                                           String hold, String country, String phone) {
        return headerFor(ORDER_NO, ORDER_SUFFIX, shipvia, shipped, hold, country, phone);
    }

    private NdsShipmentLookupRepository.OrderHeader headerFor(String orderNo, String suffix,
                                                              String shipvia, String shipped,
                                                              String hold, String country, String phone) {
        return new NdsShipmentLookupRepository.OrderHeader(
                orderNo, suffix,
                "Wile E Coyote", "ACME Corp",
                "1 Anvil Way", null, null,
                "Tucson", "AZ", "85701", country,
                phone, "wile@acme.example",
                shipvia, shipped, hold,
                "PO-100", "SHIP", "DAP", "USD");
    }

    private NdsShipmentLookupRepository.BatchPackage pkg(String containerId, String orderNo, String suffix) {
        return new NdsShipmentLookupRepository.BatchPackage(
                containerId, orderNo, suffix,
                new BigDecimal("1.0"), new BigDecimal("10"),
                new BigDecimal("5"), new BigDecimal("3"), "BOX");
    }

    private ClientShipviaCodeMap newClientMap(long serviceId) {
        ClientShipviaCodeMap m = new ClientShipviaCodeMap();
        m.setClientCode(CLIENT);
        m.setErpCode("P80");
        m.setServiceId(serviceId);
        return m;
    }
}
