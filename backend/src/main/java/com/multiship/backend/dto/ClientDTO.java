package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDTO {
    private Long id;
    private String clientCode;
    private String name;
    private String email;
    private String phone;
    private String status;

    /** Origin address printed in the label FROM block. */
    private AddressDTO shipFrom;
    /** Where returns go; mirrors shipFrom when returnSameAsShipFrom. */
    private AddressDTO returnAddress;
    private Boolean returnSameAsShipFrom;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Carrier accounts linked to this client (credentials never included). */
    private List<CarrierAccountRefDTO> carrierAccounts;

    /** How many orders reference this client's code. */
    private Long orderCount;
}
