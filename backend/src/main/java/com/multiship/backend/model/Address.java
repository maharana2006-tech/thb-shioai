package com.multiship.backend.model;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A postal address, reused for a client's Ship From (origin) and Return
 * addresses. Embedded — the owning entity supplies the column names.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Address {
    private String name;
    private String line1;
    private String line2;
    private String city;
    private String state;
    private String zip;
    private String country;
    private String phone;

    /** An address is usable once it has a street and a city. */
    public boolean hasValue() {
        return line1 != null && !line1.isBlank() && city != null && !city.isBlank();
    }
}
