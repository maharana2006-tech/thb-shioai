package com.multiship.backend.controller.external.v2;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API-contract audit fix pin — every {@code @RequestBody} parameter on
 * the external v2 controller must carry {@code @Valid}. Pre-fix, four
 * of five body-accepting endpoints (rate-shop, pickups, close-out,
 * landed-cost — plus the address-validate route) accepted their bodies
 * without validation, so all constraints defined on their DTOs
 * (PickupRequestDTO alone has 11) were documentation-only.
 *
 * <p>Companion file to {@link ExternalV2ControllerTest} which covers
 * the delegation semantics. This test is scoped to the API-contract
 * regression guards so it fails loudly if a future refactor drops
 * {@code @Valid} anywhere on this controller. External APIs are
 * exactly where you want validation strictest.
 *
 * <p>Also cross-references the DTOs by running a manual Validator
 * against an empty instance — proves the constraints aren't no-ops.
 */
class ExternalV2ControllerValidAnnotationTest {

    @Test
    void everyRequestBodyParameter_carriesValidAnnotation() {
        long unvalidated = Arrays.stream(ExternalV2Controller.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .filter(p -> hasAnnotation(p, RequestBody.class))
                .filter(p -> !hasAnnotation(p, Valid.class))
                .count();

        assertEquals(0L, unvalidated,
                "every external-v2 @RequestBody must also be @Valid — see API-contract audit fix. "
                        + "External API endpoints are the strictest-validation surface.");
    }

    @Test
    void dtoConstraints_areEnforceable_notNoOps() {
        // Sanity: instantiating each request DTO empty and running a
        // manual Validator must surface violations. If any of these DTOs
        // has ZERO violations on an empty instance, its constraint set
        // is either broken or too permissive to enforce anything useful.
        try (ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            // ExternalAddress uses @Pattern (null-safe XSS guard) — an
            // empty instance produces 0 violations by design. Covered by
            // AddressControllerTest.externalAddress_hasEnforceableConstraints
            // (XSS-vector test) + .externalAddress_permitsEmptyValues.
            // The 4 DTOs below use @NotNull / @NotBlank for required
            // fields so empty-DTO validation IS meaningful.
            List<Class<?>> dtos = List.of(
                    com.multiship.backend.dto.RateShopRequestDTO.class,
                    com.multiship.backend.dto.PickupRequestDTO.class,
                    com.multiship.backend.dto.ManifestRequestDTO.class,
                    com.multiship.backend.dto.LandedCostRequestDTO.class);

            for (Class<?> dtoClass : dtos) {
                Object empty = dtoClass.getDeclaredConstructor().newInstance();
                int violations = validator.validate(empty).size();
                assertTrue(violations > 0,
                        dtoClass.getSimpleName()
                                + ": expected bean-validation to flag missing required fields on an empty DTO; got "
                                + violations
                                + " (constraint set may be broken or the DTO was rewritten without re-checking constraints).");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to instantiate a DTO for validator check", e);
        }
    }

    private static boolean hasAnnotation(Parameter p, Class<? extends Annotation> annotation) {
        return Arrays.stream(p.getAnnotations()).anyMatch(annotation::isInstance);
    }
}
