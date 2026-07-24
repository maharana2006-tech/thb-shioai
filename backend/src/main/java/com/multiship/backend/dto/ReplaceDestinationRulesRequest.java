package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PUT /clients/{code}/destinations body. Replaces the client's ship-to rules
 * atomically. All countries share one mode (enforced server-side); an empty
 * countries list clears every rule and returns the client to "ship anywhere".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceDestinationRulesRequest {

    /** ALLOW | DENY. */
    @NotBlank
    private String mode;

    /** ISO-3166 alpha-2 codes. */
    @NotNull
    @Size(max = 249)
    private List<String> countries;
}
