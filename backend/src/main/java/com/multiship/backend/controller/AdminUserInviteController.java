package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UserInviteRequest;
import com.multiship.backend.dto.UserInviteResponse;
import com.multiship.backend.model.UserInvite;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.service.UserInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Sprint 50 Tier 0.5 PR D — admin mints + lists user invites.
 *
 * <p>ADMIN-only. Complements {@link com.multiship.backend.controller.SystemSettingsController}
 * (Tier 0) and precedes PR E's user-management admin page.
 */
@Tag(name = "Admin user invites",
        description = "Sprint 50 — invite-only signup: mint an invite pre-scoped to a client + role.")
@RestController
@RequestMapping("/api/v1/admin/user-invites")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserInviteController {

    /** ROLEs that admins can invite. ADMIN is deliberately excluded — that role
     *  is created out-of-band (e.g. seed / dedicated admin flow), never by invite. */
    private static final Set<String> INVITABLE_ROLES = Set.of("USER", "TENANT");

    private final UserInviteService inviteService;
    private final ClientRepository clientRepository;

    /**
     * Base URL the invite-accept link points at. Defaults to the
     * frontend origin from the request; overridable via
     * {@code invite.accept-link-base-url} for HTTPS-behind-proxy.
     */
    @Value("${invite.accept-link-base-url:}")
    private String acceptLinkBaseUrl;

    @Operation(summary = "Mint an invite (admin)")
    @PostMapping
    public ResponseEntity<ApiResponse<UserInviteResponse>> mint(
            @Valid @RequestBody UserInviteRequest req,
            @AuthenticationPrincipal UserDetails admin,
            HttpServletRequest request) {

        String role = req.getRole().trim().toUpperCase();
        if (!INVITABLE_ROLES.contains(role)) {
            return ResponseEntity.status(400).body(error(400, ErrorCode.VALIDATION_ERROR,
                    "Invitable roles are USER or TENANT. Got: " + role));
        }
        String clientCode = req.getClientCode().trim();
        if (!clientRepository.existsByClientCodeIgnoreCase(clientCode)) {
            return ResponseEntity.status(404).body(error(404, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + clientCode + " was not found."));
        }

        String baseUrl = resolveBaseUrl(request);
        UserInvite invite = inviteService.mint(req.getEmail(), clientCode, role,
                admin != null ? admin.getUsername() : "system", baseUrl);

        UserInviteResponse body = UserInviteResponse.of(invite,
                baseUrl + "/invite/" + invite.getToken());
        return ResponseEntity.status(201).body(ApiResponse.<UserInviteResponse>builder()
                .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                .message("Invite issued. Email sent (or logged if SMTP not configured); "
                        + "the raw accept link is echoed on the response for copy-fallback.")
                .data(body).build());
    }

    @Operation(summary = "List invites (admin)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserInviteResponse>>> list(HttpServletRequest request) {
        String baseUrl = resolveBaseUrl(request);
        List<UserInviteResponse> data = inviteService.listAll().stream()
                .map(i -> UserInviteResponse.of(i, baseUrl + "/invite/" + i.getToken()))
                .toList();
        return ResponseEntity.ok(ApiResponse.<List<UserInviteResponse>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(data.size() + " invite(s).")
                .data(data).build());
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (acceptLinkBaseUrl != null && !acceptLinkBaseUrl.isBlank()) {
            return acceptLinkBaseUrl;
        }
        return request.getScheme() + "://" + request.getServerName()
                + (isDefaultPort(request) ? "" : ":" + request.getServerPort());
    }

    private boolean isDefaultPort(HttpServletRequest r) {
        return ("http".equalsIgnoreCase(r.getScheme()) && r.getServerPort() == 80)
                || ("https".equalsIgnoreCase(r.getScheme()) && r.getServerPort() == 443);
    }

    private <T> ApiResponse<T> error(int code, ErrorCode err, String msg) {
        return ApiResponse.<T>builder()
                .status("ERROR").code(code).timestamp(LocalDateTime.now())
                .errorCode(err.name()).message(msg).build();
    }
}
