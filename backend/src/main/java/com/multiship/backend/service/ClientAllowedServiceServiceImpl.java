package com.multiship.backend.service;

import com.multiship.backend.dto.AllowServiceRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedServiceDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientAllowedService;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientAllowedServiceServiceImpl implements ClientAllowedServiceService {

    private final ClientAllowedServiceRepository repo;
    private final ClientRepository clientRepository;
    private final ShippingServiceRepository shippingServiceRepository;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientAllowedServiceDTO>> listForClient(String clientCode) {
        String code = normalize(clientCode);
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
        List<ClientAllowedServiceDTO> rows = repo.findAll().stream().map(this::toDTO).toList();
        return success("Service allowlist usage retrieved successfully.", rows);
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedServiceDTO> setDefault(String clientCode, Long serviceId) {
        String code = normalize(clientCode);
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
