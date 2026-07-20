package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional body of POST /orders/{orderNo}/label. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateLabelRequest {

    /**
     * Explicitly chosen carrier account (from the account book). When set it
     * overrides the resolution cascade — the shipper picked manually.
     */
    private Long accountId;
}
