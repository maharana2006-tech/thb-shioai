package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientBillingMarkupDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpdateClientMarkupRequest;
import com.multiship.backend.model.ClientBillingMarkup;
import com.multiship.backend.repository.ClientBillingMarkupRepository;
import com.multiship.backend.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientBillingMarkupServiceImpl implements ClientBillingMarkupService {

    private final ClientBillingMarkupRepository repo;
    private final ClientRepository clientRepository;
    /**
     * Sprint 50 Tier 0.5 PR H - defence-in-depth belt on path-param
     * clientCode. Controller SpEL from PR F already blocks scoped USERs at
     * /clients/OTHER/**, but the service-layer guard survives future
     * refactors. Optional so pre-PR-H tests still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientBillingMarkupDTO> get(String clientCode) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        // Absent row = zero markup, USD; the resolver treats it identically
        // to this synthetic default row.
        ClientBillingMarkup markup = repo.findByClientCodeIgnoreCase(code)
                .orElseGet(() -> ClientBillingMarkup.builder()
                        .clientCode(code)
                        .kind(ClientBillingMarkup.KIND_PERCENT)
                        .value(BigDecimal.ZERO)
                        .currency("USD")
                        .build());
        return success("Billing markup retrieved successfully.", toDTO(markup));
    }

    @Override
    @Transactional
    public ApiResponse<ClientBillingMarkupDTO> update(String clientCode, UpdateClientMarkupRequest request) {
        String code = normalize(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        String kind = normalize(request.getKind());
        if (!ClientBillingMarkup.KIND_PERCENT.equals(kind) && !ClientBillingMarkup.KIND_FLAT.equals(kind)) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.MARKUP_INVALID,
                    "kind must be PERCENT or FLAT.");
        }
        if (request.getValue() == null || request.getValue().signum() < 0) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.MARKUP_INVALID,
                    "value must be non-negative.");
        }
        String currency = normalize(request.getCurrency());
        if (currency.length() != 3) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.MARKUP_INVALID,
                    "currency must be 3 letters (ISO-4217).");
        }

        ClientBillingMarkup markup = repo.findByClientCodeIgnoreCase(code)
                .orElseGet(() -> ClientBillingMarkup.builder().clientCode(code).build());
        markup.setKind(kind);
        markup.setValue(request.getValue());
        markup.setCurrency(currency);
        repo.save(markup);

        return success("Billing markup updated for client " + code + ".", toDTO(markup));
    }

    // ===== helpers =====

    private ClientBillingMarkupDTO toDTO(ClientBillingMarkup m) {
        return ClientBillingMarkupDTO.builder()
                .clientCode(m.getClientCode())
                .kind(m.getKind())
                .value(m.getValue())
                .currency(m.getCurrency())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
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
