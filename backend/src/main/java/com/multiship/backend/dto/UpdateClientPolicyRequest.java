package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * PUT /clients/{code}/policy body. rateStrategy=FIXED requires
 * fixedServiceId; the service layer 400s with POLICY_FIXED_SERVICE_REQUIRED
 * otherwise.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateClientPolicyRequest {

    /** CHEAPEST | FASTEST | FIXED. */
    @NotBlank
    private String rateStrategy;

    /** Required when rateStrategy=FIXED; must be an allowed service for the client. */
    private Long fixedServiceId;

    private LocalTime cutoffTime;

    @Size(max = 60)
    private String cutoffTz;
}
