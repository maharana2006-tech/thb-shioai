package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.AddressDTO;
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
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.AuditLogRepository;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientServiceImpl implements ClientService {

    private final ClientRepository clientRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final OrderRepository orderRepository;
    private final com.multiship.backend.repository.ClientCustomsProfileRepository customsProfileRepository;
    private final WarehouseRepository warehouseRepository;
    private final ClientWarehouseRepository clientWarehouseRepository;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    /**
     * Sprint 50 Tier 0.5 PR E — tenant clamp so a scoped USER hitting
     * /clients sees only their own client row (rather than every client).
     */
    private final TenantScopeEnforcer tenantScope;
    private final ObjectMapper cascadeJson = new ObjectMapper();

    @Override
    @Transactional(readOnly = true)
    public String exportClientsCsv(ClientListFilters filters) {
        String keyword = norm(filters.getSearch());
        String status = norm(filters.getStatus());
        String carrier = norm(filters.getCarrier());
        String hasOrders = norm(filters.getHasOrders());
        // Sprint 50 Tier 0.5 PR E - clamp so a scoped USER exports only
        // their own client row even when they omit / spoof the code param.
        String code = norm(tenantScope.clampClientCode(filters.getCode()));
        String name = norm(filters.getName());
        String city = norm(filters.getCity());
        String sortBy = filters.getSortBy() != null ? filters.getSortBy() : "code";
        String sortDirection = filters.getSortDirection() != null ? filters.getSortDirection() : "ASC";

        // Two-query approach: count first so we bound the fetch, then pull
        // every matching row. Avoids passing Integer.MAX_VALUE as LIMIT.
        long total = clientRepository.countFiltered(keyword, status, carrier, hasOrders, code, name, city);
        int limit = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        List<String> codes = clientRepository.filterCodes(
                keyword, status, carrier, hasOrders, code, name, city,
                sortBy, sortDirection, 0, Math.max(limit, 1));

        List<ClientDTO> clients = codes.stream()
                .map(c -> clientRepository.findByClientCodeIgnoreCase(c).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(this::toDTO)
                .toList();

        StringBuilder csv = new StringBuilder(64 + clients.size() * 96);
        csv.append("Code,Name,Email,Phone,Country,Ship-from city,Carrier accounts,Orders,Status,Created,Updated\r\n");
        for (ClientDTO c : clients) {
            AddressDTO ship = c.getShipFrom();
            String country = ship != null && ship.getCountry() != null ? ship.getCountry().toUpperCase(Locale.ROOT) : "";
            String cityValue = ship != null && ship.getCity() != null ? ship.getCity() : "";
            String accounts = c.getCarrierAccounts() == null ? "" :
                    c.getCarrierAccounts().stream()
                            .map(a -> a.getCarrierCode() + ":" + a.getAccountNumber() + (Boolean.TRUE.equals(a.getClientDefault()) ? "*" : ""))
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("");
            csv.append(csvCell(c.getClientCode())).append(',')
               .append(csvCell(c.getName())).append(',')
               .append(csvCell(c.getEmail())).append(',')
               .append(csvCell(c.getPhone())).append(',')
               .append(csvCell(country)).append(',')
               .append(csvCell(cityValue)).append(',')
               .append(csvCell(accounts)).append(',')
               .append(c.getOrderCount() == null ? 0L : c.getOrderCount()).append(',')
               .append(csvCell(c.getStatus())).append(',')
               .append(csvCell(c.getCreatedAt() == null ? "" : c.getCreatedAt().toString())).append(',')
               .append(csvCell(c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString())).append("\r\n");
        }
        return csv.toString();
    }

    /** RFC-4180 CSV field: quote when the value contains ", newline, or a comma. */
    private static String csvCell(String value) {
        if (value == null) return "";
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<PageResponseDTO<ClientDTO>> listClients(ClientListFilters filters) {
        int page = Math.max(filters.getPage(), 0);
        int size = Math.min(Math.max(filters.getSize(), 1), 100);
        String keyword = norm(filters.getSearch());
        String status = norm(filters.getStatus());
        String carrier = norm(filters.getCarrier());
        String hasOrders = norm(filters.getHasOrders());
        // Sprint 50 Tier 0.5 PR E — clamp the caller-supplied code filter
        // to the caller's own tenant when scoped. Platform operators pass
        // through unchanged; tenant-scoped callers see only their own row
        // even if they omitted the code param.
        String code = norm(tenantScope.clampClientCode(filters.getCode()));
        String name = norm(filters.getName());
        String city = norm(filters.getCity());
        String sortBy = filters.getSortBy() != null ? filters.getSortBy() : "code";
        String sortDirection = filters.getSortDirection() != null ? filters.getSortDirection() : "ASC";

        List<String> codes = clientRepository.filterCodes(
                keyword, status, carrier, hasOrders, code, name, city,
                sortBy, sortDirection, page * size, size);
        long total = clientRepository.countFiltered(keyword, status, carrier, hasOrders, code, name, city);

        List<ClientDTO> clients = codes.stream()
                .map(c -> clientRepository.findByClientCodeIgnoreCase(c).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(this::toDTO)
                .toList();

        return success("Clients retrieved successfully.", PageResponseDTO.of(clients, page, size, total));
    }

    private String norm(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientDTO> getClient(String clientCode) {
        Client client = clientRepository.findByClientCodeIgnoreCase(normalize(clientCode)).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + normalize(clientCode) + " was not found.");
        }
        // Sprint 50 Tier 0.5 PR E — defense in depth: the controller-level
        // @PreAuthorize on this endpoint already blocks cross-tenant reads,
        // but service callers (batch jobs, other services) can bypass method
        // security. Guard here too so no path leaks a row for a foreign tenant.
        tenantScope.requireTenantMatch(client.getClientCode());

        return success("Client retrieved successfully.", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<ClientDTO> createClient(ClientUpsertRequest request) {
        // Sprint 50 Tier 0.5 PR G — clamp so a scoped USER can't POST a
        // client for a foreign clientCode. Operators pass through unchanged.
        String code = normalize(tenantScope.clampClientCode(request.getClientCode()));

        if (clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.CONFLICT, ErrorCode.CLIENT_CODE_TAKEN,
                    "Client code " + code + " is already registered.");
        }

        Client client = Client.builder()
                .clientCode(code)
                .status(Client.STATUS_ACTIVE)
                .build();
        applyFields(client, request);
        clientRepository.save(client);

        auditService.record(AuditService.CREATE, AuditService.CLIENT,
                client.getId(), code, toDTO(client), "Client " + code + " created.");
        return success("Client " + code + " created successfully.", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<ClientDTO> updateClient(String clientCode, ClientUpsertRequest request) {
        Client client = clientRepository.findByClientCodeIgnoreCase(normalize(clientCode)).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + normalize(clientCode) + " was not found.");
        }

        // The code is the linkage key to orders and accounts — immutable.
        applyFields(client, request);
        clientRepository.save(client);

        auditService.record(AuditService.UPDATE, AuditService.CLIENT,
                client.getId(), client.getClientCode(), toDTO(client),
                "Client " + client.getClientCode() + " updated.");
        return success("Client " + client.getClientCode() + " updated successfully.", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<ClientDTO> toggleActive(String clientCode) {
        String code = normalize(clientCode);
        Client client = clientRepository.findByClientCodeIgnoreCase(code).orElse(null);
        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        return client.isActive() ? disableWithCascade(client) : enableWithRestore(client);
    }

    /**
     * Disable path — guards on pending orders, cascades to carrier
     * accounts + client-owned warehouses + attachment links, records
     * a snapshot in the audit log so re-enable can restore exactly
     * these rows.
     */
    private ApiResponse<ClientDTO> disableWithCascade(Client client) {
        String code = client.getClientCode();

        long pending = orderRepository.countPendingByClient(code);
        if (pending > 0) {
            return failure(HttpStatus.CONFLICT, ErrorCode.CLIENT_HAS_ORDERS,
                    "Client " + code + " has " + pending
                            + " pending order(s). Complete or void them before deactivating.");
        }

        // Collect active rows scoped to this client, deactivate them,
        // remember the ids for the audit log.
        List<CarrierAccountRef> accounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(code);
        List<Long> deactivatedAccountIds = new java.util.ArrayList<>();
        for (CarrierAccountRef a : accounts) {
            if (Boolean.TRUE.equals(a.getActive())) {
                a.setActive(false);
                deactivatedAccountIds.add(a.getId());
            }
        }
        if (!deactivatedAccountIds.isEmpty()) {
            carrierAccountRefRepository.saveAll(accounts);
        }

        List<Warehouse> ownedWarehouses = warehouseRepository
                .findByOwnerClientCodeIgnoreCaseOrderByCodeAsc(code);
        List<String> deactivatedWarehouseCodes = new java.util.ArrayList<>();
        for (Warehouse w : ownedWarehouses) {
            if (Boolean.TRUE.equals(w.getActive())) {
                w.setActive(false);
                deactivatedWarehouseCodes.add(w.getCode());
            }
        }
        if (!deactivatedWarehouseCodes.isEmpty()) {
            warehouseRepository.saveAll(ownedWarehouses);
        }

        List<ClientWarehouse> links = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code);
        List<Long> detachedLinkIds = links.stream().map(ClientWarehouse::getId).toList();
        // Client-warehouse link rows have no active flag; drop them and
        // remember the target warehouse codes on the snapshot so re-enable
        // can re-attach. The re-attach uses attach() which is idempotent.
        // N+1 fix (perf audit): one batch fetch, not one SELECT per link.
        List<Long> warehouseIds = links.stream().map(ClientWarehouse::getWarehouseId).toList();
        java.util.List<String> detachedWarehouseCodes = warehouseRepository.findAllById(warehouseIds).stream()
                .map(Warehouse::getCode)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (!links.isEmpty()) {
            clientWarehouseRepository.deleteAll(links);
        }

        client.setStatus(Client.STATUS_INACTIVE);
        clientRepository.save(client);

        ClientCascadeSnapshot snap = ClientCascadeSnapshot.builder()
                .carrierAccountIds(deactivatedAccountIds)
                .clientOwnedWarehouseCodes(deactivatedWarehouseCodes)
                .clientWarehouseLinkIds(detachedLinkIds)
                .detachedWarehouseCodes(detachedWarehouseCodes)
                .build();
        String notes = "Deactivated: " + deactivatedAccountIds.size() + " carrier account(s), "
                + deactivatedWarehouseCodes.size() + " client-owned warehouse(s), "
                + detachedLinkIds.size() + " attachment(s).";
        auditService.record(AuditService.CASCADE_DISABLE, AuditService.CLIENT,
                client.getId(), code, snap, notes);

        return success("Client " + code + " deactivated. " + notes, toDTO(client));
    }

    /**
     * Enable path — look up the most-recent CASCADE_DISABLE snapshot
     * for this client and restore only those rows. Rows that were
     * inactive BEFORE the cascade stay inactive. If no snapshot exists
     * (client was disabled before this feature landed) we just re-
     * activate the client without cascading — safe default.
     */
    private ApiResponse<ClientDTO> enableWithRestore(Client client) {
        String code = client.getClientCode();
        client.setStatus(Client.STATUS_ACTIVE);
        clientRepository.save(client);

        ClientCascadeSnapshot snap = auditLogRepository
                .findFirstByEntityTypeAndEntityKeyAndActionOrderByCreatedAtDesc(
                        AuditService.CLIENT, code, AuditService.CASCADE_DISABLE)
                .map(AuditLog::getChanges)
                .flatMap(this::parseSnapshot)
                .orElse(null);

        int restoredAccounts = 0;
        int restoredWarehouses = 0;
        int reattachedLinks = 0;
        if (snap != null) {
            if (snap.getCarrierAccountIds() != null) {
                for (Long id : snap.getCarrierAccountIds()) {
                    carrierAccountRefRepository.findById(id).ifPresent(a -> {
                        a.setActive(true);
                        carrierAccountRefRepository.save(a);
                    });
                    restoredAccounts++;
                }
            }
            if (snap.getClientOwnedWarehouseCodes() != null) {
                for (String whCode : snap.getClientOwnedWarehouseCodes()) {
                    warehouseRepository.findByCodeIgnoreCase(whCode).ifPresent(w -> {
                        w.setActive(true);
                        warehouseRepository.save(w);
                    });
                    restoredWarehouses++;
                }
            }
            // Re-attach: iterate EVERY warehouse the client had attached
            // (both CLIENT-owned and PLATFORM-owned). The earlier version
            // iterated clientOwnedWarehouseCodes which silently lost any
            // platform-warehouse attachment across a disable/enable cycle.
            List<String> reattachCodes = snap.getDetachedWarehouseCodes() != null
                    ? snap.getDetachedWarehouseCodes()
                    // Fallback for audit rows written before the fix — the
                    // best we can do is re-activate any client-owned rows.
                    : snap.getClientOwnedWarehouseCodes();
            if (reattachCodes != null) {
                boolean first = true;
                for (String whCode : reattachCodes) {
                    Warehouse w = warehouseRepository.findByCodeIgnoreCase(whCode).orElse(null);
                    if (w == null) continue;
                    if (clientWarehouseRepository
                            .findByClientCodeIgnoreCaseAndWarehouseId(code, w.getId()).isPresent()) {
                        continue;
                    }
                    ClientWarehouse cw = new ClientWarehouse();
                    cw.setClientCode(code);
                    cw.setWarehouseId(w.getId());
                    cw.setIsDefault(first);
                    clientWarehouseRepository.save(cw);
                    reattachedLinks++;
                    first = false;
                }
            }
        }

        String notes = "Reactivated: " + restoredAccounts + " carrier account(s), "
                + restoredWarehouses + " client-owned warehouse(s), "
                + reattachedLinks + " attachment(s).";
        auditService.record(AuditService.CASCADE_ENABLE, AuditService.CLIENT,
                client.getId(), code, snap, notes);
        return success("Client " + code + " reactivated. " + notes, toDTO(client));
    }

    private java.util.Optional<ClientCascadeSnapshot> parseSnapshot(String json) {
        if (json == null || json.isBlank()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(cascadeJson.readValue(json, ClientCascadeSnapshot.class));
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientCascadePreviewDTO> previewCascade(String clientCode) {
        String code = normalize(clientCode);
        Client client = clientRepository.findByClientCodeIgnoreCase(code).orElse(null);
        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        long pending = orderRepository.countPendingByClient(code);
        long activeAccounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(code).stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive())).count();
        long activeOwnedWh = warehouseRepository
                .findByOwnerClientCodeIgnoreCaseOrderByCodeAsc(code).stream()
                .filter(w -> Boolean.TRUE.equals(w.getActive())).count();
        long links = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code).size();
        ClientCascadePreviewDTO body = ClientCascadePreviewDTO.builder()
                .clientCode(code)
                .pendingOrderCount(pending)
                .activeCarrierAccountCount(activeAccounts)
                .clientOwnedWarehouseCount(activeOwnedWh)
                .clientWarehouseLinkCount(links)
                .clientCurrentlyActive(client.isActive())
                .build();
        return success("Cascade preview computed.", body);
    }

    @Override
    @Transactional
    public ApiResponse<Void> deleteClient(String clientCode) {
        String code = normalize(clientCode);
        Client client = clientRepository.findByClientCodeIgnoreCase(code).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }

        long orders = orderRepository.countOrdersUnified("", code, "", "", "", "", "", "", "", "");
        if (orders > 0) {
            return failure(HttpStatus.CONFLICT, ErrorCode.CLIENT_HAS_ORDERS,
                    "Client " + code + " has " + orders + " orders and cannot be deleted — deactivate it instead.");
        }

        // The client's config rides along — customs profiles and carrier accounts
        // are meaningless without their owner and must not linger as orphans
        // (they are linked by client-code string, not FK, so nothing cascades).
        customsProfileRepository.deleteAll(customsProfileRepository.findByClientCodeIgnoreCase(code));
        carrierAccountRefRepository.deleteAll(
                carrierAccountRefRepository.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(code));

        // Sprint 55 audit #288 — CLIENT-owned warehouses were previously
        // orphaned on delete. The cascadePreview flow (line 397-422) shows
        // clientOwnedWarehouseCount, but the delete path didn't act on it.
        // Now delete them alongside so the count matches reality.
        List<Warehouse> ownedWarehouses = warehouseRepository
                .findByOwnerClientCodeIgnoreCaseOrderByCodeAsc(code);
        int ownedWarehouseCount = ownedWarehouses.size();
        if (!ownedWarehouses.isEmpty()) {
            // Detach any client-warehouse links first (client_warehouse rows
            // are string-linked without FK cascade — same pattern as the
            // deactivate path at line 275-287).
            for (Warehouse w : ownedWarehouses) {
                List<ClientWarehouse> links = clientWarehouseRepository.findByWarehouseId(w.getId());
                if (!links.isEmpty()) clientWarehouseRepository.deleteAll(links);
            }
            warehouseRepository.deleteAll(ownedWarehouses);
        }

        clientRepository.delete(client);
        String cascadeSummary = "Client " + code + " deleted (including its carrier accounts and customs profiles"
                + (ownedWarehouseCount > 0 ? " and " + ownedWarehouseCount + " client-owned warehouse(s)" : "")
                + ").";
        auditService.record(AuditService.DELETE, AuditService.CLIENT,
                client.getId(), code, null, cascadeSummary);
        return success(cascadeSummary, null);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<CarrierAccountRefDTO>> listClientAccounts(String clientCode) {
        String code = normalize(clientCode);

        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }

        List<CarrierAccountRefDTO> accounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(code)
                .stream()
                .map(this::toAccountSummary)
                .toList();

        return success("Client accounts retrieved successfully.", accounts);
    }

    // ===== helpers =====

    private void applyFields(Client client, ClientUpsertRequest request) {
        client.setName(request.getName().trim());
        client.setEmail(trimOrNull(request.getEmail()));
        client.setPhone(trimOrNull(request.getPhone()));

        // Sprint 50 Tier 1 finding #4 — persist per-tenant defaults.
        // Every value is uppercased (currencies, unit codes, country codes
        // are all upper by convention) and length-normalised so the CHECK
        // constraints in V6 don't reject stray whitespace.
        client.setDefaultCurrency(trimUpperOrNull(request.getDefaultCurrency()));
        client.setDefaultWeightUnit(trimUpperOrNull(request.getDefaultWeightUnit()));
        client.setDefaultDimUnit(trimUpperOrNull(request.getDefaultDimUnit()));
        client.setTimezone(trimOrNull(request.getTimezone()));  // tz identifiers preserve case
        client.setDefaultOriginCountry(trimUpperOrNull(request.getDefaultOriginCountry()));

        Address shipFrom = toAddress(request.getShipFrom());
        client.setShipFrom(shipFrom);

        boolean sameAsShipFrom = !Boolean.FALSE.equals(request.getReturnSameAsShipFrom());
        client.setReturnSameAsShipFrom(sameAsShipFrom);
        // Store the distinct return address only when the client keeps one;
        // otherwise clear it so it visibly mirrors ship-from.
        client.setReturnAddress(sameAsShipFrom ? null : toAddress(request.getReturnAddress()));
    }

    /** Sprint 50 Tier 1 finding #4 — trim + uppercase a code field, mapping blank to null. */
    private static String trimUpperOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /** Trim an address DTO into an entity Address; uppercase the country (defaults US). */
    private Address toAddress(AddressDTO dto) {
        if (dto == null) {
            return Address.builder().country("US").build();
        }
        return Address.builder()
                .name(trimOrNull(dto.getName()))
                .line1(trimOrNull(dto.getLine1()))
                .line2(trimOrNull(dto.getLine2()))
                .city(trimOrNull(dto.getCity()))
                .state(trimOrNull(dto.getState()))
                .zip(trimOrNull(dto.getZip()))
                .country(StringUtils.hasText(dto.getCountry()) ? dto.getCountry().trim().toUpperCase(Locale.ROOT) : "US")
                .phone(trimOrNull(dto.getPhone()))
                .build();
    }

    private AddressDTO toAddressDTO(Address address) {
        if (address == null) {
            return null;
        }
        return AddressDTO.builder()
                .name(address.getName())
                .line1(address.getLine1())
                .line2(address.getLine2())
                .city(address.getCity())
                .state(address.getState())
                .zip(address.getZip())
                .country(address.getCountry())
                .phone(address.getPhone())
                .build();
    }

    private ClientDTO toDTO(Client client) {
        List<CarrierAccountRefDTO> accounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(client.getClientCode())
                .stream()
                .map(this::toAccountSummary)
                .toList();

        return ClientDTO.builder()
                .id(client.getId())
                .clientCode(client.getClientCode())
                .name(client.getName())
                .email(client.getEmail())
                .phone(client.getPhone())
                .status(client.getStatus())
                // Sprint 50 Tier 1 finding #4 — per-tenant defaults.
                .defaultCurrency(client.getDefaultCurrency())
                .defaultWeightUnit(client.getDefaultWeightUnit())
                .defaultDimUnit(client.getDefaultDimUnit())
                .timezone(client.getTimezone())
                .defaultOriginCountry(client.getDefaultOriginCountry())
                .shipFrom(toAddressDTO(client.getShipFrom()))
                .returnAddress(toAddressDTO(
                        Boolean.FALSE.equals(client.getReturnSameAsShipFrom()) ? client.getReturnAddress() : null))
                .returnSameAsShipFrom(!Boolean.FALSE.equals(client.getReturnSameAsShipFrom()))
                .createdAt(client.getCreatedAt())
                .updatedAt(client.getUpdatedAt())
                .carrierAccounts(accounts)
                .orderCount(orderRepository.countOrdersUnified("", client.getClientCode().toUpperCase(Locale.ROOT), "", "", "", "", "", "", "", ""))
                .build();
    }

    private CarrierAccountRefDTO toAccountSummary(CarrierAccountRef account) {
        return CarrierAccountRefDTO.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .carrierCode(account.getCarrierCode())
                .accountName(account.getAccountName())
                .customerNo(account.getCustomerNo())
                .environment(account.getEnvironment())
                .isDefault(Boolean.TRUE.equals(account.getIsDefault()))
                .clientDefault(Boolean.TRUE.equals(account.getClientDefault()))
                .active(!Boolean.FALSE.equals(account.getActive()))
                .complete(account.isComplete())
                .verified(account.getVerified())
                .lastVerifiedAt(account.getLastVerifiedAt())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    private String normalize(String code) {
        return code != null ? code.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .code(200)
                .message(message)
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .code(status.value())
                .errorCode(errorCode.name())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
