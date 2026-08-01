package com.multiship.backend.service;

import com.multiship.backend.dto.AddressDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.WarehouseDTO;
import com.multiship.backend.dto.WarehouseListFilters;
import com.multiship.backend.dto.WarehouseUpsertRequest;
import com.multiship.backend.model.Address;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final ClientWarehouseRepository clientWarehouseRepository;
    private final ClientRepository clientRepository;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<PageResponseDTO<WarehouseDTO>> listWarehouses(WarehouseListFilters filters) {
        int page = Math.max(filters.getPage(), 0);
        int size = Math.min(Math.max(filters.getSize(), 1), 100);
        String keyword = norm(filters.getSearch());
        String ownerType = norm(filters.getOwnerType());
        String ownerClientCode = norm(filters.getOwnerClientCode());
        String activeFilter = norm(filters.getActive());
        String sortBy = filters.getSortBy() != null ? filters.getSortBy() : "code";
        String sortDirection = filters.getSortDirection() != null ? filters.getSortDirection() : "ASC";

        Page<Warehouse> pageOfWarehouses = warehouseRepository.filter(
                keyword, ownerType, ownerClientCode, activeFilter,
                sortBy, sortDirection, PageRequest.of(page, size));

        List<WarehouseDTO> content = pageOfWarehouses.getContent().stream()
                .map(this::toDTO)
                .toList();

        return success("Warehouses retrieved successfully.",
                PageResponseDTO.of(content, page, size, pageOfWarehouses.getTotalElements(), sortBy, sortDirection));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<WarehouseDTO> getWarehouse(String code) {
        Warehouse w = warehouseRepository.findByCodeIgnoreCase(normalize(code)).orElse(null);
        if (w == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + normalize(code) + " was not found.");
        }
        return success("Warehouse retrieved successfully.", toDTO(w));
    }

    @Override
    @Transactional
    public ApiResponse<WarehouseDTO> createWarehouse(WarehouseUpsertRequest request) {
        String code = normalize(request.getCode());
        if (warehouseRepository.existsByCodeIgnoreCase(code)) {
            return failure(HttpStatus.CONFLICT, ErrorCode.WAREHOUSE_CODE_TAKEN,
                    "Warehouse code " + code + " is already registered.");
        }

        ApiResponse<WarehouseDTO> ownerCheck = validateOwner(request);
        if (ownerCheck != null) return ownerCheck;

        Warehouse w = Warehouse.builder()
                .code(code)
                .name(request.getName().trim())
                .address(toAddress(request.getAddress()))
                .ownerType(normalize(request.getOwnerType()))
                .ownerClientCode(isClientOwned(request) ? normalize(request.getOwnerClientCode()) : null)
                .active(!Boolean.FALSE.equals(request.getActive()))
                .build();
        warehouseRepository.save(w);

        auditService.record(AuditService.CREATE, AuditService.WAREHOUSE,
                w.getId(), code, toDTO(w), "Warehouse " + code + " created.");
        return success("Warehouse " + code + " created successfully.", toDTO(w));
    }

    @Override
    @Transactional
    public ApiResponse<WarehouseDTO> updateWarehouse(String code, WarehouseUpsertRequest request) {
        Warehouse w = warehouseRepository.findByCodeIgnoreCase(normalize(code)).orElse(null);
        if (w == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + normalize(code) + " was not found.");
        }

        ApiResponse<WarehouseDTO> ownerCheck = validateOwner(request);
        if (ownerCheck != null) return ownerCheck;

        // Code is immutable — it's the linkage key to ClientWarehouse and
        // ShipViaMapping rows. The mutable surface is name, address, owner, active.
        w.setName(request.getName().trim());
        w.setAddress(toAddress(request.getAddress()));
        w.setOwnerType(normalize(request.getOwnerType()));
        w.setOwnerClientCode(isClientOwned(request) ? normalize(request.getOwnerClientCode()) : null);
        if (request.getActive() != null) {
            w.setActive(request.getActive());
        }
        warehouseRepository.save(w);

        auditService.record(AuditService.UPDATE, AuditService.WAREHOUSE,
                w.getId(), w.getCode(), toDTO(w),
                "Warehouse " + w.getCode() + " updated.");
        return success("Warehouse " + w.getCode() + " updated successfully.", toDTO(w));
    }

    @Override
    @Transactional
    public ApiResponse<WarehouseDTO> toggleActive(String code) {
        Warehouse w = warehouseRepository.findByCodeIgnoreCase(normalize(code)).orElse(null);
        if (w == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + normalize(code) + " was not found.");
        }
        w.setActive(!Boolean.TRUE.equals(w.getActive()));
        warehouseRepository.save(w);
        String state = Boolean.TRUE.equals(w.getActive()) ? "active" : "inactive";
        auditService.record(AuditService.TOGGLE_ACTIVE, AuditService.WAREHOUSE,
                w.getId(), w.getCode(), java.util.Map.of("active", w.getActive()),
                "Warehouse " + w.getCode() + " is now " + state + ".");
        return success("Warehouse " + w.getCode() + " is now " + state + ".", toDTO(w));
    }

    @Override
    @Transactional
    public ApiResponse<Void> deleteWarehouse(String code) {
        String c = normalize(code);
        Warehouse w = warehouseRepository.findByCodeIgnoreCase(c).orElse(null);
        if (w == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + c + " was not found.");
        }
        // Detach every client link along with the warehouse itself; ShipViaMapping
        // rows keep their now-orphan warehouse_id and fall back to any-warehouse
        // resolution (the resolver treats missing warehouses as "no origin filter").
        long attachedClients = clientWarehouseRepository.countByWarehouseId(w.getId());
        if (attachedClients > 0) {
            clientWarehouseRepository.deleteAll(clientWarehouseRepository.findByWarehouseId(w.getId()));
        }
        Long deletedId = w.getId();
        warehouseRepository.delete(w);
        auditService.record(AuditService.DELETE, AuditService.WAREHOUSE,
                deletedId, c, null,
                "Warehouse " + c + " deleted"
                        + (attachedClients > 0 ? " (unlinked from " + attachedClients + " client(s))" : "") + ".");
        return success("Warehouse " + c + " deleted successfully"
                + (attachedClients > 0 ? " (unlinked from " + attachedClients + " client(s))" : "") + ".", null);
    }

    // ===== helpers =====

    /** Returns a populated failure ApiResponse when owner_type/owner_client_code is inconsistent, else null. */
    private ApiResponse<WarehouseDTO> validateOwner(WarehouseUpsertRequest request) {
        String ownerType = normalize(request.getOwnerType());
        boolean clientOwned = Warehouse.OWNER_CLIENT.equalsIgnoreCase(ownerType);
        boolean platformOwned = Warehouse.OWNER_PLATFORM.equalsIgnoreCase(ownerType);
        if (!clientOwned && !platformOwned) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.WAREHOUSE_OWNER_INVALID,
                    "ownerType must be PLATFORM or CLIENT.");
        }
        boolean hasOwnerClient = StringUtils.hasText(request.getOwnerClientCode());
        if (clientOwned && !hasOwnerClient) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.WAREHOUSE_OWNER_INVALID,
                    "CLIENT-owned warehouse requires ownerClientCode.");
        }
        if (platformOwned && hasOwnerClient) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.WAREHOUSE_OWNER_INVALID,
                    "PLATFORM warehouse must not carry ownerClientCode.");
        }
        if (clientOwned) {
            String ownerClientCode = normalize(request.getOwnerClientCode());
            if (!clientRepository.existsByClientCodeIgnoreCase(ownerClientCode)) {
                return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                        "Owner client " + ownerClientCode + " was not found.");
            }
        }
        return null;
    }

    private static boolean isClientOwned(WarehouseUpsertRequest request) {
        return Warehouse.OWNER_CLIENT.equalsIgnoreCase(request.getOwnerType());
    }

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
                .country(StringUtils.hasText(dto.getCountry())
                        ? dto.getCountry().trim().toUpperCase(Locale.ROOT) : "US")
                .phone(trimOrNull(dto.getPhone()))
                .build();
    }

    private AddressDTO toAddressDTO(Address address) {
        if (address == null) return null;
        return AddressDTO.builder()
                .name(address.getName()).line1(address.getLine1()).line2(address.getLine2())
                .city(address.getCity()).state(address.getState()).zip(address.getZip())
                .country(address.getCountry()).phone(address.getPhone())
                .build();
    }

    private WarehouseDTO toDTO(Warehouse w) {
        return WarehouseDTO.builder()
                .id(w.getId())
                .code(w.getCode())
                .name(w.getName())
                .address(toAddressDTO(w.getAddress()))
                .ownerType(w.getOwnerType())
                .ownerClientCode(w.getOwnerClientCode())
                .active(w.getActive())
                .attachedClientCount(clientWarehouseRepository.countByWarehouseId(w.getId()))
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }

    private String norm(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
