package com.multiship.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A postal address, used for both request and response of client addresses. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressDTO {
    @Size(max = 255) private String name;
    @Size(max = 255) private String line1;
    @Size(max = 255) private String line2;
    @Size(max = 100) private String city;
    @Size(max = 50) private String state;
    @Size(max = 20) private String zip;
    @Size(max = 10) private String country;
    @Size(max = 50) private String phone;
}
