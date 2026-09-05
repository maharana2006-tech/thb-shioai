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

    /** US FTR §30.37 exemption wire code (see IntlShipmentBlockDTO). */
    @Size(max = 32) private String ftrExemption;
    /** AES ITN filed with US Census. */
    @Size(max = 64) private String aesCitation;
    /** Generic non-US export declaration reference (CA B13A / GB CDS / etc). */
    @Size(max = 96) private String exportDeclarationReference;

    // Sprint 52 — DTO-level worst-case cap on customs items (999 mirrors
    // the widest carrier ceiling); per-carrier cap enforced at label time.
    @Valid
    @Size(max = 999, message = "items: at most 999 customs lines per order")
    private List<OrderCustomsItemDTO> items;
}
