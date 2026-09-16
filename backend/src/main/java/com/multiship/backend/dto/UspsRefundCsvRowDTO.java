package com.multiship.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PR-D — one row in the PS Form 3533 refund-request CSV the platform
 * admin uploads to the USPS Business Customer Gateway eVS Refund portal
 * (there is no REST endpoint for the refund workflow — batch CSV upload
 * is the only path).
 *
 * <p>Column set is USPS-defined and pinned by
 * {@link com.multiship.backend.service.carriers.usps.UspsRefundCsvExporter#PS3533_HEADER}
 * so the header always matches what the portal accepts. Operator-verify
 * against the current PS 3533 spec before shipping to production —
 * USPS occasionally revises the column set between annual mail-service
 * announcements.
 *
 * <p><b>REGULATORY_REFERENCE</b>: PS Form 3533, "Application for Refund
 * of Fees, Products and Withdrawal of Customer Accounts" —
 * <a href="https://about.usps.com/forms/ps3533.pdf">USPS forms library</a>.
 * The eVS Refund upload variant is described in Publication 205
 * ("Electronic Verification System (eVS) Business and Technical Guide"),
 * Chapter 6 (Refunds).
 */
public record UspsRefundCsvRowDTO(
        /** IMpb tracking number the label was issued under. USPS keys the
         *  refund row off this. Non-null; blank rows fail validation. */
        String trackingNumber,

        /** USPS Mailer ID assigned to the shipper. Sourced from
         *  {@code carrier_account_ref.usps_direct_mid}. USPS uses it to
         *  route the refund to the correct eVS mailing group. */
        String mailerId,

        /** USPS Customer Registration ID. Sourced from
         *  {@code carrier_account_ref.usps_direct_crid}. Identifies the
         *  business entity across USPS services. */
        String customerRegistrationId,

        /** Postage the label was originally rated at (before markup).
         *  Sourced from {@link com.multiship.backend.model.OrderTracking#getCarrierAmount()}.
         *  USPS refunds this amount minus a small processing fee. Null
         *  when the tracking row has no rated amount (rare — usually a
         *  legacy import). */
        BigDecimal originalPostage,

        /** Date the label was generated. Sourced from
         *  {@link com.multiship.backend.model.OrderTracking#getLabelGeneratedAt()}.
         *  USPS requires the refund request to be filed within 30 days
         *  of label creation — see PS 3533 §5. */
        LocalDate labelDate,

        /** Reason code. Always {@code "UNUSED"} for our workflow — we
         *  only file refunds for labels the operator voided before use.
         *  USPS other reason codes (DAMAGED, NO_LONGER_NEEDED) aren't
         *  applicable to the eVS refund upload path. */
        String reasonCode,

        /** Free-form customer reference. Populated with the local order
         *  number so ops can trace a rejected refund back to the source
         *  order without a separate lookup. */
        String customerReference
) {
}
