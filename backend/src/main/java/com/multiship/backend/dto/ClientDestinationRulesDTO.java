package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Read shape for GET /clients/{code}/destinations. When the client has no
 * rules at all, the response carries mode=null + empty list — meaning
 * "unrestricted, ship anywhere".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDestinationRulesDTO {
    private String clientCode;
    /** ALLOW | DENY, or null when unrestricted. */
    private String mode;
    /** ISO-3166 alpha-2, sorted A→Z. */
    private List<String> countries;
}
