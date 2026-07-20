package com.multiship.backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class OrderWithTrackingDTO {
    // Order fields
    private Integer orderNo;
    private Integer orderSuffix;
    private String orderStatus;
    private String custNo;
    private String shiptoCity;
    private String shiptoState;
    private String shiptoZip;
    private String shipviaCd;
    private BigDecimal weight;
    private String goodsDesc;
    private LocalDate createdDate;

    // Tracking fields
    private String labelStatus;
    private Boolean isLabelGenerated;
    private String trackingNumber;
    private String trackingUrl;
    private String labelFilePath;
    private String errorMessage;
    private LocalDateTime labelGeneratedAt;

    // Ship via
    private String shipviaDesc;
}