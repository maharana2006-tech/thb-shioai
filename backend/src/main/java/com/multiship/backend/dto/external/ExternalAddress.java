package com.multiship.backend.dto.external;

import lombok.Data;

/** A postal address in the public API contract. */
@Data
public class ExternalAddress {
    private String name;
    private String company;
    private String phone;
    private String email;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    /** ISO alpha-2 country code (e.g. US, IN). */
    private String countryCode;
}
