package com.multiship.backend.service.externalsystems;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ExternalSystemClientLoginOverride;
import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.model.ExternalSystemSecret;
import com.multiship.backend.repository.ExternalSystemClientLoginOverrideRepository;
import com.multiship.backend.repository.ExternalSystemConnectionRepository;
import com.multiship.backend.repository.ExternalSystemSecretRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * S1 framework — ExternalSystemConfigService unit tests.
 * Anti-fallback: all repos + CryptoService mocked; no real DB or key.
 */
class ExternalSystemConfigServiceTest {

    private ExternalSystemConnectionRepository connectionRepo;
    private ExternalSystemSecretRepository secretRepo;
    private ExternalSystemClientLoginOverrideRepository overrideRepo;
    private CryptoService crypto;
    private ExternalSystemConfigService svc;

    @BeforeEach
    void setUp() {
        connectionRepo = mock(ExternalSystemConnectionRepository.class);
        secretRepo = mock(ExternalSystemSecretRepository.class);
        overrideRepo = mock(ExternalSystemClientLoginOverrideRepository.class);
        crypto = mock(CryptoService.class);
        when(crypto.isAvailable()).thenReturn(true);
        svc = new ExternalSystemConfigService(connectionRepo, secretRepo, overrideRepo, crypto);
    }

    // ─── connections ────────────────────────────────────────────────

    @Test
    void saveConnectionRequiresName() {
        ExternalSystemConnection c = new ExternalSystemConnection();
        c.setSystemType("NDS_ORACLE");
        assertThrows(IllegalArgumentException.class, () -> svc.saveConnection(c, "admin"));
    }

    @Test
    void saveConnectionRequiresSystemType() {
        ExternalSystemConnection c = new ExternalSystemConnection();
        c.setName("nds-default");
        assertThrows(IllegalArgumentException.class, () -> svc.saveConnection(c, "admin"));
    }

    @Test
    void saveConnectionDefaultsBlankConfigToEmptyObject() {
        ExternalSystemConnection c = new ExternalSystemConnection();
        c.setName("nds-default");
        c.setSystemType("NDS_ORACLE");
        c.setConfigJson(null);
        when(connectionRepo.save(any(ExternalSystemConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        ExternalSystemConnection saved = svc.saveConnection(c, "admin");
        assertEquals("{}", saved.getConfigJson());
        assertEquals("admin", saved.getUpdatedBy());
    }

    // ─── S4 helper: getDefaultNdsConnection ────────────────────────

    @Test
    void getDefaultNdsConnectionLooksUpByWellKnownName() {
        ExternalSystemConnection seed = new ExternalSystemConnection();
        seed.setId(42L);
        seed.setName("nds-default");
        seed.setSystemType("NDS_ORACLE");
        seed.setActive(false);
        when(connectionRepo.findByName("nds-default")).thenReturn(Optional.of(seed));

        Optional<ExternalSystemConnection> got = svc.getDefaultNdsConnection();
        assertTrue(got.isPresent());
        assertEquals("nds-default", got.get().getName());
        assertEquals("NDS_ORACLE", got.get().getSystemType());
        verify(connectionRepo).findByName("nds-default");
    }

    @Test
    void getDefaultNdsConnectionEmptyWhenNoSeedRow() {
        when(connectionRepo.findByName("nds-default")).thenReturn(Optional.empty());
        assertTrue(svc.getDefaultNdsConnection().isEmpty());
    }

    // ─── secrets ────────────────────────────────────────────────────

    @Test
    void getSecretDecryptsRoundTrip() {
        ExternalSystemSecret row = new ExternalSystemSecret();
        row.setEncryptedValue("cipherblob");
        when(secretRepo.findByConnectionIdAndSecretKey(1L, "productionPassword"))
                .thenReturn(Optional.of(row));
        when(crypto.decrypt("cipherblob")).thenReturn("hunter2");

        Optional<String> got = svc.getSecret(1L, "productionPassword");
        assertEquals("hunter2", got.orElseThrow());
        // Confirm no plaintext read from the DB.
        verify(crypto).decrypt("cipherblob");
    }

    @Test
    void getSecretEmptyWhenNoRow() {
        when(secretRepo.findByConnectionIdAndSecretKey(1L, "x")).thenReturn(Optional.empty());
        assertTrue(svc.getSecret(1L, "x").isEmpty());
    }

    @Test
    void getSecretThrowsWhenCryptoUnavailable() {
        when(crypto.isAvailable()).thenReturn(false);
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> svc.getSecret(1L, "productionPassword"));
        assertEquals(ExternalSystemException.Kind.SECRET_UNAVAILABLE, e.kind());
    }

    @Test
    void putSecretEncryptsAndUpserts() {
        when(crypto.encrypt("hunter2")).thenReturn("cipherblob");
        when(secretRepo.findByConnectionIdAndSecretKey(1L, "productionPassword"))
                .thenReturn(Optional.empty());
        when(secretRepo.save(any(ExternalSystemSecret.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.putSecret(1L, "productionPassword", "hunter2", "admin");

        ArgumentCaptor<ExternalSystemSecret> cap = ArgumentCaptor.forClass(ExternalSystemSecret.class);
        verify(secretRepo).save(cap.capture());
        assertEquals("cipherblob", cap.getValue().getEncryptedValue(),
                "must persist ciphertext, never plaintext");
        assertEquals("admin", cap.getValue().getUpdatedBy());
    }

    @Test
    void putSecretRejectsBlankValue() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.putSecret(1L, "productionPassword", null, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.putSecret(1L, "productionPassword", "", "admin"));
        verify(secretRepo, never()).save(any());
    }

    @Test
    void putSecretPreservesIdOnUpdate() {
        ExternalSystemSecret existing = new ExternalSystemSecret();
        existing.setId(99L);
        existing.setConnectionId(1L);
        existing.setSecretKey("productionPassword");
        existing.setEncryptedValue("old");
        when(secretRepo.findByConnectionIdAndSecretKey(1L, "productionPassword"))
                .thenReturn(Optional.of(existing));
        when(crypto.encrypt("new")).thenReturn("newcipher");
        when(secretRepo.save(any(ExternalSystemSecret.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.putSecret(1L, "productionPassword", "new", "admin");

        ArgumentCaptor<ExternalSystemSecret> cap = ArgumentCaptor.forClass(ExternalSystemSecret.class);
        verify(secretRepo).save(cap.capture());
        assertEquals(99L, cap.getValue().getId(), "id must be preserved on upsert");
        assertEquals("newcipher", cap.getValue().getEncryptedValue());
    }

    // ─── client-login overrides ─────────────────────────────────────

    @Test
    void getClientOverrideRoundTripDecryptsPassword() {
        ExternalSystemClientLoginOverride row = new ExternalSystemClientLoginOverride();
        row.setUsername("MKL246");
        row.setEncryptedPassword("cipherPwd");
        when(overrideRepo.findByConnectionIdAndClientCode(1L, "MKL246"))
                .thenReturn(Optional.of(row));
        when(crypto.decrypt("cipherPwd")).thenReturn("special-pwd");

        Optional<ConnectorSecretAccess.ClientLogin> got = svc.getClientOverride(1L, "MKL246");
        assertEquals("MKL246", got.orElseThrow().username());
        assertEquals("special-pwd", got.orElseThrow().password());
    }

    @Test
    void getClientOverrideEmptyWhenNoRow() {
        when(overrideRepo.findByConnectionIdAndClientCode(1L, "MKL246"))
                .thenReturn(Optional.empty());
        assertTrue(svc.getClientOverride(1L, "MKL246").isEmpty());
    }

    @Test
    void putClientOverrideEncrypts() {
        when(crypto.encrypt("plaintext")).thenReturn("cipher");
        when(overrideRepo.findByConnectionIdAndClientCode(1L, "MKL246"))
                .thenReturn(Optional.empty());
        when(overrideRepo.save(any(ExternalSystemClientLoginOverride.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.putClientOverride(1L, "MKL246", "USER246", "plaintext", "admin");

        ArgumentCaptor<ExternalSystemClientLoginOverride> cap =
                ArgumentCaptor.forClass(ExternalSystemClientLoginOverride.class);
        verify(overrideRepo).save(cap.capture());
        assertEquals("USER246", cap.getValue().getUsername());
        assertEquals("cipher", cap.getValue().getEncryptedPassword());
    }
}
