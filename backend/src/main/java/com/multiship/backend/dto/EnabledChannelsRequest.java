package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request payload for
 * {@code PUT /api/v1/tenants/{tenantCode}/settings/enabled-channels}.
 * {@code enabledChannels} must contain at least one of {@code D2C},
 * {@code B2B}; unknown tokens are silently dropped by the service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnabledChannelsRequest {
    private List<String> enabledChannels;
}
