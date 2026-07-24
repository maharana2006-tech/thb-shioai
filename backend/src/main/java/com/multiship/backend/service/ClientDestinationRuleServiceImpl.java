package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientDestinationRulesDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ReplaceDestinationRulesRequest;
import com.multiship.backend.model.ClientDestinationRule;
import com.multiship.backend.repository.ClientDestinationRuleRepository;
import com.multiship.backend.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientDestinationRuleServiceImpl implements ClientDestinationRuleService {

    private final ClientDestinationRuleRepository repo;
    private final ClientRepository clientRepository;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientDestinationRulesDTO> get(String clientCode) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        List<ClientDestinationRule> rows = repo.findByClientCodeIgnoreCaseOrderByCountryAsc(code);
        String mode = rows.isEmpty() ? null : rows.get(0).getMode();
        List<String> countries = rows.stream().map(ClientDestinationRule::getCountry).toList();
        return success("Destination rules retrieved successfully.",
                ClientDestinationRulesDTO.builder()
                        .clientCode(code).mode(mode).countries(countries).build());
    }

    @Override
    @Transactional
    public ApiResponse<ClientDestinationRulesDTO> replace(String clientCode, ReplaceDestinationRulesRequest request) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        String mode = normalize(request.getMode());
        if (!ClientDestinationRule.MODE_ALLOW.equals(mode) && !ClientDestinationRule.MODE_DENY.equals(mode)) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "mode must be ALLOW or DENY.");
        }
        // Diff-free replace: clear everything, then insert the (deduped) set.
        repo.deleteAllByClientCode(code);
        // Explicit flush would happen at TX commit; the JPQL DELETE runs
        // ahead of the inserts so the unique(client_code, country) constraint
        // never trips mid-flush.
        Set<String> uniqueCountries = request.getCountries().stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .filter(c -> c.length() == 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String country : uniqueCountries) {
            repo.save(ClientDestinationRule.builder()
                    .clientCode(code).mode(mode).country(country).build());
        }
        return success("Destination rules updated successfully.",
                ClientDestinationRulesDTO.builder()
                        .clientCode(code).mode(mode).countries(uniqueCountries.stream().sorted().toList())
                        .build());
    }

    @Override
    @Transactional
    public ApiResponse<Void> clear(String clientCode) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        repo.deleteAllByClientCode(code);
        return success("Destination rules cleared for client " + code + " (ships anywhere).", null);
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
