package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.AddressDTO;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.ClientCascadePreviewDTO;
import com.multiship.backend.dto.ClientCascadeSnapshot;
import com.multiship.backend.dto.ClientDTO;
import com.multiship.backend.dto.ClientListFilters;
import com.multiship.backend.dto.ClientUpsertRequest;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Address;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.AuditLogRepository;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backend service-coverage backfill for {@link ClientServiceImpl}
 * (638 LoC, 11 public methods, previously untested at the unit
 * level). Controller-layer tests only exercise delegation; the
 * business logic in the service — pagination clamp, tenant clamp,
 * NOT_FOUND / CODE_TAKEN / HAS_ORDERS branches, cascade
 * disable/enable snapshotting, delete-with-orphan-cleanup — lives
 * exclusively here.
 *
 * <p>Pattern matches the other service unit tests in this package:
 * pure Mockito (no @SpringBootTest / no context load), constructor
 * injection for the repositories + services, {@code mock(Class)}
 * over annotation-driven mock injection so the collaborator list
 * is explicit at the top of the test.
 */
class ClientServiceImplTest {

    private ClientRepository clientRepo;
    private CarrierAccountRefRepository accountRepo;
    private OrderRepository orderRepo;
    private ClientCustomsProfileRepository customsProfileRepo;
    private WarehouseRepository warehouseRepo;
    private ClientWarehouseRepository clientWarehouseRepo;
    private AuditService auditService;
    private AuditLogRepository auditLogRepo;
    private TenantScopeEnforcer tenantScope;

    private ClientServiceImpl service;

    @BeforeEach
    void setUp() {
        clientRepo = mock(ClientRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        orderRepo = mock(OrderRepository.class);
        customsProfileRepo = mock(ClientCustomsProfileRepository.class);
        warehouseRepo = mock(WarehouseRepository.class);
        clientWarehouseRepo = mock(ClientWarehouseRepository.class);
        auditService = mock(AuditService.class);
        auditLogRepo = mock(AuditLogRepository.class);
        tenantScope = mock(TenantScopeEnforcer.class);

        service = new ClientServiceImpl(
                clientRepo, accountRepo, orderRepo, customsProfileRepo,
                warehouseRepo, clientWarehouseRepo, auditService,
                auditLogRepo, tenantScope);

        // Platform-mode default: tenant-scope pass-through. Individual
        // tests override when they need to exercise the clamp branch.
        when(tenantScope.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ===== helpers =====

    private Client sampleClient(String code) {
        Client c = Client.builder()
                .id(42L)
                .clientCode(code)
                .name("Acme " + code)
                .email("ops@" + code.toLowerCase() + ".io")
                .phone("+1-555-0100")
                .status(Client.STATUS_ACTIVE)
                .shipFrom(Address.builder()
                        .line1("100 Main St")
                        .city("Denver")
                        .state("CO")
                        .zip("80202")
                        .country("US")
                        .build())
                .returnSameAsShipFrom(true)
                .build();
        return c;
    }

    private ClientUpsertRequest sampleUpsert(String code) {
        AddressDTO ship = AddressDTO.builder()
                .line1("100 Main St").city("Denver").state("CO").zip("80202").country("US")
                .build();
        return ClientUpsertRequest.builder()
                .clientCode(code)
                .name("Acme " + code)
                .email(" ops@acme.io ")   // ships with whitespace to prove trim runs
                .phone(" +1-555-0100 ")
                .defaultCurrency("usd")   // ships lower-case to prove upper() runs
                .defaultOriginCountry("us")
                .shipFrom(ship)
                .returnSameAsShipFrom(true)
                .build();
    }

    private CarrierAccountRef sampleAccount(long id, String customerNo, boolean active) {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setId(id);
        a.setCarrierCode("UPS");
        a.setAccountNumber("A" + id);
        a.setCustomerNo(customerNo);
        a.setActive(active);
        return a;
    }

    private Warehouse sampleWarehouse(long id, String code, boolean active) {
        Warehouse w = new Warehouse();
        w.setId(id);
        w.setCode(code);
        w.setActive(active);
        return w;
    }

    // ===== listClients =====

    @Test
    void listClients_clampsPageSizeTo100_evenWhenCallerAsksFor10000() {
        ClientListFilters filters = ClientListFilters.builder()
                .page(0).size(10_000).build();
        when(clientRepo.filterCodes(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(0), eq(100))).thenReturn(List.of());
        when(clientRepo.countFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        ApiResponse<PageResponseDTO<ClientDTO>> res = service.listClients(filters);

        // The clamp is the only reason this test injected size=10000 — that
        // the service passes limit=100 (not 10000) to filterCodes proves the
        // Math.min(size, 100) guard runs and the caller can't blow past the
        // DB-side pagination guarantees.
        assertEquals(200, res.getCode());
        verify(clientRepo).filterCodes(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(0), eq(100));
    }

    @Test
    void listClients_appliesTenantClamp_soScopedUserSeesOnlyOwnRow() {
        // A scoped USER hits /clients with no code param; TenantScopeEnforcer
        // clamps to their own client_code. The service must forward that
        // clamped value to the repository as the code-filter.
        when(tenantScope.clampClientCode(any())).thenReturn("TENANTA");
        ClientListFilters filters = ClientListFilters.builder()
                .page(0).size(20).build();
        when(clientRepo.filterCodes(any(), any(), any(), any(), eq("TENANTA"), any(), any(),
                any(), any(), eq(0), eq(20))).thenReturn(List.of());
        when(clientRepo.countFiltered(any(), any(), any(), any(), eq("TENANTA"), any(), any()))
                .thenReturn(0L);

        service.listClients(filters);

        verify(clientRepo).filterCodes(any(), any(), any(), any(), eq("TENANTA"), any(), any(),
                any(), any(), eq(0), eq(20));
    }

    @Test
    void listClients_mapsResultCodesToDTOs_viaPerCodeLookup() {
        when(clientRepo.filterCodes(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(0), eq(20))).thenReturn(List.of("ACME"));
        when(clientRepo.countFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());

        ApiResponse<PageResponseDTO<ClientDTO>> res = service.listClients(
                ClientListFilters.builder().page(0).size(20).build());

        assertEquals(1, res.getData().getContent().size());
        assertEquals("ACME", res.getData().getContent().get(0).getClientCode());
    }

    // ===== getClient =====

    @Test
    void getClient_returns200_forExistingCode() {
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());

        ApiResponse<ClientDTO> res = service.getClient("acme");

        assertEquals(200, res.getCode());
        assertEquals("ACME", res.getData().getClientCode());
        // requireTenantMatch is called defensively even after the controller
        // @PreAuthorize check — a service caller (batch job) could bypass
        // method security otherwise. Verify the guard actually fires.
        verify(tenantScope).requireTenantMatch("ACME");
    }

    @Test
    void getClient_returns404WithErrorCode_whenCodeMissing() {
        when(clientRepo.findByClientCodeIgnoreCase("MISSING"))
                .thenReturn(Optional.empty());

        ApiResponse<ClientDTO> res = service.getClient("missing");

        assertEquals(404, res.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), res.getErrorCode());
        assertNull(res.getData());
    }

    // ===== createClient =====

    @Test
    void createClient_persistsClient_withCodeUppercased_andRecordsAudit() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(false);
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());

        ApiResponse<ClientDTO> res = service.createClient(sampleUpsert("acme"));

        assertEquals(200, res.getCode());
        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clientRepo).save(saved.capture());
        assertEquals("ACME", saved.getValue().getClientCode(), "code must be uppercased");
        // per-tenant default currency was 'usd' in the request; assert the upper() ran
        assertEquals("USD", saved.getValue().getDefaultCurrency());
        // email came in with wrapping whitespace; assert trim ran
        assertEquals("ops@acme.io", saved.getValue().getEmail());
        // Audit trail — CREATE action on the CLIENT entity with the new code
        verify(auditService).record(eq(AuditService.CREATE), eq(AuditService.CLIENT),
                any(), eq("ACME"), any(), anyString());
    }

    @Test
    void createClient_returns409_whenCodeAlreadyTaken() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        ApiResponse<ClientDTO> res = service.createClient(sampleUpsert("ACME"));

        assertEquals(409, res.getCode());
        assertEquals(ErrorCode.CLIENT_CODE_TAKEN.name(), res.getErrorCode());
        verify(clientRepo, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    // ===== updateClient =====

    @Test
    void updateClient_appliesFields_andRecordsUpdateAudit() {
        Client existing = sampleClient("ACME");
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());
        ClientUpsertRequest req = sampleUpsert("ACME");
        req.setName("Acme (Renamed)");

        ApiResponse<ClientDTO> res = service.updateClient("acme", req);

        assertEquals(200, res.getCode());
        assertEquals("Acme (Renamed)", existing.getName());
        verify(clientRepo).save(existing);
        verify(auditService).record(eq(AuditService.UPDATE), eq(AuditService.CLIENT),
                any(), eq("ACME"), any(), anyString());
    }

    @Test
    void updateClient_returns404_whenClientMissing() {
        when(clientRepo.findByClientCodeIgnoreCase("GHOST"))
                .thenReturn(Optional.empty());

        ApiResponse<ClientDTO> res = service.updateClient("ghost", sampleUpsert("GHOST"));

        assertEquals(404, res.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), res.getErrorCode());
        verify(clientRepo, never()).save(any());
    }

    // ===== toggleActive → disable cascade =====

    @Test
    void toggleActive_disableBlocked_whenPendingOrdersExist() {
        Client active = sampleClient("ACME");
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(active));
        when(orderRepo.countPendingByClient("ACME")).thenReturn(3L);

        ApiResponse<ClientDTO> res = service.toggleActive("acme");

        assertEquals(409, res.getCode());
        assertEquals(ErrorCode.CLIENT_HAS_ORDERS.name(), res.getErrorCode());
        assertTrue(res.getMessage().contains("3 pending order"));
        // Cascade side-effects must NOT run when the guard trips.
        verify(accountRepo, never()).saveAll(any());
        verify(warehouseRepo, never()).saveAll(any());
        verify(clientWarehouseRepo, never()).deleteAll(any());
    }

    @Test
    void toggleActive_disableSucceeds_cascadesToAccounts_warehouses_links_andSnapshotsIds() {
        Client active = sampleClient("ACME");
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(active));
        when(orderRepo.countPendingByClient("ACME")).thenReturn(0L);

        CarrierAccountRef account = sampleAccount(11L, "ACME", true);
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of(account));

        Warehouse ownedWh = sampleWarehouse(21L, "WH-ACME", true);
        when(warehouseRepo.findByOwnerClientCodeIgnoreCaseOrderByCodeAsc("ACME"))
                .thenReturn(List.of(ownedWh));

        ClientWarehouse link = new ClientWarehouse();
        link.setId(31L);
        link.setClientCode("ACME");
        link.setWarehouseId(21L);
        when(clientWarehouseRepo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(link));
        when(warehouseRepo.findAllById(List.of(21L))).thenReturn(List.of(ownedWh));

        ApiResponse<ClientDTO> res = service.toggleActive("acme");

        assertEquals(200, res.getCode());
        assertFalse(account.getActive(), "carrier account must be deactivated");
        assertFalse(ownedWh.getActive(), "client-owned warehouse must be deactivated");
        assertEquals(Client.STATUS_INACTIVE, active.getStatus());
        verify(clientWarehouseRepo).deleteAll(List.of(link));

        // Snapshot is written to the audit log so re-enable can restore
        // exactly these rows (not touching rows that were inactive BEFORE).
        ArgumentCaptor<ClientCascadeSnapshot> snap = ArgumentCaptor.forClass(ClientCascadeSnapshot.class);
        verify(auditService).record(eq(AuditService.CASCADE_DISABLE), eq(AuditService.CLIENT),
                any(), eq("ACME"), snap.capture(), anyString());
        assertEquals(List.of(11L), snap.getValue().getCarrierAccountIds());
        assertEquals(List.of("WH-ACME"), snap.getValue().getClientOwnedWarehouseCodes());
        assertEquals(List.of(31L), snap.getValue().getClientWarehouseLinkIds());
        // Sprint 47 data-loss fix — detachedWarehouseCodes populated so the
        // re-enable path can re-attach PLATFORM warehouses too, not just
        // CLIENT-owned ones (the earlier version silently lost these).
        assertEquals(List.of("WH-ACME"), snap.getValue().getDetachedWarehouseCodes());
    }

    // ===== toggleActive → enable restore =====

    @Test
    void toggleActive_enableRestoresRows_fromMostRecentSnapshot() {
        Client inactive = sampleClient("ACME");
        inactive.setStatus(Client.STATUS_INACTIVE);
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(inactive));

        // Snapshot from the earlier disable cascade — one account, one
        // client-owned warehouse, one previously-attached warehouse code.
        ClientCascadeSnapshot snap = ClientCascadeSnapshot.builder()
                .carrierAccountIds(List.of(11L))
                .clientOwnedWarehouseCodes(List.of("WH-ACME"))
                .clientWarehouseLinkIds(List.of(31L))
                .detachedWarehouseCodes(List.of("WH-ACME"))
                .build();
        AuditLog snapshotLog = new AuditLog();
        // The service uses ObjectMapper to parse; provide the same JSON shape.
        snapshotLog.setChanges("{\"carrierAccountIds\":[11],\"clientOwnedWarehouseCodes\":[\"WH-ACME\"],"
                + "\"clientWarehouseLinkIds\":[31],\"detachedWarehouseCodes\":[\"WH-ACME\"]}");
        when(auditLogRepo.findFirstByEntityTypeAndEntityKeyAndActionOrderByCreatedAtDesc(
                AuditService.CLIENT, "ACME", AuditService.CASCADE_DISABLE))
                .thenReturn(Optional.of(snapshotLog));

        CarrierAccountRef account = sampleAccount(11L, "ACME", false);
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account));

        Warehouse wh = sampleWarehouse(21L, "WH-ACME", false);
        when(warehouseRepo.findByCodeIgnoreCase("WH-ACME")).thenReturn(Optional.of(wh));

        // Re-attach path — no existing link, so a new one is saved.
        when(clientWarehouseRepo.findByClientCodeIgnoreCaseAndWarehouseId("ACME", 21L))
                .thenReturn(Optional.empty());

        ApiResponse<ClientDTO> res = service.toggleActive("acme");

        assertEquals(200, res.getCode());
        assertTrue(account.getActive(), "carrier account restored");
        assertTrue(wh.getActive(), "warehouse restored");
        assertEquals(Client.STATUS_ACTIVE, inactive.getStatus());
        // Re-attach path saves the recreated client_warehouse link.
        verify(clientWarehouseRepo).save(any(ClientWarehouse.class));
        // CASCADE_ENABLE audit row emitted so the trail is symmetric.
        verify(auditService).record(eq(AuditService.CASCADE_ENABLE), eq(AuditService.CLIENT),
                any(), eq("ACME"), any(), anyString());
        assertNotNull(snap);
    }

    @Test
    void toggleActive_enableWithoutSnapshot_stillActivatesClient_asSafeDefault() {
        // A client disabled before the snapshot-writing code shipped has no
        // CASCADE_DISABLE row in audit_log. The service must still flip the
        // status to ACTIVE without cascading.
        Client inactive = sampleClient("LEGACY");
        inactive.setStatus(Client.STATUS_INACTIVE);
        when(clientRepo.findByClientCodeIgnoreCase("LEGACY"))
                .thenReturn(Optional.of(inactive));
        when(auditLogRepo.findFirstByEntityTypeAndEntityKeyAndActionOrderByCreatedAtDesc(
                AuditService.CLIENT, "LEGACY", AuditService.CASCADE_DISABLE))
                .thenReturn(Optional.empty());

        ApiResponse<ClientDTO> res = service.toggleActive("legacy");

        assertEquals(200, res.getCode());
        assertEquals(Client.STATUS_ACTIVE, inactive.getStatus());
        // No accounts / warehouses touched — snapshot absent.
        verify(accountRepo, never()).save(any());
        verify(warehouseRepo, never()).save(any());
        verify(clientWarehouseRepo, never()).save(any());
    }

    // ===== previewCascade =====

    @Test
    void previewCascade_reportsAggregateCounts_fromLiveRepositoryState() {
        Client c = sampleClient("ACME");
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(c));
        when(orderRepo.countPendingByClient("ACME")).thenReturn(2L);
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of(sampleAccount(1L, "ACME", true),
                        sampleAccount(2L, "ACME", false)));
        when(warehouseRepo.findByOwnerClientCodeIgnoreCaseOrderByCodeAsc("ACME"))
                .thenReturn(List.of(sampleWarehouse(10L, "WH-A", true),
                        sampleWarehouse(11L, "WH-B", true),
                        sampleWarehouse(12L, "WH-C", false)));
        when(clientWarehouseRepo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(new ClientWarehouse(), new ClientWarehouse()));

        ApiResponse<ClientCascadePreviewDTO> res = service.previewCascade("acme");

        assertEquals(200, res.getCode());
        ClientCascadePreviewDTO body = res.getData();
        assertEquals("ACME", body.getClientCode());
        assertEquals(2L, body.getPendingOrderCount());
        assertEquals(1L, body.getActiveCarrierAccountCount(), "only ACTIVE rows counted");
        assertEquals(2L, body.getClientOwnedWarehouseCount(), "only ACTIVE warehouses counted");
        assertEquals(2L, body.getClientWarehouseLinkCount());
        assertTrue(body.isClientCurrentlyActive());
    }

    // ===== deleteClient =====

    @Test
    void deleteClient_hardDeletes_cascadesToDependents_andRecordsDeleteAudit() {
        Client c = sampleClient("ACME");
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(c));
        // No orders — delete proceeds.
        when(orderRepo.countOrdersUnified(anyString(), eq("ACME"), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        // Owned warehouse must be deleted alongside the client (Sprint 55
        // #288 — previously orphaned). Link rows detached first because
        // client_warehouse is string-linked without FK cascade.
        Warehouse owned = sampleWarehouse(21L, "WH-ACME", true);
        when(warehouseRepo.findByOwnerClientCodeIgnoreCaseOrderByCodeAsc("ACME"))
                .thenReturn(List.of(owned));
        ClientWarehouse link = new ClientWarehouse();
        link.setId(31L);
        when(clientWarehouseRepo.findByWarehouseId(21L)).thenReturn(List.of(link));

        // Customs profiles + carrier accounts deleted first — no FK cascade.
        when(customsProfileRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(List.of(new ClientCustomsProfile()));
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of(sampleAccount(11L, "ACME", true)));

        ApiResponse<Void> res = service.deleteClient("acme");

        assertEquals(200, res.getCode());
        verify(customsProfileRepo).deleteAll(any());
        verify(accountRepo).deleteAll(any());
        verify(clientWarehouseRepo).deleteAll(List.of(link));
        verify(warehouseRepo).deleteAll(List.of(owned));
        verify(clientRepo).delete(c);
        verify(auditService).record(eq(AuditService.DELETE), eq(AuditService.CLIENT),
                any(), eq("ACME"), any(), anyString());
        assertTrue(res.getMessage().contains("client-owned warehouse"),
                "message should mention the warehouse cascade when >0");
    }

    @Test
    void deleteClient_returns409_whenClientHasOrders_evenNonPending() {
        Client c = sampleClient("ACME");
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(c));
        when(orderRepo.countOrdersUnified(anyString(), eq("ACME"), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(5L);

        ApiResponse<Void> res = service.deleteClient("acme");

        assertEquals(409, res.getCode());
        assertEquals(ErrorCode.CLIENT_HAS_ORDERS.name(), res.getErrorCode());
        assertTrue(res.getMessage().contains("5 orders"));
        // No cascades — the guard trips before any deletion runs.
        verify(clientRepo, never()).delete(any());
        verify(accountRepo, never()).deleteAll(any());
        verify(customsProfileRepo, never()).deleteAll(any());
    }

    @Test
    void deleteClient_returns404_whenClientMissing() {
        when(clientRepo.findByClientCodeIgnoreCase("GHOST"))
                .thenReturn(Optional.empty());

        ApiResponse<Void> res = service.deleteClient("ghost");

        assertEquals(404, res.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), res.getErrorCode());
        verify(clientRepo, never()).delete(any());
    }

    // ===== listClientAccounts =====

    @Test
    void listClientAccounts_returnsAccountsForKnownClient() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of(sampleAccount(1L, "ACME", true),
                        sampleAccount(2L, "ACME", true)));

        ApiResponse<List<CarrierAccountRefDTO>> res = service.listClientAccounts("acme");

        assertEquals(200, res.getCode());
        assertEquals(2, res.getData().size());
    }

    @Test
    void listClientAccounts_returns404_forUnknownClient() {
        when(clientRepo.existsByClientCodeIgnoreCase("GHOST")).thenReturn(false);

        ApiResponse<List<CarrierAccountRefDTO>> res = service.listClientAccounts("ghost");

        assertEquals(404, res.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), res.getErrorCode());
        assertNull(res.getData());
    }

    // ===== exportClientsCsv =====

    @Test
    void exportClientsCsv_writesHeaderRow_andOneRowPerClient() {
        when(clientRepo.countFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(clientRepo.filterCodes(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(0), eq(1))).thenReturn(List.of("ACME"));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());
        when(orderRepo.countOrdersUnified(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(0L);

        String csv = service.exportClientsCsv(ClientListFilters.builder().build());

        String[] lines = csv.split("\r\n");
        assertEquals("Code,Name,Email,Phone,Country,Ship-from city,Carrier accounts,Orders,Status,Created,Updated",
                lines[0]);
        assertEquals(2, lines.length, "header + one data row");
        assertTrue(lines[1].startsWith("ACME,Acme ACME,"));
    }

    @Test
    void exportClientsCsv_quotesFieldsContainingCommas_perRfc4180() {
        Client c = sampleClient("ACME");
        c.setName("Acme, Corp");   // comma triggers quoting
        when(clientRepo.countFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(clientRepo.filterCodes(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(0), eq(1))).thenReturn(List.of("ACME"));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(c));
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());
        when(orderRepo.countOrdersUnified(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(0L);

        String csv = service.exportClientsCsv(ClientListFilters.builder().build());

        assertTrue(csv.contains("\"Acme, Corp\""), "comma-bearing name must be quoted per RFC 4180");
    }

    // ===== call-count regression guard =====

    @Test
    void createClient_countsAuditRecord_exactlyOnce() {
        // Guard against a future refactor that accidentally records the
        // audit event twice (double-audit was a real class of bug in
        // Sprint 49 — every audit-observer registration was reviewed).
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(false);
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of());

        service.createClient(sampleUpsert("ACME"));

        verify(auditService, times(1)).record(any(), any(), any(), any(), any(), any());
    }
}
