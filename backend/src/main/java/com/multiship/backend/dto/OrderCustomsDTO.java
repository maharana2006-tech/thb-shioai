package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** An order's full customs declaration (response shape). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCustomsDTO {
    private Long id;
    private String orderNo;

    /** Importer of record. */
    private AddressDTO importer;
    private String importerCompany;
    private String importerTaxId;
    private String importerVat;
    private String importerEori;

    private String incoterms;
    private String reasonForExport;
    private String currency;
    private String weightUnit;
    private String notes;

    private List<OrderCustomsItemDTO> items;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
