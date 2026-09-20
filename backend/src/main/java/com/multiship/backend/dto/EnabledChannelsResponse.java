package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response payload for the enabled-channels endpoints.
 *
 * <p>{@code enabledChannels} is returned normalised (deduped +
 * alphabetically ordered — {@code B2B} before {@code D2C}) so the FE
 * doesn't have to. {@code isConfigured} is false when no row exists yet
 * (force-picking default — the intake gate rejects orders in this
 * state).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnabledChannelsResponse {
    private String tenantCode;
    private List<String> enabledChannels;
    private boolean isConfigured;
}
