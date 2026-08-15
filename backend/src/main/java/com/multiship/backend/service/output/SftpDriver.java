package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Properties;

/**
 * Sprint 52 — {@link DestinationType#SFTP} driver. Uploads the payload
 * to a customer's SFTP drop over an SSH session established with either
 * password or private-key auth.
 *
 * <p>Secrets: the config JSON never carries a plaintext password / key.
 * It stores a {@code passwordSecretId} / {@code privateKeySecretId} that
 * points at a row in {@link SystemSetting}; the value there is stored
 * AES-GCM encrypted (Sprint 49 Tier 0 {@link CryptoService}). This
 * driver decrypts at use-time and never logs the plaintext.
 *
 * <p>Connection is bounded by 10s connect + 10s data timeout. The
 * session + channel are opened and closed per dispatch — no pooling. For
 * the current bulk-label profile (up to ~500 orders / batch, one upload
 * per order, tenant SFTP drops sized for that volume) the overhead of a
 * fresh SSH handshake (~200ms) is negligible compared to the label
 * generation time (5-15s carrier RTT). Follow-up: cache sessions if a
 * profile ever changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpDriver implements OutputDriver {

    static final int  NETWORK_TIMEOUT_MS = 10_000;
    static final int  DEFAULT_PORT       = 22;
    static final String AUTH_PASSWORD    = "PASSWORD";
    static final String AUTH_KEY         = "KEY";

    private final ObjectMapper objectMapper;
    private final SystemSettingRepository systemSettingRepository;
    private final CryptoService cryptoService;

    @Override
    public DestinationType supports() {
        return DestinationType.SFTP;
    }

    @Override
    public void dispatch(ClientOutputDestination destination,
                         DocType docType,
                         byte[] payload,
                         DispatchContext ctx) {
        OutputConfig.SftpConfig cfg =
                OutputConfig.parse(objectMapper, destination.getConfig(), OutputConfig.SftpConfig.class);
        validate(destination.getId(), cfg);

        int port = cfg.getPort() != null ? cfg.getPort() : DEFAULT_PORT;
        String remoteDir = cfg.getRemoteDir() == null ? "." : cfg.getRemoteDir();
        String fileName = LocalFsDriver.buildFileName(docType, ctx);

        Session session = null;
        ChannelSftp sftp = null;
        try {
            JSch jsch = new JSch();
            if (AUTH_KEY.equalsIgnoreCase(cfg.getAuthType())) {
                String pem = decrypt(destination.getId(), cfg.getPrivateKeySecretId(),
                        "SFTP private-key");
                jsch.addIdentity("sftp-key-" + destination.getId(),
                        pem.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        null, null);
            }
            session = jsch.getSession(cfg.getUsername(), cfg.getHost(), port);
            if (AUTH_PASSWORD.equalsIgnoreCase(cfg.getAuthType())) {
                session.setPassword(decrypt(destination.getId(), cfg.getPasswordSecretId(),
                        "SFTP password"));
            }
            // NOTE: strict host-key checking off — a customer's SFTP drop
            // typically has a self-signed host key we can't pre-provision.
            // The channel is single-use per dispatch so a bounded blast
            // radius. Follow-up: expose knownHostsSecretId in config.
            Properties props = new Properties();
            props.put("StrictHostKeyChecking", "no");
            session.setConfig(props);
            session.setTimeout(NETWORK_TIMEOUT_MS);
            session.connect(NETWORK_TIMEOUT_MS);

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(NETWORK_TIMEOUT_MS);

            try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
                String target = remoteDir.endsWith("/")
                        ? remoteDir + fileName
                        : remoteDir + "/" + fileName;
                sftp.put(in, target);
            }
            log.info("SFTP delivered {} bytes to {}@{}:{}{}/{} (destination_id={})",
                    payload.length, cfg.getUsername(), cfg.getHost(), port,
                    remoteDir, fileName, destination.getId());
        } catch (Exception ex) {
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "SFTP upload failed to " + cfg.getHost() + ":" + port + " — " + ex.getMessage(), ex);
        } finally {
            if (sftp    != null) try { sftp.disconnect();   } catch (Exception ignored) { /* fine */ }
            if (session != null) try { session.disconnect(); } catch (Exception ignored) { /* fine */ }
        }
    }

    private static void validate(Long destinationId, OutputConfig.SftpConfig cfg) {
        if (cfg.getHost()     == null || cfg.getHost().isBlank())
            throw fail(destinationId, "SFTP host missing");
        if (cfg.getUsername() == null || cfg.getUsername().isBlank())
            throw fail(destinationId, "SFTP username missing");
        if (cfg.getAuthType() == null)
            throw fail(destinationId, "SFTP authType missing (PASSWORD or KEY)");
        if (AUTH_PASSWORD.equalsIgnoreCase(cfg.getAuthType())
                && (cfg.getPasswordSecretId() == null || cfg.getPasswordSecretId().isBlank()))
            throw fail(destinationId, "SFTP passwordSecretId missing for PASSWORD auth");
        if (AUTH_KEY.equalsIgnoreCase(cfg.getAuthType())
                && (cfg.getPrivateKeySecretId() == null || cfg.getPrivateKeySecretId().isBlank()))
            throw fail(destinationId, "SFTP privateKeySecretId missing for KEY auth");
    }

    private String decrypt(Long destinationId, String secretId, String label) {
        SystemSetting row = systemSettingRepository.findByKey(secretId).orElseThrow(() ->
                fail(destinationId, label + " secret not found (id=" + secretId + ")"));
        // SystemSettingService normally encrypts on save; call CryptoService
        // directly here so we can read the raw ciphertext + surface a
        // sensible error if the encryption key is missing.
        String raw = row.getEncryptedValue();
        if (raw == null || raw.isBlank())
            throw fail(destinationId, label + " secret has no value stored");
        if (!cryptoService.isAvailable())
            throw fail(destinationId, label + " needs SECRETS_ENCRYPTION_KEY to decrypt");
        try {
            return cryptoService.decrypt(raw);
        } catch (Exception ex) {
            throw fail(destinationId, label + " decrypt failed: " + ex.getMessage());
        }
    }

    private static OutputDeliveryException fail(Long destinationId, String message) {
        return new OutputDeliveryException(destinationId, DestinationType.SFTP, message);
    }
}
