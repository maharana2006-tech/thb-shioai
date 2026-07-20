package com.multiship.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One customs line item — used for both request and response. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCustomsItemDTO {
    private Long id;
    @Size(max = 200) private String description;
    @Size(max = 12) private String hsCode;
    @Size(max = 2) private String countryOfOrigin;
    private Integer quantity;
    private BigDecimal unitValue;
    private BigDecimal weight;
    @Size(max = 60) private String sku;
}
