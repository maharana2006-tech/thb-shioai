package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.dto.OutputDestinationDTO;
import com.multiship.backend.dto.OutputDestinationUpsertRequest;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.ClientOutputDestinationRepository;
import com.multiship.backend.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 52 — CRUD service backing the {@code /api/v1/admin/output-destinations}
 * page. Owns the SFTP secret-swap flow: the UI submits password /
 * private-key material as plaintext, this service encrypts it into a
 * {@link SystemSetting} row and rewrites the config JSON with a
 * pointer id.
 *
 * <p>Reads never surface the encrypted material. {@link #toDto} rewrites
 * the config JSON so the UI shows "***set***" / "***unset***" in place
 * of the pointer id — safe for logs, safe for UI display, keeps the
 * secret confidential even inside a compromised admin session's browser
 * dev-tools.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutputDestinationAdminService {

    private final ClientOutputDestinationRepository destinationRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final OutputDestinationService outputDestinationService;
    /** Sprint 52 output-polish (follow-up #3) — realistic per-type payloads. */
    private final TestPayloadFactory testPayloadFactory;

    /** Sentinel prefix on system_settings.setting_key for SFTP secrets. */
    static final String SFTP_SECRET_PREFIX = "sftp.secret.";

    public List<OutputDestinationDTO> list(String clientCode) {
        List<ClientOutputDestination> rows = (clientCode == null || clientCode.isBlank())
                ? destinationRepository.findAllByOrderByClientCodeAscDocTypeAscIdAsc()
                : destinationRepository.findByClientCodeOrderByDocTypeAscIdAsc(clientCode);
        return rows.stream().map(this::toDto).toList();
    }

    public Optional<OutputDestinationDTO> get(Long id) {
        return destinationRepository.findById(id).map(this::toDto);
    }

    @Transactional
    public OutputDestinationDTO create(OutputDestinationUpsertRequest req, String actor) {
        ClientOutputDestination entity = ClientOutputDestination.builder()
                .clientCode(req.getClientCode().trim())
                .docType(req.getDocType())
                .destinationType(req.getDestinationType())
                .config(processConfig(req, null, actor))
                .active(req.isActive())
                .notes(req.getNotes())
                .build();
        entity = destinationRepository.save(entity);
        log.info("OutputDestination created id={} client={} docType={} destType={} by={}",
                entity.getId(), entity.getClientCode(), entity.getDocType(),
                entity.getDestinationType(), actor);
        return toDto(entity);
    }

    @Transactional
    public Optional<OutputDestinationDTO> update(Long id, OutputDestinationUpsertRequest req, String actor) {
        return destinationRepository.findById(id).map(entity -> {
            entity.setClientCode(req.getClientCode().trim());
            entity.setDocType(req.getDocType());
            entity.setDestinationType(req.getDestinationType());
            entity.setConfig(processConfig(req, entity, actor));
            entity.setActive(req.isActive());
            entity.setNotes(req.getNotes());
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            log.info("OutputDestination updated id={} by={}", entity.getId(), actor);
            return toDto(entity);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        return destinationRepository.findById(id).map(row -> {
            // Best-effort: clean up SFTP secret rows attached to this
            // destination. If cleanup fails we still delete the row —
            // orphan secrets are harmless (no code path references them).
            cleanupSecrets(row);
            destinationRepository.delete(row);
            log.info("OutputDestination deleted id={}", id);
            return true;
        }).orElse(false);
    }

    /**
     * Admin "test" button — dispatch a doc-type-appropriate synthetic
     * payload to the destination without touching the real shipment
     * flow. The payload is built by {@link TestPayloadFactory} so a
     * Zebra printer gets ZPL, an IPP queue gets a 1-page PDF, and an
     * SFTP / LOCAL_FS drop gets a ZPL fixture. Every payload embeds
     * the current UTC timestamp for visual confirmation.
     *
     * @throws IllegalArgumentException when the destination id doesn't exist
     */
    public DispatchResult test(Long id) {
        ClientOutputDestination dest = destinationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no such destination: " + id));
        String protocol = readProtocol(dest);
        TestPayloadFactory.Payload p = testPayloadFactory.build(
                dest.getDestinationType(), dest.getDocType(), protocol, dest.getId());
        DispatchContext ctx = new DispatchContext(
                null, null, dest.getClientCode(), p.getContentType(), p.getFileNameHint());
        log.info("OutputDestinationAdminService.test id={} destType={} docType={} protocol={} "
                        + "payloadSize={} contentType={}",
                id, dest.getDestinationType(), dest.getDocType(), protocol,
                p.getBytes().length, p.getContentType());
        // We intentionally bypass the DB copy here — this is a test ping,
        // not a real shipment document. Call the driver directly.
        return outputDestinationService.testDispatch(dest, dest.getDocType(), p.getBytes(), ctx);
    }

    /** Extract the {@code protocol} field from the PRINTER config JSON;
     *  {@code null} for other destination types or malformed JSON. */
    private String readProtocol(ClientOutputDestination dest) {
        if (dest.getDestinationType() != DestinationType.PRINTER) return null;
        try {
            JsonNode node = objectMapper.readTree(dest.getConfig());
            return str(node, "protocol");
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Handle SFTP secret material: if the request carries a plaintext
     * password / key, encrypt + store it in {@code system_settings}
     * under a new sentinel key, then rewrite the config JSON so the
     * saved row references the sentinel by id.
     *
     * <p>On update, if no new plaintext arrives, we keep the existing
     * pointer intact — that's how "leave the secret alone, only change
     * the host" works. If the caller changes the auth type (KEY→PASSWORD)
     * without supplying new material, we clear the stale pointer.
     */
    private String processConfig(OutputDestinationUpsertRequest req,
                                 ClientOutputDestination existing,
                                 String actor) {
        try {
            ObjectNode config = (ObjectNode) objectMapper.readTree(req.getConfig());
            if (req.getDestinationType() != DestinationType.SFTP) {
                return objectMapper.writeValueAsString(config);
            }
            String priorConfigJson = existing == null ? null : existing.getConfig();
            ObjectNode priorCfg = priorConfigJson == null ? null
                    : (ObjectNode) objectMapper.readTree(priorConfigJson);

            if (req.getSftpPasswordPlain() != null && !req.getSftpPasswordPlain().isBlank()) {
                String id = writeSecret(req.getClientCode(), "password", req.getSftpPasswordPlain(), actor);
                config.put("passwordSecretId", id);
                // Remove opposite-auth pointer if it's dangling.
                config.remove("privateKeySecretId");
            } else if (priorCfg != null && priorCfg.has("passwordSecretId")
                    && "PASSWORD".equalsIgnoreCase(str(config, "authType"))) {
                // Keep the existing pointer.
                config.put("passwordSecretId", priorCfg.get("passwordSecretId").asText());
            }

            if (req.getSftpPrivateKeyPlain() != null && !req.getSftpPrivateKeyPlain().isBlank()) {
                String id = writeSecret(req.getClientCode(), "key", req.getSftpPrivateKeyPlain(), actor);
                config.put("privateKeySecretId", id);
                config.remove("passwordSecretId");
            } else if (priorCfg != null && priorCfg.has("privateKeySecretId")
                    && "KEY".equalsIgnoreCase(str(config, "authType"))) {
                config.put("privateKeySecretId", priorCfg.get("privateKeySecretId").asText());
            }

            // Sprint 52 output-polish (follow-up #2): known_hosts is
            // orthogonal to the auth secret — either auth mode can opt
            // in to strict host-key checking. Same "swap plaintext for
            // pointer id" pattern.
            if (req.getSftpKnownHostsPlain() != null && !req.getSftpKnownHostsPlain().isBlank()) {
                String id = writeSecret(req.getClientCode(), "known_hosts",
                        req.getSftpKnownHostsPlain(), actor);
                config.put("knownHostsSecretId", id);
            } else if (priorCfg != null && priorCfg.has("knownHostsSecretId")) {
                // Preserve the existing pointer on update-without-new-material.
                config.put("knownHostsSecretId", priorCfg.get("knownHostsSecretId").asText());
            }

            return objectMapper.writeValueAsString(config);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid config JSON: " + ex.getMessage(), ex);
        }
    }

    private String writeSecret(String clientCode, String kind, String plaintext, String actor) {
        if (!cryptoService.isAvailable()) {
            throw new IllegalStateException(
                    "SECRETS_ENCRYPTION_KEY is not configured; refusing to store SFTP secret in plaintext");
        }
        String key = SFTP_SECRET_PREFIX + clientCode + "." + kind + "." + UUID.randomUUID();
        SystemSetting row = new SystemSetting();
        row.setKey(key);
        row.setEncryptedValue(cryptoService.encrypt(plaintext));
        row.setUpdatedBy(actor);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        systemSettingRepository.save(row);
        log.info("SFTP {} secret stored as {} by {}", kind, key, actor);
        return key;
    }

    private void cleanupSecrets(ClientOutputDestination row) {
        try {
            JsonNode config = objectMapper.readTree(row.getConfig());
            String pwdId = str(config, "passwordSecretId");
            String keyId = str(config, "privateKeySecretId");
            String khId  = str(config, "knownHostsSecretId");
            if (pwdId != null) systemSettingRepository.findByKey(pwdId).ifPresent(systemSettingRepository::delete);
            if (keyId != null) systemSettingRepository.findByKey(keyId).ifPresent(systemSettingRepository::delete);
            if (khId  != null) systemSettingRepository.findByKey(khId).ifPresent(systemSettingRepository::delete);
        } catch (Exception ex) {
            log.warn("SFTP secret cleanup failed for destination {}: {}", row.getId(), ex.getMessage());
        }
    }

    OutputDestinationDTO toDto(ClientOutputDestination row) {
        return OutputDestinationDTO.builder()
                .id(row.getId())
                .clientCode(row.getClientCode())
                .docType(row.getDocType())
                .destinationType(row.getDestinationType())
                .configSafe(sanitiseConfig(row.getConfig()))
                .active(row.isActive())
                .notes(row.getNotes())
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

    /**
     * Rewrite the config JSON so any secret-pointer field surfaces as
     * a "***set***" / "***unset***" marker. Never lets the pointer id
     * or (worst case) plaintext ciphertext leak to the wire.
     */
    String sanitiseConfig(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return rawJson;
        try {
            ObjectNode cfg = (ObjectNode) objectMapper.readTree(rawJson);
            if (cfg.has("passwordSecretId")) {
                cfg.put("passwordSecretId", "***set***");
            }
            if (cfg.has("privateKeySecretId")) {
                cfg.put("privateKeySecretId", "***set***");
            }
            if (cfg.has("knownHostsSecretId")) {
                cfg.put("knownHostsSecretId", "***set***");
            }
            return objectMapper.writeValueAsString(cfg);
        } catch (Exception ex) {
            return "{\"parseError\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String str(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }
}
