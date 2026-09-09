package com.multiship.backend.controller;

import com.multiship.backend.dto.SystemSettingDTO;
import com.multiship.backend.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level Mockito tests for {@link SystemSettingsController} —
 * the `/api/v1/admin/system-settings` family behind `/settings/system`.
 *
 * <p>Anti-fallback: sole collaborator {@link SystemSettingService} mocked
 * in every test. Endpoints assert {@code times(1)} on the exercised
 * service methods + {@code never()} on siblings.
 *
 * <p>Endpoints covered (2):
 * <ul>
 *   <li>GET /api/v1/admin/system-settings           — list registry-known settings</li>
 *   <li>PUT /api/v1/admin/system-settings/{key}     — upsert (404 on unknown key)</li>
 * </ul>
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} is pinned via
 * reflection — the entire family is admin-only.
 */
class SystemSettingsControllerTest {

    private SystemSettingService service;
    private SystemSettingsController controller;

    private static final String KNOWN_KEY = "openai.api-key";
    private static final String FLAVOR_KEY = "carrier.stamps.api-flavor";
    /** Registry size — grow this in lockstep with SystemSettingsController.KNOWN_SETTINGS. */
    private static final int REGISTRY_SIZE = 2;

    @BeforeEach
    void setUp() {
        service = mock(SystemSettingService.class);
        controller = new SystemSettingsController(service);
    }

    // ================ helpers ================

    private static Authentication auth(String name) {
        return new UsernamePasswordAuthenticationToken(
                name, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // ================ GET / ================

    @Test
    void list_returnsOneEntryPerKnownSetting_withMaskedValueWhenPresent() {
        when(service.has(KNOWN_KEY)).thenReturn(true);
        when(service.maskedPreview(KNOWN_KEY)).thenReturn(Optional.of("****abcd"));

        ResponseEntity<List<SystemSettingDTO>> re = controller.list();

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(REGISTRY_SIZE, re.getBody().size(),
                "Registry currently exposes " + REGISTRY_SIZE
                        + " settings (openai.api-key + carrier.stamps.api-flavor).");
        SystemSettingDTO dto = re.getBody().stream()
                .filter(d -> KNOWN_KEY.equals(d.getKey()))
                .findFirst().orElseThrow();
        assertEquals(KNOWN_KEY, dto.getKey());
        assertTrue(dto.isHasValue());
        assertEquals("****abcd", dto.getMaskedValue());
        assertNotNull(dto.getDescription(), "Description must be surfaced from the registry.");
        // Delegation: has + maskedPreview called once for the known key.
        verify(service, times(1)).has(KNOWN_KEY);
        verify(service, times(1)).maskedPreview(KNOWN_KEY);
    }

    @Test
    void list_emptyState_whenNoValueStored_returnsHasValueFalse_andEmptyMasked() {
        when(service.has(KNOWN_KEY)).thenReturn(false);

        ResponseEntity<List<SystemSettingDTO>> re = controller.list();

        assertEquals(HttpStatus.OK, re.getStatusCode());
        SystemSettingDTO dto = re.getBody().stream()
                .filter(d -> KNOWN_KEY.equals(d.getKey()))
                .findFirst().orElseThrow();
        assertEquals(false, dto.isHasValue());
        assertEquals("", dto.getMaskedValue(),
                "No value stored → masked empty string (not '(encrypted…)').");
        // Optimization: no masked lookup when value is absent.
        verify(service, times(1)).has(KNOWN_KEY);
        verify(service, never()).maskedPreview(KNOWN_KEY);
    }

    @Test
    void list_encryptedButUndecryptable_showsSentinelMask() {
        // Documented: when has=true but maskedPreview returns Optional.empty
        // (e.g. running without SECRETS_ENCRYPTION_KEY), the DTO surfaces
        // '(encrypted — no decrypt key)' so operators know why the value
        // can't be shown.
        when(service.has(KNOWN_KEY)).thenReturn(true);
        when(service.maskedPreview(KNOWN_KEY)).thenReturn(Optional.empty());

        ResponseEntity<List<SystemSettingDTO>> re = controller.list();

        SystemSettingDTO dto = re.getBody().stream()
                .filter(d -> KNOWN_KEY.equals(d.getKey()))
                .findFirst().orElseThrow();
        assertEquals("(encrypted — no decrypt key)", dto.getMaskedValue());
    }

    // ================ CHOICE-kind setting (stamps api-flavor) ================

    @Test
    void list_flavorChoice_exposesOptionsAndCurrentValue() {
        // CHOICE settings surface the current cleartext value + option list
        // so the FE can render a radio picker. Not a secret — the flavor
        // pick is public config.
        when(service.has(FLAVOR_KEY)).thenReturn(true);
        when(service.getDecrypted(FLAVOR_KEY)).thenReturn(Optional.of("SERA"));

        ResponseEntity<List<SystemSettingDTO>> re = controller.list();

        SystemSettingDTO dto = re.getBody().stream()
                .filter(d -> FLAVOR_KEY.equals(d.getKey()))
                .findFirst().orElseThrow();
        assertEquals(SystemSettingDTO.Kind.CHOICE, dto.getKind(),
                "flavor setting must be CHOICE so the FE renders a radio picker");
        assertEquals(List.of("SWSIM", "SERA"), dto.getOptions());
        assertEquals("SERA", dto.getCurrentValue(),
                "CHOICE settings must expose the current cleartext value (not masked)");
        assertEquals("SWSIM", dto.getDefaultValue(),
                "flavor setting default must remain SWSIM (backward-compat)");
    }

    @Test
    void list_flavorChoice_unsetReturnsDefault() {
        when(service.has(FLAVOR_KEY)).thenReturn(false);

        ResponseEntity<List<SystemSettingDTO>> re = controller.list();

        SystemSettingDTO dto = re.getBody().stream()
                .filter(d -> FLAVOR_KEY.equals(d.getKey()))
                .findFirst().orElseThrow();
        assertEquals("SWSIM", dto.getCurrentValue(),
                "when nothing is stored, currentValue falls back to the default");
    }

    @Test
    void update_flavorChoice_acceptsValidOption() {
        ResponseEntity<SystemSettingDTO> re = controller.update(
                FLAVOR_KEY, Map.of("value", "SERA"), auth("admin"));

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(FLAVOR_KEY, "SERA", "admin");
    }

    @Test
    void update_flavorChoice_rejectsInvalidOption() {
        ResponseEntity<SystemSettingDTO> re = controller.update(
                FLAVOR_KEY, Map.of("value", "PIGEON_POST"), auth("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode(),
                "CHOICE settings must reject values outside the option list");
        verify(service, never()).setEncrypted(eq(FLAVOR_KEY), any(), any());
    }

    @Test
    void update_flavorChoice_rejectsNullValue() {
        ResponseEntity<SystemSettingDTO> re = controller.update(
                FLAVOR_KEY, Map.of("other", "field"), auth("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode(),
                "CHOICE settings require a value — null clear is not supported");
        verify(service, never()).setEncrypted(eq(FLAVOR_KEY), any(), any());
    }

    // ================ PUT /{key} ================

    @Test
    void update_knownKey_delegatesToService_andReturnsFreshDto() {
        when(service.maskedPreview(KNOWN_KEY)).thenReturn(Optional.of("****9999"));
        when(service.has(KNOWN_KEY)).thenReturn(true);

        ResponseEntity<SystemSettingDTO> re = controller.update(
                KNOWN_KEY, Map.of("value", "sk-newkey"), auth("admin-user"));

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(KNOWN_KEY, re.getBody().getKey());
        assertTrue(re.getBody().isHasValue());
        assertEquals("****9999", re.getBody().getMaskedValue());
        // Service called with the actor name from Authentication.
        verify(service, times(1)).setEncrypted(eq(KNOWN_KEY), eq("sk-newkey"), eq("admin-user"));
    }

    @Test
    void update_unknownKey_returns404_withoutTouchingService() {
        // KNOWN_SETTINGS registry-driven; any unregistered key → 404 BEFORE
        // the service is called (so a caller can't sneak a rogue key past
        // the registry).
        ResponseEntity<SystemSettingDTO> re = controller.update(
                "bogus.key", Map.of("value", "hax"), auth("admin-user"));

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
        verify(service, never()).setEncrypted(any(), any(), any());
    }

    @Test
    void update_nullBody_passesNullValueToService() {
        // Documented: a null body (or missing 'value' key) explicitly
        // clears the setting via setEncrypted(key, null, actor).
        when(service.maskedPreview(KNOWN_KEY)).thenReturn(Optional.of(""));
        when(service.has(KNOWN_KEY)).thenReturn(false);

        ResponseEntity<SystemSettingDTO> re = controller.update(
                KNOWN_KEY, null, auth("admin-user"));

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(KNOWN_KEY, null, "admin-user");
    }

    @Test
    void update_nullAuth_recordsUnknownActor() {
        // Defensive: no Authentication (should never happen in prod, but the
        // controller null-guards it) → actor = 'unknown'.
        when(service.maskedPreview(KNOWN_KEY)).thenReturn(Optional.of(""));
        when(service.has(KNOWN_KEY)).thenReturn(true);

        controller.update(KNOWN_KEY, Map.of("value", "x"), null);

        verify(service, times(1)).setEncrypted(KNOWN_KEY, "x", "unknown");
    }

    @Test
    void update_missingValueInBody_passesNullToService() {
        // Body present but missing 'value' key → passes null.
        when(service.maskedPreview(KNOWN_KEY)).thenReturn(Optional.of(""));
        when(service.has(KNOWN_KEY)).thenReturn(false);

        controller.update(KNOWN_KEY, Map.of("other", "field"), auth("admin"));

        verify(service, times(1)).setEncrypted(KNOWN_KEY, null, "admin");
    }

    // ================ Role wiring ================

    @Test
    void classLevel_PreAuthorize_isAdminOnly() {
        PreAuthorize a = SystemSettingsController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(a, "Class-level @PreAuthorize must be present.");
        assertEquals("hasRole('ADMIN')", a.value(),
                "Entire /admin/system-settings family is admin-only.");
    }

    @Test
    void classLevel_RequestMapping_pinnedToV1AdminSystemSettings() {
        assertEquals("/api/v1/admin/system-settings",
                SystemSettingsController.class.getAnnotation(RequestMapping.class).value()[0]);
    }

    @Test
    void methodMappings_pinnedByReflection() throws NoSuchMethodException {
        assertNotNull(SystemSettingsController.class.getMethod("list")
                .getAnnotation(GetMapping.class));
        Method update = SystemSettingsController.class.getMethod(
                "update", String.class, Map.class, Authentication.class);
        assertNotNull(update.getAnnotation(PutMapping.class));
    }

    // ================ Cross-cutting ================

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        SystemSettingService fresh = mock(SystemSettingService.class);
        new SystemSettingsController(fresh);
        verifyNoInteractions(fresh);
    }

    /** Static helper so we can use {@code any(...)} for verify sugar. */
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
