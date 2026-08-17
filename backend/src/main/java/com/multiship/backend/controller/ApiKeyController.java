package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiKeyIssueRequest;
import com.multiship.backend.dto.ApiKeyResponse;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin management of external API keys. Issuing returns the full token exactly
 * once; thereafter only masked forms are shown. All endpoints require ADMIN.
 */
@Tag(name = "API Keys", description = "Issue and revoke API keys for external applications")
@RestController
@RequestMapping("/api/v1/api-keys")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;
    private final ClientRepository clientRepository;

    @Operation(summary = "Issue a new API key (full token shown once)")
    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyResponse>> issue(@Valid @RequestBody ApiKeyIssueRequest req,
                                                             @AuthenticationPrincipal UserDetails admin) {
        // A blank client (or "*") mints a platform-wide key: it ships for ANY client,
        // and each external call must then name the client in its request body.
        String clientCode = org.springframework.util.StringUtils.hasText(req.getClientCode())
                ? req.getClientCode().trim()
                : com.multiship.backend.config.ApiKeyPrincipal.ALL_CLIENTS;
        boolean allClients = com.multiship.backend.config.ApiKeyPrincipal.ALL_CLIENTS.equals(clientCode);
        if (!allClients && !clientRepository.existsByClientCodeIgnoreCase(clientCode)) {
            return ResponseEntity.status(404).body(ApiResponse.<ApiKeyResponse>builder()
                    .status("ERROR").code(404).timestamp(LocalDateTime.now())
                    .message("Client " + clientCode + " was not found.")
                    .errorCode(ErrorCode.CLIENT_NOT_FOUND.name())
                    .build());
        }
        ApiKeyService.IssuedKey issued = apiKeyService.issue(
                req.getName().trim(), clientCode, req.getEnvironment(), req.getScopes(),
                admin != null ? admin.getUsername() : null);

        ApiKeyResponse body = ApiKeyResponse.of(issued.record(),
                apiKeyService.maskedToken(issued.record()), issued.plaintextToken());
        return ResponseEntity.status(201).body(ApiResponse.<ApiKeyResponse>builder()
                .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                .message("API key issued. Copy the token now — it will not be shown again.")
                .data(body)
                .build());
    }

    @Operation(summary = "List API keys (masked)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ApiKeyResponse>>> list() {
        List<ApiKeyResponse> keys = apiKeyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(k -> ApiKeyResponse.of(k, apiKeyService.maskedToken(k), null))
                .toList();
        return ResponseEntity.ok(ApiResponse.<List<ApiKeyResponse>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(keys.size() + " API key(s).")
                .data(keys)
                .build());
    }

    @Operation(summary = "Rotate an API key",
            description = "Mints a fresh key inheriting the old key's client, environment, and scopes. "
                    + "The old key stays valid for a 24h grace window; requests using it during that window "
                    + "get Deprecation + Sunset headers (RFC 8594). The new plaintext token is shown once.")
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiResponse<ApiKeyResponse>> rotate(@PathVariable Long id,
                                                               @AuthenticationPrincipal UserDetails admin) {
        var issued = apiKeyService.rotate(id, admin != null ? admin.getUsername() : null);
        if (issued.isEmpty()) {
            ApiKey existing = apiKeyRepository.findById(id).orElse(null);
            int code = existing == null ? 404 : 409;
            // Audit B5 — canonical error codes; was ORDER_NOT_FOUND (copy-paste
            // from a sibling controller) + generic VALIDATION_ERROR.
            return ResponseEntity.status(code).body(ApiResponse.<ApiKeyResponse>builder()
                    .status("ERROR").code(code).timestamp(LocalDateTime.now())
                    .message(existing == null ? "API key not found." : "Cannot rotate a revoked key — issue a new one.")
                    .errorCode(existing == null
                            ? ErrorCode.API_KEY_NOT_FOUND.name()
                            : ErrorCode.API_KEY_ALREADY_REVOKED.name())
                    .build());
        }
        ApiKeyResponse body = ApiKeyResponse.of(issued.get().record(),
                apiKeyService.maskedToken(issued.get().record()), issued.get().plaintextToken());
        return ResponseEntity.status(201).body(ApiResponse.<ApiKeyResponse>builder()
                .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                .message("API key rotated. The old key stays valid for 24h with Deprecation/Sunset headers. "
                        + "Copy the new token now — it will not be shown again.")
                .data(body)
                .build());
    }

    @Operation(summary = "Revoke an API key")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable Long id) {
        boolean revoked = apiKeyService.revoke(id);
        if (!revoked) {
            ApiKey existing = apiKeyRepository.findById(id).orElse(null);
            int code = existing == null ? 404 : 409;
            // Audit B5 — canonical error codes; see rotate() above.
            return ResponseEntity.status(code).body(ApiResponse.<Void>builder()
                    .status("ERROR").code(code).timestamp(LocalDateTime.now())
                    .message(existing == null ? "API key not found." : "API key is already revoked.")
                    .errorCode(existing == null
                            ? ErrorCode.API_KEY_NOT_FOUND.name()
                            : ErrorCode.API_KEY_ALREADY_REVOKED.name())
                    .build());
        }
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("API key revoked.")
                .build());
    }
}
