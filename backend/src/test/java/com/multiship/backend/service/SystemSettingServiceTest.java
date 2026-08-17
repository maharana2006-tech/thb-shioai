package com.multiship.backend.service;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SystemSettingService} — the AES-GCM-backed
 * admin secrets store behind `/settings/system`. **Net-new** — no
 * prior test file.
 *
 * <p>Anti-fallback: repository + {@link CryptoService} are both mocked.
 * The real {@code CryptoService} would need a live encryption key
 * (base64 32-byte) to construct; we stub {@code isAvailable} + return
 * values so no real cryptographic path runs.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code getDecrypted} — 4 branches: empty repo, blank cipher,
 *       crypto unavailable, decrypt throws.</li>
 *   <li>{@code setEncrypted} — 3 branches: crypto-unavailable throws,
 *       new row upsert, existing row overwrite (id + createdAt preserved).</li>
 *   <li>{@code has} — 3 branches: missing, blank cipher = false, present = true.</li>
 *   <li>{@code maskedPreview} — 4 branches via the static mask helper.</li>
 * </ul>
 */
class SystemSettingServiceTest {

    private SystemSettingRepository repo;
    private CryptoService crypto;
    private SystemSettingService service;

    @BeforeEach
    void setUp() {
        repo = mock(SystemSettingRepository.class);
        crypto = mock(CryptoService.class);
        service = new SystemSettingService(repo, crypto);
    }

    // ================ helpers ================

    private static SystemSetting row(String key, String cipher) {
        SystemSetting r = new SystemSetting();
        r.setKey(key);
        r.setEncryptedValue(cipher);
        return r;
    }

    // ================ getDecrypted() ================

    @Test
    void getDecrypted_missingRow_returnsEmpty() {
        when(repo.findByKey("k")).thenReturn(Optional.empty());

        assertTrue(service.getDecrypted("k").isEmpty());
        verify(crypto, never()).decrypt(any());
    }

    @Test
    void getDecrypted_blankCipher_returnsEmpty_withoutCallingCrypto() {
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "")));

        assertTrue(service.getDecrypted("k").isEmpty());
        verify(crypto, never()).decrypt(any());
    }

    @Test
    void getDecrypted_cryptoUnavailable_returnsEmpty_andLogs() {
        // Documented tolerance: dev boots without SECRETS_ENCRYPTION_KEY;
        // reads gracefully degrade to empty instead of throwing.
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "abc-cipher")));
        when(crypto.isAvailable()).thenReturn(false);

        assertTrue(service.getDecrypted("k").isEmpty());
        verify(crypto, never()).decrypt(any());
    }

    @Test
    void getDecrypted_decryptThrows_returnsEmpty_notRethrown() {
        // Corrupted ciphertext must NOT crash reads — return empty so the
        // caller's fallback (env var) kicks in.
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "corrupt")));
        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.decrypt("corrupt")).thenThrow(new RuntimeException("bad GCM tag"));

        assertTrue(service.getDecrypted("k").isEmpty());
    }

    @Test
    void getDecrypted_happyPath_returnsPlaintext() {
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "cipher")));
        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.decrypt("cipher")).thenReturn("sk-openai-actual");

        assertEquals(Optional.of("sk-openai-actual"), service.getDecrypted("k"));
    }

    // ================ setEncrypted() ================

    @Test
    void setEncrypted_cryptoUnavailable_throwsIllegalStateException_beforeRepoWrite() {
        // Writes MUST require the encryption key — never store plaintext.
        when(crypto.isAvailable()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.setEncrypted("k", "value", "admin"));
        verify(repo, never()).save(any());
        verify(repo, never()).findByKey(any());
    }

    @Test
    void setEncrypted_newRow_persistsWithActorAndTimestamp() {
        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.encrypt("plain")).thenReturn("cipher");
        when(repo.findByKey("k")).thenReturn(Optional.empty()); // no existing row

        ArgumentCaptor<SystemSetting> cap = ArgumentCaptor.forClass(SystemSetting.class);
        when(repo.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.setEncrypted("k", "plain", "admin-user");

        SystemSetting saved = cap.getValue();
        assertEquals("k", saved.getKey());
        assertEquals("cipher", saved.getEncryptedValue());
        assertEquals("admin-user", saved.getUpdatedBy());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void setEncrypted_existingRow_overwritesInPlace() {
        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.encrypt("new-plain")).thenReturn("new-cipher");
        SystemSetting existing = row("k", "old-cipher");
        existing.setUpdatedBy("prior-admin");
        existing.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(repo.findByKey("k")).thenReturn(Optional.of(existing));

        ArgumentCaptor<SystemSetting> cap = ArgumentCaptor.forClass(SystemSetting.class);
        when(repo.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.setEncrypted("k", "new-plain", "new-admin");

        SystemSetting saved = cap.getValue();
        // Same instance (existing row updated).
        assertEquals("new-cipher", saved.getEncryptedValue());
        assertEquals("new-admin", saved.getUpdatedBy());
        assertTrue(saved.getUpdatedAt().isAfter(LocalDateTime.of(2026, 1, 1, 0, 0)),
                "updatedAt must advance on overwrite.");
    }

    // ================ has() ================

    @Test
    void has_missingRow_returnsFalse() {
        when(repo.findByKey("k")).thenReturn(Optional.empty());
        assertFalse(service.has("k"));
    }

    @Test
    void has_blankCipher_returnsFalse() {
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "")));
        assertFalse(service.has("k"));
    }

    @Test
    void has_presentCipher_returnsTrue() {
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "anything")));
        assertTrue(service.has("k"));
    }

    // ================ maskedPreview() ================

    @Test
    void maskedPreview_absent_returnsEmpty() {
        when(repo.findByKey("k")).thenReturn(Optional.empty());
        assertTrue(service.maskedPreview("k").isEmpty());
    }

    @Test
    void maskedPreview_present_returnsFourStarsPlusLastFour() {
        when(repo.findByKey("k")).thenReturn(Optional.of(row("k", "cipher")));
        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.decrypt("cipher")).thenReturn("sk-openai-abcd1234");

        assertEquals(Optional.of("****1234"), service.maskedPreview("k"));
    }

    // ================ mask() static helper ================

    @Test
    void mask_nullAndEmpty_returnEmpty() {
        assertEquals("", SystemSettingService.mask(null));
        assertEquals("", SystemSettingService.mask(""));
    }

    @Test
    void mask_shortStrings_returnJustAsterisks() {
        // <= 4 chars → '****' (no plaintext leak, no reveal of length).
        assertEquals("****", SystemSettingService.mask("a"));
        assertEquals("****", SystemSettingService.mask("abcd"));
    }

    @Test
    void mask_longStrings_returnAsterisksPlusLastFour() {
        assertEquals("****cdef", SystemSettingService.mask("abcdef"));
        assertEquals("****1234", SystemSettingService.mask("sk-openai-abcd1234"));
    }
}
