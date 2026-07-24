package com.multiship.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /clients/{code}/services body. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllowServiceRequest {

    @NotNull
    private Long serviceId;

    /** Attach as this client's default service. First allow auto-defaults. */
    @NotNull
    private Boolean makeDefault;
}
