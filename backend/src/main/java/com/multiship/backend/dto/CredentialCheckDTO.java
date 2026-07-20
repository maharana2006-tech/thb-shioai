package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Outcome of a live credential check against the carrier API. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CredentialCheckDTO {
    private Boolean verified;
    private String message;
    private LocalDateTime checkedAt;
}
