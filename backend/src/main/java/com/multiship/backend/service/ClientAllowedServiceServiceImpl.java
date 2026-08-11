package com.multiship.backend.service;

import com.multiship.backend.dto.AllowServiceRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedServiceDTO;
import com.multiship.backend.dto.ClientAllowedServiceDestinationsDTO;
import com.multiship.backend.dto.ClientAllowedServiceWarehousesDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ReplaceAllowedServiceDestinationsRequest;
import com.multiship.backend.dto.ReplaceAllowedServiceWarehousesRequest;
import com.multiship.backend.model.ClientAllowedService;
import com.multiship.backend.model.ClientAllowedServiceDestination;
import com.multiship.backend.model.ClientAllowedServiceWarehouse;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ClientAllowedServiceDestinationRepository;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientAllowedServiceWarehouseRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ClientAllowedServiceServiceImpl implements ClientAllowedServiceService {

    private final ClientAllowedServiceRepository repo;
    private final ClientAllowedServiceDestinationRepository destinationRepository;
    private final ClientAllowedServiceWarehouseRepository warehouseGateRepository;
    private final ClientWarehouseRepository clientWarehouseRepository;
    private final ClientRepository clientRepository;
    private final ShippingServiceRepository shippingServiceRepository;

    /**
     * Sprint 50 Tier 0.5 PR H - clamp {@link #listAllAssignments} so a
     * scoped USER only sees their own client's allowlist rows. Optional so
     * pre-Sprint-50 unit tests that construct the service directly still
     * compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientAllowedServiceDTO>> listForClient(String clientCode) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        List<ClientAllowedServiceDTO> rows = repo
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                .stream().map(this::toDTO).toList();
        return success("Allowed services retrieved successfully.", rows);
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedServiceDTO> allow(String clientCode, AllowServiceRequest request) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        ShippingService service = shippingServiceRepository.findById(request.getServiceId()).orElse(null);
        if (service == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR,
                    "Shipping service " + request.getServiceId() + " was not found.");
        }
        if (repo.existsByClientCodeIgnoreCaseAndServiceId(code, service.getId())) {
            return failure(HttpStatus.CONFLICT, ErrorCode.ALLOWLIST_ALREADY_EXISTS,
                    "Service " + service.getServiceCode() + " is already allowed for client " + code + ".");
        }

        boolean makeDefault = Boolean.TRUE.equals(request.getMakeDefault());
        // Client's first allow auto-defaults so shipment resolution always
        // finds a default without an explicit setDefault call.
        boolean isFirst = repo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code).isEmpty();
        boolean shouldBeDefault = makeDefault || isFirst;

        if (shouldBeDefault) {
            clearExistingDefault(code);
        }

        ClientAllowedService link = ClientAllowedService.builder()
                .clientCode(code)
                .serviceId(service.getId())
                .isDefault(shouldBeDefault)
                .build();
        repo.save(link);

        return success("Service " + service.getServiceCode() + " allowed for client " + code
                + (shouldBeDefault ? " as the default." : "."), toDTO(link));
    }

    @Override
    @Transactional
    public ApiResponse<Void> remove(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService link = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (link == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not allowed for client " + code + ".");
        }
        boolean wasDefault = Boolean.TRUE.equals(link.getIsDefault());
        repo.delete(link);

        // Promote oldest remaining so the client always has a default when
        // it has ≥1 allowed service (mirrors the client-warehouse invariant).
        if (wasDefault) {
            repo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                    .stream().findFirst().ifPresent(next -> {
                        next.setIsDefault(true);
                        repo.save(next);
                    });
        }
        return success("Service " + serviceId + " removed from client " + code + ".", null);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientAllowedServiceDTO>> listAllAssignments() {
        // Sprint 50 Tier 0.5 PR H - scoped USERs only see their own client's
        // rows; platform operators (empty scope) see the full org-wide list.
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        List<ClientAllowedServiceDTO> rows = repo.findAll().stream()
                .filter(link -> scope.isEmpty()
                        || (link.getClientCode() != null
                                && scope.get().equalsIgnoreCase(link.getClientCode().trim())))
                .map(this::toDTO)
                .toList();
        return success("Service allowlist usage retrieved successfully.", rows);
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedServiceDTO> setDefault(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService target = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (target == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not allowed for client " + code + ".");
        }
        clearExistingDefault(code);
        target.setIsDefault(true);
        repo.save(target);
        return success("Service " + serviceId + " is now the default for client " + code + ".", toDTO(target));
    }

    // ===== helpers =====

    private void clearExistingDefault(String clientCode) {
        repo.findByClientCodeIgnoreCaseAndIsDefaultTrue(clientCode).ifPresent(existing -> {
            existing.setIsDefault(false);
            repo.save(existing);
        });
    }

    private ClientAllowedServiceDTO toDTO(ClientAllowedService link) {
        ShippingService s = shippingServiceRepository.findById(link.getServiceId()).orElse(null);
        return ClientAllowedServiceDTO.builder()
                .id(link.getId())
                .clientCode(link.getClientCode())
                .serviceId(link.getServiceId())
                .carrier(s == null ? null : s.getCarrier())
                .serviceCode(s == null ? null : s.getServiceCode())
                .serviceName(s == null ? null : s.getName())
                .scope(s == null ? null : s.getScope())
                .originCountry(s == null ? null : s.getOriginCountry())
                .isDefault(link.getIsDefault())
                .createdAt(link.getCreatedAt())
                .updatedAt(link.getUpdatedAt())
                .build();
    }

    // ===== Destination gate =====

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientAllowedServiceDestinationsDTO> getDestinations(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService allow = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (allow == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        List<String> countries = destinationRepository
                .findByAllowedServiceIdOrderByCountryAsc(allow.getId())
                .stream()
                .map(ClientAllowedServiceDestination::getCountry)
                .toList();
        return success("Service destinations retrieved successfully.",
                ClientAllowedServiceDestinationsDTO.builder()
                        .clientCode(code).serviceId(serviceId).countries(countries)
                        .build());
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedServiceDestinationsDTO> replaceDestinations(
            String clientCode, Long serviceId, ReplaceAllowedServiceDestinationsRequest request) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService allow = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (allow == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        // Diff-free replace: wipe first, insert deduped set.
        destinationRepository.deleteAllByAllowedServiceId(allow.getId());
        Set<String> deduped = request.getCountries() == null
                ? Set.of()
                : request.getCountries().stream()
                        .filter(c -> c != null && !c.isBlank())
                        .map(c -> c.trim().toUpperCase(Locale.ROOT))
                        .filter(c -> c.length() == 2)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String country : deduped) {
            destinationRepository.save(ClientAllowedServiceDestination.builder()
                    .allowedServiceId(allow.getId())
                    .clientCode(code)
                    .country(country)
                    .build());
        }
        return success("Service destinations updated successfully.",
                ClientAllowedServiceDestinationsDTO.builder()
                        .clientCode(code).serviceId(serviceId)
                        .countries(deduped.stream().sorted().toList())
                        .build());
    }

    @Override
    @Transactional
    public ApiResponse<Void> clearDestinations(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService allow = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (allow == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        destinationRepository.deleteAllByAllowedServiceId(allow.getId());
        return success("Destination gate cleared for service " + serviceId + " on client " + code + ".", null);
    }

    // ===== Warehouse gate (G1) =====

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientAllowedServiceWarehousesDTO> getWarehouses(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService allow = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (allow == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        List<Long> warehouseIds = warehouseGateRepository
                .findByAllowedServiceIdOrderByWarehouseIdAsc(allow.getId())
                .stream()
                .map(ClientAllowedServiceWarehouse::getWarehouseId)
                .toList();
        return success("Service warehouses retrieved successfully.",
                ClientAllowedServiceWarehousesDTO.builder()
                        .clientCode(code).serviceId(serviceId).warehouseIds(warehouseIds)
                        .build());
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedServiceWarehousesDTO> replaceWarehouses(
            String clientCode, Long serviceId, ReplaceAllowedServiceWarehousesRequest request) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService allow = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (allow == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        // Filter to warehouses actually attached to the client so a gate
        // can't reference a warehouse the client can't ship from anyway.
        Set<Long> clientAttached = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                .stream()
                .map(cw -> cw.getWarehouseId())
                .collect(java.util.stream.Collectors.toSet());

        // Diff-free replace: wipe first, insert deduped set.
        warehouseGateRepository.deleteAllByAllowedServiceId(allow.getId());
        Set<Long> deduped = request.getWarehouseIds() == null
                ? Set.of()
                : request.getWarehouseIds().stream()
                        .filter(id -> id != null && clientAttached.contains(id))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Long warehouseId : deduped) {
            warehouseGateRepository.save(ClientAllowedServiceWarehouse.builder()
                    .allowedServiceId(allow.getId())
                    .clientCode(code)
                    .warehouseId(warehouseId)
                    .build());
        }
        return success("Service warehouses updated successfully.",
                ClientAllowedServiceWarehousesDTO.builder()
                        .clientCode(code).serviceId(serviceId)
                        .warehouseIds(deduped.stream().sorted().toList())
                        .build());
    }

    @Override
    @Transactional
    public ApiResponse<Void> clearWarehouses(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ClientAllowedService allow = repo.findByClientCodeIgnoreCaseAndServiceId(code, serviceId).orElse(null);
        if (allow == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        warehouseGateRepository.deleteAllByAllowedServiceId(allow.getId());
        return success("Warehouse gate cleared for service " + serviceId + " on client " + code + ".", null);
    }

    // ===== helpers =====

    private String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message)
                .timestamp(LocalDateTime.now()).data(data).build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR").code(status.value()).errorCode(errorCode.name())
                .message(message).timestamp(LocalDateTime.now()).build();
    }
}
