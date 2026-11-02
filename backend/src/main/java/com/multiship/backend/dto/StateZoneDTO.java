package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * G2 — admin wire shape for the state_zone table. GET returns the
 * full row set; PUT upsert reads {@code countryCode + stateCode}
 * from the path and {@code zoneId + displayName} from the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateZoneDTO {
    private String countryCode;
    private String stateCode;
    private String zoneId;
    private String displayName;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
