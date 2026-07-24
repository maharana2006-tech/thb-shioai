package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Destination-gate on a {@link com.multiship.backend.model.ClientAllowedService}
 * row: the set of ISO-3166 alpha-2 countries the client may ship to on this
 * service. Empty = unrestricted (any destination).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedServiceDestinationsDTO {
    private String clientCode;
    private Long serviceId;
    /** Sorted A→Z, upper-case ISO-2. */
    private List<String> countries;
}
