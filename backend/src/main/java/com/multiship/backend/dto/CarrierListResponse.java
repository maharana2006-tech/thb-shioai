package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierListResponse {

    private String carrierCode;
    private String carrierName;
    private String description;
    private Boolean active;
    private String environment;
    private String logoUrl;
    private String documentationUrl;
    private String connectionGuide;
}
