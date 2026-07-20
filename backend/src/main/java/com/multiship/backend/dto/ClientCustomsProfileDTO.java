package com.multiship.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** A client's importer+broker profile applied to a set of destination countries. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCustomsProfileDTO {

    private Long id;
    private String clientCode;
    /** Populated on the master list for display; ignored on write. */
    private String clientName;

    /** Destination countries this profile covers (ISO-3166 alpha-2). */
    private List<String> countries;

    // importer
    /** RECEIVER (DAP — consignee is IOR) | BUSINESS (DDP — fixed entity, default). */
    @Size(max = 10) private String importerType;
    /**
     * Required for BUSINESS profiles — enforced in the service (a RECEIVER
     * profile legitimately has no importer identity, so no @NotBlank here).
     */
    @Size(max = 200) private String importerName;
    @Size(max = 200) private String importerContact;
    @Size(max = 10) private String importerCountry;
    @Size(max = 255) private String importerAddress1;
    @Size(max = 255) private String importerAddress2;
    @Size(max = 50) private String importerPhone;
    @Size(max = 120) private String importerCity;
    @Size(max = 120) private String importerState;
    @Size(max = 20) private String importerPostcode;
    @Size(max = 60) private String importerTaxId;
    @Size(max = 20) private String importerTaxIdType;
    @Size(max = 40) private String importerEori;
    @Size(max = 40) private String importerIoss;
    @Size(max = 60) private String importerCompanyReg;
    @Size(max = 40) private String importerIec;
    @Size(max = 40) private String importerGstin;

    // broker
    @Size(max = 200) private String brokerName;
    @Size(max = 200) private String brokerCompany;
    @Size(max = 10) private String brokerCountry;
    @Size(max = 255) private String brokerAddress1;
    @Size(max = 255) private String brokerAddress2;
    @Size(max = 50) private String brokerPhone;
    @Size(max = 120) private String brokerCity;
    @Size(max = 120) private String brokerState;
    @Size(max = 20) private String brokerPostcode;
    @Size(max = 40) private String brokerId;
    @Size(max = 60) private String brokerLicense;

    // shipment defaults
    @Size(max = 10) private String incoterms;
    @Size(max = 20) private String dutiesBillTo;
    @Size(max = 60) private String dutiesAccount;
    @Size(max = 40) private String reasonForExport;
    @Size(max = 3) private String currency;

    // account
    @Size(max = 20) private String accountCarrier;
    @Size(max = 60) private String accountNo;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
