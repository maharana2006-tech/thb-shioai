package com.multiship.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.ExternalSystemClientLoginOverride;
import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.service.externalsystems.ExternalSystemConfigService;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import com.multiship.backend.service.externalsystems.HealthCheckResult;
import com.multiship.backend.service.externalsystems.LoginContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S2 admin surface for the S1 external-systems framework. Drives the
 * FE {@code /settings/external-systems} page (S3).
 *
 * <p>ADMIN role required. All secret writes go through
 * {@link ExternalSystemConfigService} (never plaintext at rest / in
 * logs). GET responses NEVER include secret values — only whether a
 * secret is set.
 */
@Tag(name = "External systems", description = "Admin CRUD for external-system connections (NDS Oracle, future REST integrations, etc.)")
@RestController
@RequestMapping("/api/v1/admin/external-systems")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ExternalSystemsAdminController {

    private final ExternalSystemConfigService config;
    private final ExternalSystemRegistry registry;
    private final ObjectMapper objectMapper;
    private final com.multiship.backend.service.TenantSettingsService tenantSettings;

    // ─── connectors ─────────────────────────────────────────────────

    @Operation(summary = "List every ExternalSystemConnector currently registered "
            + "(system_type + config-shape class). FE uses this to render the "
            + "New Connection dropdown.")
    @GetMapping("/connectors")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listConnectors() {
        List<Map<String, Object>> body = registry.connectors().stream()
                .map(c -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("systemType", c.systemType());
                    row.put("configType", c.configType().getSimpleName());
                    return row;
                })
                .toList();
        return ok(body);
    }

    // ─── connections ────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<List<ConnectionSummary>>> list() {
        List<ConnectionSummary> body = config.listConnections().stream()
                .map(this::summarize)
                .toList();
        return ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConnectionDetail>> get(@PathVariable Long id) {
        return config.findById(id)
                .map(c -> ok(detail(c)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(errBody(
                        HttpStatus.NOT_FOUND, "Connection " + id + " not found.")));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConnectionDetail>> create(
            @RequestBody ConnectionUpsertRequest req, Authentication auth) {
        ExternalSystemConnection c = new ExternalSystemConnection();
        applyUpsert(c, req);
        ExternalSystemConnection saved = config.saveConnection(c, actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<ConnectionDetail>builder()
                        .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                        .data(detail(saved)).build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ConnectionDetail>> update(
            @PathVariable Long id,
            @RequestBody ConnectionUpsertRequest req,
            Authentication auth) {
        ExternalSystemConnection c = config.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Connection " + id + " not found."));
        applyUpsert(c, req);
        ExternalSystemConnection saved = config.saveConnection(c, actor(auth));
        registry.reload(saved.getName());
        return ok(detail(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        config.findById(id).ifPresent(c -> registry.reload(c.getName()));
        config.deleteConnection(id);
        return ok(null);
    }

    // ─── secrets ────────────────────────────────────────────────────

    @Operation(summary = "Set (or clear) an encrypted secret on a connection. "
            + "Passing null / blank plaintext deletes the row.")
    @PutMapping("/{id}/secrets/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> putSecret(
            @PathVariable Long id,
            @PathVariable String key,
            @RequestBody SecretUpsertRequest req,
            Authentication auth) {
        if (req == null || req.plaintext == null || req.plaintext.isEmpty()) {
            config.deleteSecret(id, key);
        } else {
            config.putSecret(id, key, req.plaintext, actor(auth));
        }
        config.findById(id).ifPresent(c -> registry.reload(c.getName()));
        return ok(Map.of("connectionId", id, "secretKey", key,
                "isSet", req != null && req.plaintext != null && !req.plaintext.isEmpty()));
    }

    // ─── client-login overrides ─────────────────────────────────────

    @GetMapping("/{id}/client-overrides")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClientOverrides(
            @PathVariable Long id) {
        List<Map<String, Object>> body = config.listClientOverrides(id).stream()
                .map(o -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("clientCode", o.getClientCode());
                    row.put("username", o.getUsername());
                    row.put("updatedAt", o.getUpdatedAt());
                    row.put("updatedBy", o.getUpdatedBy() == null ? "" : o.getUpdatedBy());
                    return row;
                })
                .toList();
        return ok(body);
    }

    @PutMapping("/{id}/client-overrides/{clientCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> putClientOverride(
            @PathVariable Long id,
            @PathVariable String clientCode,
            @RequestBody ClientOverrideUpsertRequest req,
            Authentication auth) {
        if (req == null || req.username == null || req.password == null) {
            throw new IllegalArgumentException("username + password required");
        }
        config.putClientOverride(id, clientCode, req.username, req.password, actor(auth));
        config.findById(id).ifPresent(c -> registry.reload(c.getName()));
        return ok(Map.of("connectionId", id, "clientCode", clientCode, "isSet", true));
    }

    @DeleteMapping("/{id}/client-overrides/{clientCode}")
    public ResponseEntity<ApiResponse<Void>> deleteClientOverride(
            @PathVariable Long id, @PathVariable String clientCode) {
        config.deleteClientOverride(id, clientCode);
        config.findById(id).ifPresent(c -> registry.reload(c.getName()));
        return ok(null);
    }

    // ─── tenant → connection routing (writeback dispatcher target) ──

    /** PR #750 follow-up — expose the {@code writebackConnection} tenant
     *  setting so admins can route a client's writeback to this connection
     *  from the FE. The dispatcher's connection resolver
     *  (ExternalSystemWritebackDispatcher) reads exactly this key. */
    @Operation(summary = "List clients currently routed to this connection for writeback.")
    @GetMapping("/{id}/tenant-routings")
    public ResponseEntity<ApiResponse<List<String>>> listRoutings(@PathVariable Long id) {
        ExternalSystemConnection c = config.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Connection " + id + " not found."));
        return ok(tenantSettings.tenantsWithSetting(
                com.multiship.backend.service.externalsystems.writeback.ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION,
                c.getName()));
    }

    @Operation(summary = "Route this client's writeback to this connection. "
            + "Sets tenant_settings[tenantCode].writebackConnection to this connection's name.")
    @PutMapping("/{id}/tenant-routings/{tenantCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> putRouting(
            @PathVariable Long id, @PathVariable String tenantCode, Authentication auth) {
        ExternalSystemConnection c = config.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Connection " + id + " not found."));
        tenantSettings.putSetting(tenantCode.trim(),
                com.multiship.backend.service.externalsystems.writeback.ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION,
                c.getName(), actor(auth));
        return ok(Map.of("connectionId", id, "connectionName", c.getName(),
                "tenantCode", tenantCode, "isSet", true));
    }

    @Operation(summary = "Remove this client's writeback routing. "
            + "The dispatcher then falls back to the default connection.")
    @DeleteMapping("/{id}/tenant-routings/{tenantCode}")
    public ResponseEntity<ApiResponse<Void>> deleteRouting(
            @PathVariable Long id, @PathVariable String tenantCode) {
        // Only clear the setting when it actually points at THIS connection —
        // avoids accidentally clearing a routing that names a different one.
        ExternalSystemConnection c = config.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Connection " + id + " not found."));
        String key = com.multiship.backend.service.externalsystems.writeback.ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION;
        if (tenantSettings.getSetting(tenantCode.trim(), key)
                .map(v -> v.equalsIgnoreCase(c.getName()))
                .orElse(false)) {
            tenantSettings.deleteSetting(tenantCode.trim(), key);
        }
        return ok(null);
    }

    // ─── health + test-connection ───────────────────────────────────

    @Operation(summary = "Snapshot the health of one connection. Doesn't throw — "
            + "returns UP / DOWN / UNKNOWN + message.")
    @GetMapping("/{id}/health")
    public ResponseEntity<ApiResponse<HealthCheckResult>> health(@PathVariable Long id) {
        ExternalSystemConnection c = config.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Connection " + id + " not found."));
        return ok(registry.healthCheck(c.getName()));
    }

    @Operation(summary = "Dial the connection with a given login profile "
            + "(PRODUCTION / CLIENT) and confirm it can open. For CLIENT the "
            + "caller supplies a clientCode. Returns health-shape but with a "
            + "dedicated profile round-trip.")
    @PostMapping("/{id}/test-connection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testConnection(
            @PathVariable Long id, @RequestBody TestConnectionRequest req) {
        ExternalSystemConnection c = config.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Connection " + id + " not found."));
        String profile = req == null ? null : req.loginProfile;
        String client = req == null ? null : req.clientCode;
        LoginContext ctx = client == null || client.isBlank()
                ? (profile == null ? LoginContext.platform() : LoginContext.withProfile(profile))
                : LoginContext.forClientWithProfile(client, profile);
        try {
            Object handle = registry.connect(c.getName(), ctx);
            return ok(Map.of(
                    "connectionName", c.getName(),
                    "systemType", c.getSystemType(),
                    "profile", profile == null ? "" : profile,
                    "status", "UP",
                    "message", "connect() succeeded — handle=" + handle.getClass().getSimpleName()));
        } catch (ExternalSystemException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(503).timestamp(LocalDateTime.now())
                            .message(e.getMessage())
                            .errorCode(e.kind().name())
                            .data(Map.of(
                                    "connectionName", c.getName(),
                                    "systemType", c.getSystemType(),
                                    "profile", profile == null ? "" : profile,
                                    "status", "DOWN",
                                    "kind", e.kind().name(),
                                    "message", e.getMessage()))
                            .build());
        }
    }

    // ─────────────────────────── DTOs ───────────────────────────────

    public record ConnectionSummary(
            Long id, String name, String systemType, boolean active,
            LocalDateTime updatedAt, String updatedBy) {}

    public record ConnectionDetail(
            Long id, String name, String systemType, boolean active,
            String configJson, LocalDateTime createdAt, LocalDateTime updatedAt,
            String updatedBy,
            // V89 writeback flags — one boolean per payload field; same
            // flag gates both generate + void.
            boolean writebackTracking,
            boolean writebackShipDate,
            boolean writebackStatus,
            boolean writebackCarrier,
            boolean writebackService,
            boolean writebackFreight) {}

    public static class ConnectionUpsertRequest {
        public String name;
        public String systemType;
        public Boolean active;
        public String configJson;
        // V89 writeback flags. Nullable so a partial update (e.g. from a
        // client that doesn't know about the flags yet) doesn't zero
        // them; only flags the client explicitly sends get applied.
        public Boolean writebackTracking;
        public Boolean writebackShipDate;
        public Boolean writebackStatus;
        public Boolean writebackCarrier;
        public Boolean writebackService;
        public Boolean writebackFreight;
    }

    public static class SecretUpsertRequest {
        public String plaintext;
    }

    public static class ClientOverrideUpsertRequest {
        public String username;
        public String password;
    }

    public static class TestConnectionRequest {
        public String loginProfile;
        public String clientCode;
    }

    // ─────────────────────────── helpers ────────────────────────────

    private void applyUpsert(ExternalSystemConnection c, ConnectionUpsertRequest req) {
        if (req == null) throw new IllegalArgumentException("Request body required.");
        if (req.name != null) c.setName(req.name.trim());
        if (req.systemType != null) c.setSystemType(req.systemType.trim().toUpperCase());
        if (req.active != null) c.setActive(req.active);
        if (req.configJson != null) {
            // Validate the JSON parses before we save so the connector's
            // subsequent load doesn't blow up on read.
            try {
                objectMapper.readTree(req.configJson);
            } catch (Exception e) {
                throw new IllegalArgumentException("configJson is not valid JSON: " + e.getMessage());
            }
            c.setConfigJson(req.configJson);
        }
        // V89 writeback flags — null on the request means "don't touch";
        // FE always sends all six on save so partial-update surprise
        // isn't a concern in practice.
        if (req.writebackTracking != null) c.setWritebackTracking(req.writebackTracking);
        if (req.writebackShipDate != null) c.setWritebackShipDate(req.writebackShipDate);
        if (req.writebackStatus != null) c.setWritebackStatus(req.writebackStatus);
        if (req.writebackCarrier != null) c.setWritebackCarrier(req.writebackCarrier);
        if (req.writebackService != null) c.setWritebackService(req.writebackService);
        if (req.writebackFreight != null) c.setWritebackFreight(req.writebackFreight);
    }

    private ConnectionSummary summarize(ExternalSystemConnection c) {
        return new ConnectionSummary(c.getId(), c.getName(), c.getSystemType(),
                c.isActive(), c.getUpdatedAt(), c.getUpdatedBy());
    }

    private ConnectionDetail detail(ExternalSystemConnection c) {
        return new ConnectionDetail(c.getId(), c.getName(), c.getSystemType(),
                c.isActive(), c.getConfigJson(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getUpdatedBy(),
                Boolean.TRUE.equals(c.getWritebackTracking()),
                Boolean.TRUE.equals(c.getWritebackShipDate()),
                Boolean.TRUE.equals(c.getWritebackStatus()),
                Boolean.TRUE.equals(c.getWritebackCarrier()),
                Boolean.TRUE.equals(c.getWritebackService()),
                Boolean.TRUE.equals(c.getWritebackFreight()));
    }

    private String actor(Authentication auth) {
        return auth == null ? "unknown" : auth.getName();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(body).build());
    }

    private <T> ApiResponse<T> errBody(HttpStatus status, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR").code(status.value()).timestamp(LocalDateTime.now())
                .message(message).errorCode(status.name()).build();
    }
}
