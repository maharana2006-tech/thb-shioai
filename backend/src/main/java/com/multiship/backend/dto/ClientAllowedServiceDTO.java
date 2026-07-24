package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One entry in the client's allowed-service list. Flattens the underlying
 * ShippingService summary so the settings UI can render each row without a
 * follow-up catalog fetch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedServiceDTO {
    private Long id;
    private String clientCode;
    private Long serviceId;

    // ShippingService summary — flat so the UI has everything in one call.
    private String carrier;
    private String serviceCode;
    private String serviceName;
    private String scope;
    private String originCountry;

    /** True on the row picked when a shipment doesn't name a service. */
    private Boolean isDefault;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
