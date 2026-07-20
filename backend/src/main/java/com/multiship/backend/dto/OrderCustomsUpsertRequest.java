package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Create/replace an order's customs declaration. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCustomsUpsertRequest {

    @Valid
    private AddressDTO importer;

    @Size(max = 255) private String importerCompany;
    @Size(max = 50) private String importerTaxId;
    @Size(max = 50) private String importerVat;
    @Size(max = 50) private String importerEori;

    @Size(max = 10) private String incoterms;
    @Size(max = 20) private String reasonForExport;
    @Size(max = 3) private String currency;
    @Size(max = 3) private String weightUnit;
    @Size(max = 500) private String notes;

    @Valid
    private List<OrderCustomsItemDTO> items;
}
