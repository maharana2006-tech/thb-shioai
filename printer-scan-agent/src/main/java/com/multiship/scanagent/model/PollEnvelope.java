package com.multiship.scanagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire shape returned by {@code GET /api/v1/printer-scan-agents/poll}.
 * The backend wraps the {@code PollResponse} in the standard
 * {@code ApiResponse} envelope; we only need the {@code data} block.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PollEnvelope(String status, int code, PollData data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PollData(boolean scanRequested) {
    }
}
