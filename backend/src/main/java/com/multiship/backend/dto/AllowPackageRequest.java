package com.multiship.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /clients/{code}/packages body. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllowPackageRequest {

    @NotNull
    private Long presetId;

    /** Attach as this client's default package. First allow auto-defaults. */
    @NotNull
    private Boolean makeDefault;
}
