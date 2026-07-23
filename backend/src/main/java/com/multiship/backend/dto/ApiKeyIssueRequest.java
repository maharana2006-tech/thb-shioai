package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Admin request to mint a new external API key for a client. */
@Data
public class ApiKeyIssueRequest {

    @NotBlank(message = "A key name is required.")
    private String name;

    @NotBlank(message = "The client code the key ships for is required.")
    private String clientCode;

    /** live | test — defaults to live. */
    private String environment;

    /** Space-separated scopes; defaults to the full external scope set. */
    private String scopes;
}
