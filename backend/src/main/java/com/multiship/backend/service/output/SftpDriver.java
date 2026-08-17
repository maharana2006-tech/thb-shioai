package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.SystemSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Sprint 52 — {@link DestinationType#SFTP} driver. Uploads the payload
 * to a customer's SFTP drop over an SSH session established with either
 * password or private-key auth.
 *
 * <p>Sprint 52 output-polish (follow-ups #1 + #2):
 * <ul>
 *   <li>Sessions are now cached via {@link SftpSessionPool}. The bulk-
 *       label profile that emerged post-launch (thousands of labels/hr
 *       per tenant) turns per-dispatch handshake into a real cost;
 *       pooling amortises it. Cache is bounded (32 sessions, 60s idle
 *       TTL) so a many-tenant deployment can't leak connections.</li>
 *   <li>Strict host-key checking is now opt-in via
 *       {@code knownHostsSecretId} on the config. When set, the driver
 *       loads the encrypted known_hosts body via
 *       {@link CryptoService} and enables {@code StrictHostKeyChecking=yes}.
 *       When absent (legacy rows), behaviour is preserved — checking
 *       stays off so existing customer drops keep working.</li>
 * </ul>
 *
 * <p>Secrets: the config JSON never carries a plaintext password / key /
 * known_hosts blob. It stores a {@code *SecretId} that points at a row
 * in {@link SystemSetting}; the value there is stored AES-GCM encrypted
 * (Sprint 49 Tier 0 {@link CryptoService}). This driver decrypts at
 * use-time and never logs the plaintext.
 */
@Slf4j
@Component
public class SftpDriver implements OutputDriver {

    static final int  NETWORK_TIMEOUT_MS = 10_000;
    static final int  DEFAULT_PORT       = 22;
    static final String AUTH_PASSWORD    = "PASSWORD";
    static final String AUTH_KEY         = "KEY";

    private final ObjectMapper objectMapper;
    private final SystemSettingRepository systemSettingRepository;
    private final CryptoService cryptoService;
    private final SftpSessionPool sessionPool;
    /**
     * Test seam — production wires an always-fresh {@code new JSch()}
     * supplier so pooled sessions don't share identities. Tests can
     * inject a pre-configured JSch (e.g. with an in-process host key
     * fixture) to exercise the strict-checking branch.
     */
    private final Supplier<JSch> jschFactory;

    @Autowired
    public SftpDriver(ObjectMapper objectMapper,
                      SystemSettingRepository systemSettingRepository,
                      CryptoService cryptoService,
                      SftpSessionPool sessionPool) {
        this(objectMapper, systemSettingRepository, cryptoService, sessionPool, JSch::new);
    }

    /** Test constructor — accepts a JSch factory so integration tests can
     *  pre-seed known-hosts state without touching the filesystem. */
    public SftpDriver(ObjectMapper objectMapper,
                      SystemSettingRepository systemSettingRepository,
                      CryptoService cryptoService,
                      SftpSessionPool sessionPool,
                      Supplier<JSch> jschFactory) {
        this.objectMapper = objectMapper;
        this.systemSettingRepository = systemSettingRepository;
        this.cryptoService = cryptoService;
        this.sessionPool = sessionPool;
        this.jschFactory = jschFactory;
    }

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

        // Resolve secret material once — used both for auth AND for the
        // pool-key fingerprint so a rotated password evicts the stale
        // session instead of trying to reuse it.
        String password = null;
        String privateKeyPem = null;
        String knownHostsBody = null;
        if (AUTH_PASSWORD.equalsIgnoreCase(cfg.getAuthType())) {
            password = decrypt(destination.getId(), cfg.getPasswordSecretId(), "SFTP password");
        } else if (AUTH_KEY.equalsIgnoreCase(cfg.getAuthType())) {
            privateKeyPem = decrypt(destination.getId(), cfg.getPrivateKeySecretId(), "SFTP private-key");
        }
        if (cfg.getKnownHostsSecretId() != null && !cfg.getKnownHostsSecretId().isBlank()) {
            knownHostsBody = decrypt(destination.getId(), cfg.getKnownHostsSecretId(), "SFTP known-hosts");
        }

        String authFingerprint = SftpSessionPool.fingerprint(
                password != null ? password : privateKeyPem);
        String knownHostsFingerprint = SftpSessionPool.fingerprint(knownHostsBody);
        String poolKey = SftpSessionPool.buildKey(
                cfg.getHost(), port, cfg.getUsername(),
                authFingerprint, knownHostsFingerprint);

        final String pw = password;
        final String pem = privateKeyPem;
        final String kh = knownHostsBody;
        Supplier<Session> factory = () -> openSession(destination.getId(), cfg, port, pw, pem, kh);

        SftpSessionPool.Handle handle = null;
        ChannelSftp sftp = null;
        try {
            handle = sessionPool.acquire(poolKey, factory);
            Session session = handle.session();
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(NETWORK_TIMEOUT_MS);

            try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
                String target = remoteDir.endsWith("/")
                        ? remoteDir + fileName
                        : remoteDir + "/" + fileName;
                sftp.put(in, target);
            }
            log.info("SFTP delivered {} bytes to {}@{}:{}{}/{} (destination_id={}, pooled={})",
                    payload.length, cfg.getUsername(), cfg.getHost(), port,
                    remoteDir, fileName, destination.getId(), true);
        } catch (Exception ex) {
            // On any failure evict the session — a wedged jsch session
            // often reports connected but rejects further channels.
            if (handle != null) handle.invalidate();
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "SFTP upload failed to " + cfg.getHost() + ":" + port + " — " + ex.getMessage(), ex);
        } finally {
            if (sftp != null) {
                try { sftp.disconnect(); } catch (Exception ignored) { /* fine */ }
            }
            if (handle != null) handle.close(); // releases the per-session lock
        }
    }

    /**
     * Open a fresh JSch session — called by the pool when it has no
     * live entry for the cache key. Never called on the hot path when a
     * pooled session is reusable.
     */
    private Session openSession(Long destinationId,
                                OutputConfig.SftpConfig cfg,
                                int port,
                                String password,
                                String privateKeyPem,
                                String knownHostsBody) {
        try {
            JSch jsch = jschFactory.get();
            if (privateKeyPem != null) {
                jsch.addIdentity("sftp-key-" + destinationId,
                        privateKeyPem.getBytes(StandardCharsets.UTF_8),
                        null, null);
            }
            Properties props = new Properties();
            if (knownHostsBody != null && !knownHostsBody.isBlank()) {
                jsch.setKnownHosts(new java.io.ByteArrayInputStream(
                        knownHostsBody.getBytes(StandardCharsets.UTF_8)));
                props.put("StrictHostKeyChecking", "yes");
            } else {
                // Legacy behaviour: customer drops usually have a self-signed
                // host key that we can't pre-provision. Documented risk;
                // opt-in strict checking is now available via
                // knownHostsSecretId on the config.
                props.put("StrictHostKeyChecking", "no");
            }
            Session session = jsch.getSession(cfg.getUsername(), cfg.getHost(), port);
            if (password != null) {
                session.setPassword(password);
            }
            session.setConfig(props);
            session.setTimeout(NETWORK_TIMEOUT_MS);
            session.connect(NETWORK_TIMEOUT_MS);
            return session;
        } catch (Exception ex) {
            throw new OutputDeliveryException(destinationId, DestinationType.SFTP,
                    "SFTP connect failed to " + cfg.getHost() + ":" + port + " — " + ex.getMessage(), ex);
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
