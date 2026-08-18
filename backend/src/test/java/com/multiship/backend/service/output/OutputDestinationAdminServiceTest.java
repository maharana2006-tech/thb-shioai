package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.dto.OutputDestinationDTO;
import com.multiship.backend.dto.OutputDestinationUpsertRequest;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.ClientOutputDestinationRepository;
import com.multiship.backend.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 — verifies the admin service's SFTP secret-swap flow +
 * sanitisation of the config JSON on the read path.
 */
class OutputDestinationAdminServiceTest {

    private ClientOutputDestinationRepository destinationRepo;
    private SystemSettingRepository systemSettingRepo;
    private CryptoService cryptoService;
    private OutputDestinationService outputService;
    private ObjectMapper mapper;
    private OutputDestinationAdminService admin;

    private final Map<Long, ClientOutputDestination> destinations = new HashMap<>();
    private final Map<String, SystemSetting> secrets = new HashMap<>();
    private final AtomicLong destIdSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        destinationRepo   = mock(ClientOutputDestinationRepository.class);
        systemSettingRepo = mock(SystemSettingRepository.class);
        cryptoService     = mock(CryptoService.class);
        outputService     = mock(OutputDestinationService.class);
        mapper            = new ObjectMapper();

        when(destinationRepo.save(any(ClientOutputDestination.class))).thenAnswer(inv -> {
            ClientOutputDestination d = inv.getArgument(0);
            if (d.getId() == null) d.setId(destIdSeq.getAndIncrement());
            destinations.put(d.getId(), d);
            return d;
        });
        when(destinationRepo.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(destinations.get(inv.<Long>getArgument(0))));

        when(systemSettingRepo.save(any(SystemSetting.class))).thenAnswer(inv -> {
            SystemSetting s = inv.getArgument(0);
            secrets.put(s.getKey(), s);
            return s;
        });
        when(systemSettingRepo.findByKey(anyString())).thenAnswer(inv ->
                Optional.ofNullable(secrets.get(inv.<String>getArgument(0))));
        doAnswer(inv -> {
            SystemSetting s = inv.getArgument(0);
            if (s != null) secrets.remove(s.getKey());
            return null;
        }).when(systemSettingRepo).delete(any(SystemSetting.class));
        doAnswer(inv -> {
            ClientOutputDestination d = inv.getArgument(0);
            if (d != null) destinations.remove(d.getId());
            return null;
        }).when(destinationRepo).delete(any(ClientOutputDestination.class));

        when(cryptoService.isAvailable()).thenReturn(true);
        when(cryptoService.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");

        // Audit R2 #344 — new WebhookUrlValidator dep for the SSRF guard.
        // Use the real class with private-network + http overrides so existing
        // fixtures using 127.0.0.1 / test hostnames don't need updating.
        com.multiship.backend.service.external.WebhookUrlValidator urlValidator =
                new com.multiship.backend.service.external.WebhookUrlValidator();
        org.springframework.test.util.ReflectionTestUtils.setField(urlValidator, "allowPrivateNetworks", true);
        org.springframework.test.util.ReflectionTestUtils.setField(urlValidator, "allowHttp", true);
        admin = new OutputDestinationAdminService(destinationRepo, systemSettingRepo,
                cryptoService, mapper, outputService, new TestPayloadFactory(), urlValidator);
    }

    @Test
    void createSftpToMetadataHostIsRejectedForSsrf() {
        // Audit R2 #344 — an admin (or compromised admin session) pointing
        // an SFTP destination at AWS metadata (169.254.169.254) or any
        // cloud-metadata endpoint gets a 400 IllegalArgumentException.
        // The METADATA_HOSTS block is enforced even when allowPrivateNetworks
        // is set (see WebhookUrlValidator inline comment).
        com.multiship.backend.service.external.WebhookUrlValidator strictValidator =
                new com.multiship.backend.service.external.WebhookUrlValidator();
        // leave both env flags off — strict defaults
        OutputDestinationAdminService strictAdmin = new OutputDestinationAdminService(
                destinationRepo, systemSettingRepo, cryptoService, mapper, outputService,
                new TestPayloadFactory(), strictValidator);

        OutputDestinationUpsertRequest req = OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"169.254.169.254\",\"port\":22,"
                        + "\"username\":\"acme\",\"authType\":\"PASSWORD\","
                        + "\"remoteDir\":\"/upload\"}")
                .sftpPasswordPlain("s3cr3t")
                .active(true).build();

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> strictAdmin.create(req, "alice"));
        assertTrue(ex.getMessage().toLowerCase().contains("metadata"),
                "expected metadata-host rejection, got: " + ex.getMessage());
    }

    @Test
    void createLocalFsPersistsRow() {
        OutputDestinationUpsertRequest req = OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.LOCAL_FS)
                .config("{\"path\":\"/tmp/labels\"}")
                .active(true).build();

        OutputDestinationDTO dto = admin.create(req, "alice");

        assertNotNull(dto.getId());
        assertEquals("ACME", dto.getClientCode());
        assertTrue(dto.getConfigSafe().contains("/tmp/labels"));
    }

    @Test
    void createSftpEncryptsPasswordAndSwapsInPointer() throws Exception {
        OutputDestinationUpsertRequest req = OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"sftp.example.com\",\"port\":22,"
                        + "\"username\":\"acme\",\"authType\":\"PASSWORD\","
                        + "\"remoteDir\":\"/upload\"}")
                .active(true)
                .sftpPasswordPlain("s3cret")
                .build();

        OutputDestinationDTO dto = admin.create(req, "alice");

        // Stored row: config contains a passwordSecretId pointer.
        ClientOutputDestination stored = destinations.get(dto.getId());
        assertTrue(stored.getConfig().contains("passwordSecretId"));
        // The pointer key exists in system_settings with an encrypted value.
        String pointer = mapper.readTree(stored.getConfig()).get("passwordSecretId").asText();
        assertTrue(secrets.containsKey(pointer));
        assertEquals("enc(s3cret)", secrets.get(pointer).getEncryptedValue());

        // Sanitised DTO NEVER echoes the pointer id.
        assertFalse(dto.getConfigSafe().contains(pointer));
        assertTrue(dto.getConfigSafe().contains("***set***"));
    }

    @Test
    void updateWithoutNewSecretKeepsExistingPointer() throws Exception {
        // First create with a password.
        OutputDestinationDTO created = admin.create(OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"sftp.example.com\",\"username\":\"acme\",\"authType\":\"PASSWORD\"}")
                .active(true).sftpPasswordPlain("s3cret").build(), "alice");
        String originalPointer = mapper.readTree(destinations.get(created.getId()).getConfig())
                .get("passwordSecretId").asText();

        // Now update WITHOUT re-supplying the password.
        Optional<OutputDestinationDTO> updated = admin.update(created.getId(),
                OutputDestinationUpsertRequest.builder()
                        .clientCode("ACME").docType(DocType.LABEL)
                        .destinationType(DestinationType.SFTP)
                        .config("{\"host\":\"new.example.com\",\"username\":\"acme\",\"authType\":\"PASSWORD\"}")
                        .active(true).build(),
                "alice");

        assertTrue(updated.isPresent());
        String newPointer = mapper.readTree(destinations.get(created.getId()).getConfig())
                .get("passwordSecretId").asText();
        assertEquals(originalPointer, newPointer, "existing pointer preserved when no new secret is supplied");
    }

    @Test
    void deleteRemovesRowAndBestEffortSecrets() {
        OutputDestinationDTO created = admin.create(OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"h\",\"username\":\"u\",\"authType\":\"PASSWORD\"}")
                .active(true).sftpPasswordPlain("p").build(), "alice");
        assertTrue(secrets.size() >= 1);

        boolean deleted = admin.delete(created.getId());

        assertTrue(deleted);
        assertFalse(destinations.containsKey(created.getId()));
        // Secret row cleaned up alongside the destination.
        assertEquals(0, secrets.size());
    }

    @Test
    void createSftpWithKnownHostsEncryptsAndSanitises() throws Exception {
        OutputDestinationUpsertRequest req = OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"sftp.example.com\",\"username\":\"acme\",\"authType\":\"PASSWORD\"}")
                .active(true)
                .sftpPasswordPlain("s3cret")
                .sftpKnownHostsPlain("sftp.example.com ssh-rsa AAAAB3...\n")
                .build();

        OutputDestinationDTO dto = admin.create(req, "alice");

        ClientOutputDestination stored = destinations.get(dto.getId());
        String storedCfg = stored.getConfig();
        assertTrue(storedCfg.contains("knownHostsSecretId"),
                "config should carry a knownHostsSecretId pointer");
        String khPointer = mapper.readTree(storedCfg).get("knownHostsSecretId").asText();
        assertTrue(secrets.containsKey(khPointer),
                "encrypted known_hosts row must exist under the pointer key");
        assertEquals("enc(sftp.example.com ssh-rsa AAAAB3...\n)",
                secrets.get(khPointer).getEncryptedValue());
        // Sanitised DTO must NEVER echo the pointer id — only ***set***.
        assertFalse(dto.getConfigSafe().contains(khPointer));
        assertTrue(dto.getConfigSafe().contains("***set***"));
    }

    @Test
    void updateKeepsExistingKnownHostsPointerWhenNoNewMaterial() throws Exception {
        OutputDestinationDTO created = admin.create(OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"sftp.example.com\",\"username\":\"acme\",\"authType\":\"PASSWORD\"}")
                .active(true)
                .sftpPasswordPlain("s3cret")
                .sftpKnownHostsPlain("hostkey-body")
                .build(), "alice");
        String originalKh = mapper.readTree(destinations.get(created.getId()).getConfig())
                .get("knownHostsSecretId").asText();

        // Update — no new known_hosts material supplied.
        admin.update(created.getId(), OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"new.example.com\",\"username\":\"acme\",\"authType\":\"PASSWORD\"}")
                .active(true).build(), "alice");

        String preservedKh = mapper.readTree(destinations.get(created.getId()).getConfig())
                .get("knownHostsSecretId").asText();
        assertEquals(originalKh, preservedKh,
                "known-hosts pointer must be preserved on update without new material");
    }

    @Test
    void deleteAlsoCleansUpKnownHostsSecret() {
        OutputDestinationDTO created = admin.create(OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"h\",\"username\":\"u\",\"authType\":\"PASSWORD\"}")
                .active(true)
                .sftpPasswordPlain("p")
                .sftpKnownHostsPlain("kh")
                .build(), "alice");
        assertEquals(2, secrets.size(), "one row each for password + known_hosts");

        admin.delete(created.getId());

        assertEquals(0, secrets.size(),
                "both password AND known_hosts secret rows must be cleaned up");
    }

    @Test
    void createSftpWithoutEncryptionKeyRefuses() {
        when(cryptoService.isAvailable()).thenReturn(false);

        OutputDestinationUpsertRequest req = OutputDestinationUpsertRequest.builder()
                .clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config("{\"host\":\"h\",\"username\":\"u\",\"authType\":\"PASSWORD\"}")
                .active(true).sftpPasswordPlain("s3cret").build();

        assertThrows(Exception.class, () -> admin.create(req, "alice"));
    }
}
