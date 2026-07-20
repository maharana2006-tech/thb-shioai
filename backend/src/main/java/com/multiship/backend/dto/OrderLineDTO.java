package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLineDTO {
    private Long id;
    private Integer lineNo;
    private String itemNo;
    private String itemDescription;
    private Integer qtyShipped;
    private String hsCode;
    private String hsDesc;
    private String description;
    private String countryOfOrigin;
    private BigDecimal customsDeclValue;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
}
