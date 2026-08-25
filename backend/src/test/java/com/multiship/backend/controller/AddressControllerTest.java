package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.dto.external.ExternalAddressValidationResponse;
import com.multiship.backend.service.external.ExternalApiService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill + API-contract audit — AddressController was
 * 0-coverage and both endpoints accepted their @RequestBody without
 * @Valid, so the 7 Bean Validation constraints on ExternalAddress were
 * documentation-only.
 *
 * <p>This suite has two dimensions:
 * <ul>
 *   <li><b>Delegation tests</b> (pure Mockito) — echo semantics on
 *       success and service failure paths.</li>
 *   <li><b>Contract regression guards</b> (reflection) — every
 *       {@code @RequestBody} parameter on this controller must carry
 *       {@code @Valid}. Pins the audit fix against a future refactor
 *       that drops it. Also proves the DTO constraints actually fail a
 *       manual Validator call so we know they weren't no-ops even
 *       before the fix.</li>
 * </ul>
 *
 * <p>Matches the reflection-based approach used elsewhere in the
 * codebase (see CarrierControllerTest / AdminUserControllerTest —
 * "a full @WebMvcTest here would double-cover Spring's own PreAuthorize
 * plumbing at the cost of standing up the entire filter chain").
 */
class AddressControllerTest {

    private ExternalApiService externalApiService;
    private AddressController controller;

    @BeforeEach
    void setUp() {
        externalApiService = mock(ExternalApiService.class);
        controller = new AddressController(externalApiService);
    }

    // ─── delegation ────────────────────────────────────────────────────────

    @Test
    void validateStructural_echoesServiceResult() {
        ExternalAddressValidationResponse result = ExternalAddressValidationResponse.builder()
                .valid(true).issues(java.util.List.of()).build();
        when(externalApiService.validateAddress(any())).thenReturn(result);

        ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> resp =
                controller.validateStructural(new ExternalAddress());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        verify(externalApiService).validateAddress(any());
    }

    @Test
    void validate_deprecatedRoute_stillDelegatesToService() {
        // The @Deprecated /validate route stays mapped until Sprint 53
        // so live SPA callers upgrade without a coordinated deploy.
        // Same delegation as validateStructural.
        ExternalAddressValidationResponse result = ExternalAddressValidationResponse.builder()
                .valid(true).issues(java.util.List.of()).build();
        when(externalApiService.validateAddress(any())).thenReturn(result);

        ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> resp =
                controller.validate(new ExternalAddress());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(externalApiService).validateAddress(any());
    }

    // ─── contract regression guards (audit fix pin) ────────────────────────

    @Test
    void everyRequestBodyParameter_carriesValidAnnotation() {
        // Regression guard: the API-contract audit fix added @Valid to
        // both @RequestBody parameters on this controller so the 7
        // Bean Validation constraints on ExternalAddress get enforced.
        // A future refactor that drops @Valid would silently return
        // to the pre-fix "constraints are documentation only" state —
        // this test breaks the build first.
        long unvalidated = Arrays.stream(AddressController.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .filter(p -> hasAnnotation(p, RequestBody.class))
                .filter(p -> !hasAnnotation(p, Valid.class))
                .count();

        assertEquals(0L, unvalidated,
                "every @RequestBody must also be @Valid — see API-contract audit fix");
    }

    // ─── contract regression guard on the DTO constraint set ───────────────

    @Test
    void externalAddress_hasEnforceableConstraints_whenValidatorRunsManually() {
        // Prove the DTO's constraints aren't no-ops. ExternalAddress uses
        // @Pattern("^[^<>]*$") for XSS defense — null / blank pass through
        // (Bean Validation @Pattern treats null as valid), so the test
        // vector is a value that contains < or > to trigger a violation.
        // Combined with the @Valid regression guard above, this proves
        // Spring's @Valid on the controller will now actually reject
        // XSS-attempt bodies with 400 field-level errors.
        try (ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ExternalAddress xssAttempt = new ExternalAddress();
            xssAttempt.setName("<script>alert(1)</script>");
            int violations = validator.validate(xssAttempt).size();
            assertTrue(violations > 0,
                    "expected @Pattern XSS-guard to reject a name containing <>; got " + violations);
        }
    }

    @Test
    void externalAddress_permitsEmptyValues() {
        // Explicit companion — an empty ExternalAddress produces zero
        // violations by design (@Pattern is null-safe so optional fields
        // aren't forced). Pinned here so a future maintainer who adds
        // @NotBlank knows they're changing the contract.
        try (ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            int violations = validator.validate(new ExternalAddress()).size();
            assertEquals(0, violations,
                    "empty ExternalAddress is intentional — @Pattern-only defensive XSS guard, no @NotBlank");
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static boolean hasAnnotation(Parameter p, Class<? extends Annotation> annotation) {
        // A parameter's annotations include annotations at any position
        // in the parameter's annotation list (order-independent).
        return Arrays.stream(p.getAnnotations()).anyMatch(annotation::isInstance);
    }
}
