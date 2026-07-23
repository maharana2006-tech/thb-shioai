package com.multiship.backend.dto.external;

import lombok.Data;

import java.math.BigDecimal;

/** A commercial-invoice line item (required for international shipments). */
@Data
public class ExternalItem {
    private String description;
    private String sku;
    private String hsCode;
    private String countryOfOrigin;
    private Integer quantity;
    private BigDecimal unitValue;
    private BigDecimal weight;
}
