package com.multiship.backend.service;

import com.multiship.backend.dto.AddressDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.AttachWarehouseRequest;
import com.multiship.backend.dto.ClientWarehouseDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.WarehouseDTO;
import com.multiship.backend.model.Address;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientWarehouseServiceImpl implements ClientWarehouseService {

    private final ClientWarehouseRepository clientWarehouseRepository;
    private final WarehouseRepository warehouseRepository;
    private final ClientRepository clientRepository;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientWarehouseDTO>> listForClient(String clientCode) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        List<ClientWarehouseDTO> rows = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                .stream()
                .map(this::toDTO)
                .toList();
        return success("Client warehouses retrieved successfully.", rows);
    }

    @Override
    @Transactional
    public ApiResponse<ClientWarehouseDTO> attach(String clientCode, AttachWarehouseRequest request) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        Warehouse warehouse = warehouseRepository.findByCodeIgnoreCase(normalize(request.getWarehouseCode())).orElse(null);
        if (warehouse == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + normalize(request.getWarehouseCode()) + " was not found.");
        }
        // CLIENT-owned warehouses are private to their owner (Phase 1 open
        // decision #5 — user chose the recommendation).
        if (!warehouse.isPlatformOwned() && !code.equalsIgnoreCase(warehouse.getOwnerClientCode())) {
            return failure(HttpStatus.FORBIDDEN, ErrorCode.WAREHOUSE_ATTACH_FORBIDDEN,
                    "Warehouse " + warehouse.getCode() + " is owned by "
                            + warehouse.getOwnerClientCode() + " and cannot be attached to " + code + ".");
        }
        if (clientWarehouseRepository.findByClientCodeIgnoreCaseAndWarehouseId(code, warehouse.getId()).isPresent()) {
            return failure(HttpStatus.CONFLICT, ErrorCode.WAREHOUSE_ALREADY_ATTACHED,
                    "Warehouse " + warehouse.getCode() + " is already attached to client " + code + ".");
        }

        boolean makeDefault = Boolean.TRUE.equals(request.getMakeDefault());
        // First attachment auto-defaults regardless of the flag — a client
        // without a default is a broken state for shipment resolution.
        boolean isFirst = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code).isEmpty();
        boolean shouldBeDefault = makeDefault || isFirst;

        if (shouldBeDefault) {
            clearExistingDefault(code);
        }

        ClientWarehouse link = ClientWarehouse.builder()
                .clientCode(code)
                .warehouseId(warehouse.getId())
                .isDefault(shouldBeDefault)
                .build();
        clientWarehouseRepository.save(link);

        return success("Warehouse " + warehouse.getCode() + " attached to client " + code
                + (shouldBeDefault ? " as the default." : "."), toDTO(link));
    }

    @Override
    @Transactional
    public ApiResponse<Void> detach(String clientCode, String warehouseCode) {
        String code = normalize(clientCode);
        Warehouse warehouse = warehouseRepository.findByCodeIgnoreCase(normalize(warehouseCode)).orElse(null);
        if (warehouse == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + normalize(warehouseCode) + " was not found.");
        }
        ClientWarehouse link = clientWarehouseRepository
                .findByClientCodeIgnoreCaseAndWarehouseId(code, warehouse.getId()).orElse(null);
        if (link == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_WAREHOUSE_NOT_FOUND,
                    "Warehouse " + warehouse.getCode() + " is not attached to client " + code + ".");
        }
        boolean wasDefault = Boolean.TRUE.equals(link.getIsDefault());
        clientWarehouseRepository.delete(link);

        // If we deleted the default, promote the oldest remaining link so the
        // client always has one default (matches the "auto-default first link"
        // invariant enforced on attach).
        if (wasDefault) {
            clientWarehouseRepository
                    .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                    .stream().findFirst().ifPresent(next -> {
                        next.setIsDefault(true);
                        clientWarehouseRepository.save(next);
                    });
        }
        return success("Warehouse " + warehouse.getCode() + " detached from client " + code + ".", null);
    }

    @Override
    @Transactional
    public ApiResponse<ClientWarehouseDTO> setDefault(String clientCode, String warehouseCode) {
        String code = normalize(clientCode);
        Warehouse warehouse = warehouseRepository.findByCodeIgnoreCase(normalize(warehouseCode)).orElse(null);
        if (warehouse == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse " + normalize(warehouseCode) + " was not found.");
        }
        ClientWarehouse target = clientWarehouseRepository
                .findByClientCodeIgnoreCaseAndWarehouseId(code, warehouse.getId()).orElse(null);
        if (target == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_WAREHOUSE_NOT_FOUND,
                    "Warehouse " + warehouse.getCode() + " is not attached to client " + code + ".");
        }
        clearExistingDefault(code);
        target.setIsDefault(true);
        clientWarehouseRepository.save(target);
        return success("Warehouse " + warehouse.getCode() + " is now the default for client " + code + ".",
                toDTO(target));
    }

    // ===== helpers =====

    /** Clear the current default in a separate flush so the write of the new default doesn't trip the pending-uniqueness rule (enforced app-side here). */
    private void clearExistingDefault(String clientCode) {
        clientWarehouseRepository.findByClientCodeIgnoreCaseAndIsDefaultTrue(clientCode).ifPresent(existing -> {
            existing.setIsDefault(false);
            clientWarehouseRepository.save(existing);
        });
    }

    private ClientWarehouseDTO toDTO(ClientWarehouse link) {
        Warehouse w = warehouseRepository.findById(link.getWarehouseId()).orElse(null);
        return ClientWarehouseDTO.builder()
                .id(link.getId())
                .clientCode(link.getClientCode())
                .warehouse(w == null ? null : toWarehouseDTO(w))
                .isDefault(link.getIsDefault())
                .createdAt(link.getCreatedAt())
                .updatedAt(link.getUpdatedAt())
                .build();
    }

    private WarehouseDTO toWarehouseDTO(Warehouse w) {
        return WarehouseDTO.builder()
                .id(w.getId()).code(w.getCode()).name(w.getName())
                .address(toAddressDTO(w.getAddress()))
                .ownerType(w.getOwnerType()).ownerClientCode(w.getOwnerClientCode())
                .active(w.getActive())
                .createdAt(w.getCreatedAt()).updatedAt(w.getUpdatedAt())
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
