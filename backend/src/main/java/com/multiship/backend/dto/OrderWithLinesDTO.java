package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderWithLinesDTO {
    private Integer orderNo;
    private Integer orderSuffix;
    private String orderStatus;
    private String custNo;
    private String shipName;
    private String shipAttn;
    private String shipAddr1;
    private String phone;
    private String shiptoCity;
    private String shiptoState;
    private String shiptoZip;
    private String shiptoCountryCd;
    private String shipviaCd;
    private String tenantId;
    private BigDecimal weight;
    private String goodsDesc;
    private LocalDate createdDate;
    /** 'Y' when this is a reverse/return label. */
    private String isReturn;
    /** Per-shipment importer/broker override (JSON), or null to use the client profile. */
    private String importerBrokerOverride;
    private List<OrderLineDTO> orderLines;
}
