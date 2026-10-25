package com.multiship.backend.controller;

import com.multiship.backend.service.carrier.ShipperDefaultsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-027 (QA 2026-10-23) — the PUT /shipper-defaults endpoint accepted:
 * <ul>
 *   <li>an all-empty body returning 200 "Ship-from defaults saved";</li>
 *   <li>a name-only body (the other 6 required fields blank), silently
 *       half-configuring the tenant;</li>
 *   <li>country code {@code "USA"} (3 letters) although the field is
 *       documented as ISO-2.</li>
 * </ul>
 *
 * <p>Rules:
 * <ol>
 *   <li>All-empty body → OK (clear every override; legitimate
 *       "revert to platform defaults" flow).</li>
 *   <li>Otherwise every {@link ShipperDefaultsService#REQUIRED_KEYS}
 *       field must be populated — refuse half-configured tenants.</li>
 *   <li>Country code, if present, must be 2 ASCII letters.</li>
 * </ol>
 */
class TenantSettingsControllerValidationTest {

    private static Map<String, String> fullValid() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ShipperDefaultsService.KEY_NAME, "Acme Warehouse");
        m.put(ShipperDefaultsService.KEY_PHONE, "5551234567");
        m.put(ShipperDefaultsService.KEY_ADDRESS_LINE1, "1 Warehouse Way");
        m.put(ShipperDefaultsService.KEY_CITY, "Louisville");
        m.put(ShipperDefaultsService.KEY_STATE, "KY");
        m.put(ShipperDefaultsService.KEY_POSTAL_CODE, "40209");
        m.put(ShipperDefaultsService.KEY_COUNTRY_CODE, "US");
        return m;
    }

    @Test
    void allEmptyBody_isAcceptedAsAClearOverrides() {
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(new HashMap<>()));
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(null));
    }

    @Test
    void allEmptyStringsBody_isAcceptedAsAClearOverrides() {
        Map<String, String> body = new HashMap<>();
        for (String key : ShipperDefaultsService.ALL_KEYS) body.put(key, "");
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(body));
    }

    @Test
    void allRequiredFieldsPopulated_isAccepted() {
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(fullValid()));
    }

    @Test
    void addressLine2CanStayBlankWhenEverythingElseIsPopulated() {
        // addressLine2 is deliberately NOT in REQUIRED_KEYS — many addresses
        // legitimately have none.
        Map<String, String> body = fullValid();
        // No KEY_ADDRESS_LINE2 → treated as blank, still OK.
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(body));
    }

    @Test
    void nameOnlyBody_isRejectedWithTheMissingFieldsListed() {
        // QA's observation 2: Name-only save returned 200.
        Map<String, String> body = new HashMap<>();
        body.put(ShipperDefaultsService.KEY_NAME, "QA-20261023 MKL");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> TenantSettingsController.validateShipperDefaultsBody(body));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Missing"),
                "message must list the missing fields: " + ex.getReason());
        assertTrue(ex.getReason().contains("shipper.phone"), ex.getReason());
        assertTrue(ex.getReason().contains("shipper.city"), ex.getReason());
        assertTrue(ex.getReason().contains("shipper.countryCode"), ex.getReason());
    }

    @Test
    void threeLetterCountryCode_isRejected() {
        // QA's observation 3: "USA" (3 letters) stored verbatim.
        Map<String, String> body = fullValid();
        body.put(ShipperDefaultsService.KEY_COUNTRY_CODE, "USA");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> TenantSettingsController.validateShipperDefaultsBody(body));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("ISO"), ex.getReason());
        assertTrue(ex.getReason().contains("USA"), ex.getReason());
    }

    @Test
    void twoLetterCountryCode_isAccepted() {
        Map<String, String> body = fullValid();
        body.put(ShipperDefaultsService.KEY_COUNTRY_CODE, "US");
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(body));
    }

    @Test
    void twoLetterCountryCodeLowercase_isAccepted() {
        // Case is normalised to upper-case at save time (controller handler).
        // Validator must accept both cases without rejecting.
        Map<String, String> body = fullValid();
        body.put(ShipperDefaultsService.KEY_COUNTRY_CODE, "us");
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(body));
    }

    @Test
    void singleLetterCountryCode_isRejected() {
        Map<String, String> body = fullValid();
        body.put(ShipperDefaultsService.KEY_COUNTRY_CODE, "U");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> TenantSettingsController.validateShipperDefaultsBody(body));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
    }

    @Test
    void digitsInCountryCode_areRejected() {
        Map<String, String> body = fullValid();
        body.put(ShipperDefaultsService.KEY_COUNTRY_CODE, "U1");
        assertThrows(ResponseStatusException.class,
                () -> TenantSettingsController.validateShipperDefaultsBody(body));
    }

    @Test
    void blankCountryCode_isAccepted_whenEveryRequiredIsAlsoBlank() {
        // All-empty body is a clear-overrides flow; the ISO-2 check doesn't
        // fire on a blank value (nothing to validate).
        assertDoesNotThrow(() -> TenantSettingsController.validateShipperDefaultsBody(
                Map.of(ShipperDefaultsService.KEY_COUNTRY_CODE, "")));
    }

    @Test
    void nameAndPhoneButMissingAddress_isRejected() {
        // Half-configured on multiple fronts — any partial set fails.
        Map<String, String> body = new HashMap<>();
        body.put(ShipperDefaultsService.KEY_NAME, "Acme");
        body.put(ShipperDefaultsService.KEY_PHONE, "5551234567");
        assertThrows(ResponseStatusException.class,
                () -> TenantSettingsController.validateShipperDefaultsBody(body));
    }
}
