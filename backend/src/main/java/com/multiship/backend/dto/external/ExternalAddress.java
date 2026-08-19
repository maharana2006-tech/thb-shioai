package com.multiship.backend.dto.external;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** A postal address in the public API contract. */
@Data
public class ExternalAddress {
    // Sprint 51 security fix — the public External API bypasses the SPA's
    // client-side SAFE_TEXT guard entirely, so free-text fields are guarded
    // here. `^[^<>]*$` rejects stored markup; null/blank pass (@Pattern
    // treats null as valid, so optional fields are unaffected). Requires
    // @Valid on the controller method for these to fire.
    private static final String NO_ANGLE_BRACKETS = "^[^<>]*$";
    private static final String NO_ANGLE_MSG = "must not contain < or >";

    @Pattern(regexp = NO_ANGLE_BRACKETS, message = NO_ANGLE_MSG)
    private String name;
    @Pattern(regexp = NO_ANGLE_BRACKETS, message = NO_ANGLE_MSG)
    private String company;
    private String phone;
    private String email;
    @Pattern(regexp = NO_ANGLE_BRACKETS, message = NO_ANGLE_MSG)
    private String addressLine1;
    @Pattern(regexp = NO_ANGLE_BRACKETS, message = NO_ANGLE_MSG)
    private String addressLine2;
    @Pattern(regexp = NO_ANGLE_BRACKETS, message = NO_ANGLE_MSG)
    private String city;
    @Pattern(regexp = NO_ANGLE_BRACKETS, message = NO_ANGLE_MSG)
    private String state;
    private String postalCode;
    /** ISO alpha-2 country code (e.g. US, IN). */
    private String countryCode;
}
