package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientUpsertRequest {

    /** Unique uppercase code linking orders to this client (immutable after creation). */
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Client code may contain letters, digits, '-' and '_' only")
    private String clientCode;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phone;

    @Valid
    private AddressDTO shipFrom;

    @Valid
    private AddressDTO returnAddress;

    private Boolean returnSameAsShipFrom;
}
