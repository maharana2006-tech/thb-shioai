package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientShippingPolicyDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpdateClientPolicyRequest;
import com.multiship.backend.model.ClientShippingPolicy;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientShippingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientShippingPolicyServiceImpl implements ClientShippingPolicyService {

    private final ClientShippingPolicyRepository repo;
    private final ClientRepository clientRepository;
    private final ClientAllowedServiceRepository allowedServiceRepository;
    /**
     * Sprint 50 Tier 0.5 PR H - defence-in-depth belt on path-param
     * clientCode. Controller SpEL from PR F already blocks scoped USERs,
     * keeping the guard here survives future refactors. Optional so
     * pre-PR-H tests still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientShippingPolicyDTO> get(String clientCode) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        // Absent row = platform defaults (CHEAPEST, no cutoff); the resolver
        // treats a missing policy the same as this synthetic default row.
        ClientShippingPolicy policy = repo.findByClientCodeIgnoreCase(code)
                .orElseGet(() -> ClientShippingPolicy.builder()
                        .clientCode(code)
                        .rateStrategy(ClientShippingPolicy.STRATEGY_CHEAPEST)
                        .build());
        return success("Shipping policy retrieved successfully.", toDTO(policy));
    }

    @Override
    @Transactional
    public ApiResponse<ClientShippingPolicyDTO> update(String clientCode, UpdateClientPolicyRequest request) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        String strategy = normalize(request.getRateStrategy());
        if (!ClientShippingPolicy.STRATEGY_CHEAPEST.equals(strategy)
                && !ClientShippingPolicy.STRATEGY_FASTEST.equals(strategy)
                && !ClientShippingPolicy.STRATEGY_FIXED.equals(strategy)) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "rateStrategy must be CHEAPEST, FASTEST, or FIXED.");
        }
        if (ClientShippingPolicy.STRATEGY_FIXED.equals(strategy)) {
            if (request.getFixedServiceId() == null) {
                return failure(HttpStatus.BAD_REQUEST, ErrorCode.POLICY_FIXED_SERVICE_REQUIRED,
                        "rateStrategy=FIXED requires fixedServiceId.");
            }
            // The fixed service must be on the client's allowlist — otherwise
            // shipment resolution would try to use a service the client can't.
            boolean allowed = allowedServiceRepository
                    .existsByClientCodeIgnoreCaseAndServiceId(code, request.getFixedServiceId());
            if (!allowed) {
                return failure(HttpStatus.BAD_REQUEST, ErrorCode.POLICY_FIXED_SERVICE_REQUIRED,
                        "Service " + request.getFixedServiceId() + " is not on client " + code + "'s allowlist.");
            }
        }

        ClientShippingPolicy policy = repo.findByClientCodeIgnoreCase(code)
                .orElseGet(() -> ClientShippingPolicy.builder().clientCode(code).build());
        policy.setRateStrategy(strategy);
        policy.setFixedServiceId(
                ClientShippingPolicy.STRATEGY_FIXED.equals(strategy) ? request.getFixedServiceId() : null);
        policy.setCutoffTime(request.getCutoffTime());
        policy.setCutoffTz(request.getCutoffTz());
        repo.save(policy);

        return success("Shipping policy updated for client " + code + ".", toDTO(policy));
    }

    // ===== helpers =====

    private ClientShippingPolicyDTO toDTO(ClientShippingPolicy p) {
        return ClientShippingPolicyDTO.builder()
                .clientCode(p.getClientCode())
                .rateStrategy(p.getRateStrategy())
                .fixedServiceId(p.getFixedServiceId())
                .cutoffTime(p.getCutoffTime())
                .cutoffTz(p.getCutoffTz())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
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
