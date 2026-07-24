package com.multiship.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** PUT /clients/{code}/markup body. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateClientMarkupRequest {

    /** PERCENT | FLAT. */
    @NotBlank
    private String kind;

    /** Non-negative. PERCENT is 12.5 = 12.5%; FLAT is a currency amount. */
    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal value;

    /** ISO-4217 (3 letters, uppercase). */
    @NotBlank
    @Size(min = 3, max = 3)
    @Pattern(regexp = "[A-Za-z]{3}")
    private String currency;
}
