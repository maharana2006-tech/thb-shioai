package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Admin request to mint a new external API key for a client. */
@Data
public class ApiKeyIssueRequest {

    @NotBlank(message = "A key name is required.")
    private String name;

    /** Optional. Blank (or "*") mints a platform-wide key that ships for ALL clients. */
    private String clientCode;

    /** live | test — defaults to live. */
    private String environment;

    /** Space-separated scopes; defaults to the full external scope set. */
    private String scopes;
}
