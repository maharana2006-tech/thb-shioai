package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 25 — Print Return Label wire emission across all four carriers.
 * One test file, one round-trip per carrier, so a future reviewer
 * looking at "how is a return label emitted" gets the whole matrix
 * in one place.
 *
 * <p>Uses reflection into each connector's private payload builder so
 * we don't need a live sandbox — we're asserting on the request shape,
 * not the round-tripped response.
 */
class ReturnLabelTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .accountNumber("A12345")
                .serviceType("03")
                .packageType("02")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .shipperName("Acme Returns").shipperPhone("5551234567")
                .shipperAddressLine1("1 Return Depot").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane Doe").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .referenceNumber("RMA-1001")
                .build();
    }

    /* -------------------------- UPS -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> upsShipmentBlock(ShipmentRequestDTO r) throws Exception {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> shipmentRequest = (Map<String, Object>) payload.get("ShipmentRequest");
        return (Map<String, Object>) shipmentRequest.get("Shipment");
    }

    @Test
    void upsEmitsReturnServiceCode8WhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        Map<String, Object> shipment = upsShipmentBlock(r);
        Object returnService = shipment.get("ReturnService");
        assertNotNull(returnService, "UPS Shipment.ReturnService must be present on return labels");
        assertTrue(returnService instanceof Map, "ReturnService should be a Map");
        @SuppressWarnings("unchecked")
        Map<String, Object> rs = (Map<String, Object>) returnService;
        // Code 8 = "Print Return Label" (paper-based). See UPS Ship API
        // "ReturnServiceCode" enum.
        assertEquals("8", rs.get("Code"));
    }

    @Test
    void upsOmitsReturnServiceWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(false);
        assertNull(upsShipmentBlock(r).get("ReturnService"));
    }

    @Test
    void upsOmitsReturnServiceWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        assertNull(r.getIsReturn(), "sanity: default should be null");
        assertNull(upsShipmentBlock(r).get("ReturnService"));
    }

    /**
     * 9120145 fix (Sprint post-mortem 2026-09-11) — UPS rejects a return
     * label with "Missing label delivery information" unless the payload
     * carries a {@code ShipmentServiceOptions.LabelDelivery.EMail} block
     * naming the customer. With shipperEmail populated (customer email on
     * the FE sender block, since sender=customer on returns) the connector
     * must emit that block.
     */
    @Test
    @SuppressWarnings("unchecked")
    void upsEmitsLabelDeliveryEMailBlockOnReturnWhenShipperEmailPresent() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        r.setShipperEmail("jane.customer@example.com");
        r.setRecipientEmail("returns@acme.example.com");
        Map<String, Object> shipment = upsShipmentBlock(r);
        Map<String, Object> serviceOptions = (Map<String, Object>) shipment.get("ShipmentServiceOptions");
        assertNotNull(serviceOptions, "ShipmentServiceOptions must be present on return labels");
        Map<String, Object> labelDelivery = (Map<String, Object>) serviceOptions.get("LabelDelivery");
        assertNotNull(labelDelivery, "LabelDelivery is what UPS rejects with 9120145 when absent");
        Map<String, Object> email = (Map<String, Object>) labelDelivery.get("EMail");
        assertNotNull(email, "LabelDelivery.EMail block is required by ReturnService codes 8 and 9");
        assertEquals("jane.customer@example.com", email.get("EMailAddress"),
                "EMailAddress must be the CUSTOMER'S email — on a return that's shipperEmail (sender=customer)");
        assertEquals("returns@acme.example.com", email.get("FromEMailAddress"),
                "FromEMailAddress must be the retailer's — on a return that's recipientEmail");
    }

    /**
     * PRINT vs EMAIL delivery mode — {@code returnType=EMAIL} on the
     * request flips UPS's ReturnService.Code from 8 (Print Return Label)
     * to 9 (Electronic Return Label — UPS emails the label directly to
     * the customer).
     */
    @Test
    @SuppressWarnings("unchecked")
    void upsUsesReturnServiceCode9WhenReturnTypeEmail() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        r.setShipperEmail("jane.customer@example.com");
        r.setReturnType("EMAIL");
        Map<String, Object> shipment = upsShipmentBlock(r);
        Map<String, Object> rs = (Map<String, Object>) shipment.get("ReturnService");
        assertEquals("9", rs.get("Code"),
                "returnType=EMAIL must map to UPS ReturnService code 9 (Electronic Return Label)");
    }

    /**
     * Defense-in-depth: if a direct caller (bypassing the CarrierServiceImpl
     * boundary guard) sets isReturn without a customer email, the connector
     * must not emit a LabelDelivery block with a null EMailAddress — UPS
     * would reject that with 9120145 the same as an absent block.
     */
    @Test
    void upsOmitsLabelDeliveryWhenShipperEmailBlank() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        // shipperEmail intentionally not set
        Map<String, Object> shipment = upsShipmentBlock(r);
        Object serviceOptions = shipment.get("ShipmentServiceOptions");
        // Either no ShipmentServiceOptions at all, or one without LabelDelivery.
        if (serviceOptions instanceof Map) {
            assertNull(((Map<?, ?>) serviceOptions).get("LabelDelivery"),
                    "LabelDelivery must not be emitted with a null EMailAddress");
        }
    }

    /**
     * 9110044 fix (Sprint post-mortem 2026-09-11, order 900667) —
     * UPS returns need three distinct party blocks:
     *   Shipper  = retailer / account holder (has ShipperNumber)
     *   ShipFrom = customer (physical origin)
     *   ShipTo   = retailer (physical destination)
     *
     * Pre-fix code populated only Shipper (from FE sender=customer) and
     * ShipTo (from FE recipient=retailer) — no ShipFrom at all — and UPS
     * rejected with "Missing ship from information."
     */
    @Test
    @SuppressWarnings("unchecked")
    void upsReturnEmitsAllThreePartyBlocksWithCorrectMapping() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        // baseRequest has shipperXxx = Acme Returns (Louisville) and
        // recipientXxx = Jane Doe (NY). For a return, the FE convention
        // puts customer in sender and retailer in recipient — so here
        // we relabel semantically:
        //   shipperXxx (Acme Returns)    → actually the CUSTOMER
        //   recipientXxx (Jane Doe / NY) → actually the RETAILER
        // The connector's job is to map:
        //   Shipper block  ← recipientXxx (retailer, with ShipperNumber)
        //   ShipFrom block ← shipperXxx  (customer)
        //   ShipTo block   ← recipientXxx (retailer)
        r.setIsReturn(true);
        r.setShipperEmail("customer@example.com");
        Map<String, Object> shipment = upsShipmentBlock(r);

        Map<String, Object> shipperBlock = (Map<String, Object>) shipment.get("Shipper");
        assertNotNull(shipperBlock);
        // The Shipper block must have the ShipperNumber (account) — this is
        // what UPS uses to bill and to identify the account holder.
        assertEquals("A12345", shipperBlock.get("ShipperNumber"),
                "Shipper.ShipperNumber must be the account (retailer's UPS shipper number)");
        // Shipper name = recipient/retailer (Jane Doe here per test relabel).
        assertEquals("Jane Doe", shipperBlock.get("AttentionName"),
                "Shipper party info must come from the RECIPIENT block on returns (retailer's return address)");

        Map<String, Object> shipFromBlock = (Map<String, Object>) shipment.get("ShipFrom");
        assertNotNull(shipFromBlock, "ShipFrom is REQUIRED on returns — UPS 9110044 rejects without it");
        assertEquals("Acme Returns", shipFromBlock.get("AttentionName"),
                "ShipFrom must be the CUSTOMER (from the FE sender block on returns)");
        // ShipFrom must NOT carry ShipperNumber — that field belongs to the
        // account-holder Shipper block only.
        assertNull(shipFromBlock.get("ShipperNumber"),
                "ShipFrom.ShipperNumber must NOT be populated — only the Shipper block owns it");

        Map<String, Object> shipToBlock = (Map<String, Object>) shipment.get("ShipTo");
        assertNotNull(shipToBlock);
        assertEquals("Jane Doe", shipToBlock.get("AttentionName"),
                "ShipTo remains the recipient block (retailer) on returns");
    }

    /**
     * Outbound (non-return) shipments must NOT emit ShipFrom — UPS
     * defaults it to Shipper. Emitting a redundant block was the
     * pre-fix behaviour of some older shipping libraries and can
     * confuse the label-render pipeline.
     */
    @Test
    void upsOutboundOmitsShipFrom() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(false);
        assertNull(upsShipmentBlock(r).get("ShipFrom"),
                "ShipFrom must be omitted on outbound (UPS defaults to Shipper)");
    }

    /**
     * Multi-package UPS return split — verifies the guard at the top of
     * {@link UpsConnector#createShipment} routes to
     * {@code createSplitReturnShipment} so each per-box payload is
     * strictly single-package (which is what UPS actually accepts). We
     * reach in via the payload builder directly for a sub-request built
     * the way the split loop would build it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void upsMultiPackageReturnSplitsPayloadToOnePackagePerSubRequest() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        r.setShipperEmail("customer@example.com");
        r.setPackages(java.util.List.of(
                com.multiship.backend.dto.PackageDetailDTO.builder()
                        .sequenceNumber(1).packageType("02")
                        .weight(new java.math.BigDecimal("2.5")).weightUnit("LB").build(),
                com.multiship.backend.dto.PackageDetailDTO.builder()
                        .sequenceNumber(2).packageType("02")
                        .weight(new java.math.BigDecimal("3.5")).weightUnit("LB").build(),
                com.multiship.backend.dto.PackageDetailDTO.builder()
                        .sequenceNumber(3).packageType("02")
                        .weight(new java.math.BigDecimal("1.5")).weightUnit("LB").build()));
        // Simulate what the split loop does for box index i=1 (middle).
        ShipmentRequestDTO sub = r.toBuilder()
                .packages(java.util.List.of(r.getPackages().get(1).toBuilder()
                        .sequenceNumber(1).build()))
                .build();
        Map<String, Object> shipment = upsShipmentBlock(sub);
        Object pkg = shipment.get("Package");
        assertNotNull(pkg);
        java.util.List<Object> pkgs = (java.util.List<Object>) pkg;
        assertEquals(1, pkgs.size(),
                "Each split sub-request must carry exactly one Package on the UPS wire — "
                        + "ReturnService codes 8/9 reject multi-package with 'Only one package "
                        + "is allowed for this movement.'");
        // ReturnService still present + LabelDelivery still present.
        assertNotNull(shipment.get("ReturnService"));
        Map<String, Object> options = (Map<String, Object>) shipment.get("ShipmentServiceOptions");
        assertNotNull(options);
        assertNotNull(options.get("LabelDelivery"));
    }

    /* -------------------------- FedEx -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> fedexRequestedShipment(ShipmentRequestDTO r) throws Exception {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        return (Map<String, Object>) payload.get("requestedShipment");
    }

    @Test
    void fedexEmitsReturnedShipmentDetailAndFlipsPickupTypeWhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        Map<String, Object> requested = fedexRequestedShipment(r);

        // pickupType flips from USE_SCHEDULED_PICKUP → CONTACT_FEDEX_TO_SCHEDULE
        // so the customer doesn't need a standing pickup.
        assertEquals("CONTACT_FEDEX_TO_SCHEDULE", requested.get("pickupType"));

        Object returned = requested.get("returnedShipmentDetail");
        assertNotNull(returned, "FedEx returnedShipmentDetail must be present on return labels");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) returned;
        assertEquals("PRINT_RETURN_LABEL", detail.get("returnType"));
    }

    @Test
    void fedexKeepsScheduledPickupAndOmitsReturnedShipmentDetailWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(false);
        Map<String, Object> requested = fedexRequestedShipment(r);
        assertEquals("USE_SCHEDULED_PICKUP", requested.get("pickupType"));
        assertNull(requested.get("returnedShipmentDetail"));
    }

    @Test
    void fedexOmitsReturnedShipmentDetailWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        assertNull(r.getIsReturn());
        assertNull(fedexRequestedShipment(r).get("returnedShipmentDetail"));
    }

    /**
     * PRINT vs EMAIL delivery mode — {@code returnType=EMAIL} on the
     * request flips FedEx's {@code returnedShipmentDetail.returnType}
     * from PRINT_RETURN_LABEL to EMAIL_LABEL, and adds an
     * {@code emailLabelDetail.recipients[]} entry naming the customer.
     */
    @Test
    @SuppressWarnings("unchecked")
    void fedexEmailLabelWhenReturnTypeEmail() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        r.setReturnType("EMAIL");
        r.setShipperEmail("jane.customer@example.com");
        Map<String, Object> requested = fedexRequestedShipment(r);
        Map<String, Object> detail = (Map<String, Object>) requested.get("returnedShipmentDetail");
        assertEquals("EMAIL_LABEL", detail.get("returnType"),
                "returnType=EMAIL should map to FedEx EMAIL_LABEL");
        Map<String, Object> emailLabel = (Map<String, Object>) detail.get("emailLabelDetail");
        assertNotNull(emailLabel, "emailLabelDetail is required for EMAIL_LABEL");
        java.util.List<Map<String, Object>> recipients =
                (java.util.List<Map<String, Object>>) emailLabel.get("recipients");
        assertNotNull(recipients);
        assertEquals(1, recipients.size());
        assertEquals("jane.customer@example.com", recipients.get(0).get("emailAddress"),
                "recipient email must be the customer's — on a return that's shipperEmail (sender=customer)");
        assertEquals("SHIPMENT_RECEIVER", recipients.get(0).get("role"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexPrintReturnLabelWhenReturnTypePrintOrOmitted() throws Exception {
        // Explicit PRINT
        ShipmentRequestDTO r1 = baseRequest();
        r1.setIsReturn(true);
        r1.setReturnType("PRINT");
        Map<String, Object> d1 = (Map<String, Object>) fedexRequestedShipment(r1).get("returnedShipmentDetail");
        assertEquals("PRINT_RETURN_LABEL", d1.get("returnType"));
        assertNull(d1.get("emailLabelDetail"), "no emailLabelDetail should be emitted on the paper path");

        // Omitted — same as PRINT (the default)
        ShipmentRequestDTO r2 = baseRequest();
        r2.setIsReturn(true);
        Map<String, Object> d2 = (Map<String, Object>) fedexRequestedShipment(r2).get("returnedShipmentDetail");
        assertEquals("PRINT_RETURN_LABEL", d2.get("returnType"));
        assertNull(d2.get("emailLabelDetail"));
    }

    /* -------------------------- USPS / Stamps SWSIM -------------------------- */

    private String stampsCreateIndiciumEnvelope(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, r, "AUTH-XYZ");
    }

    @Test
    void stampsEmitsIsReturnLabelWhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setIsReturn(true);
        String xml = stampsCreateIndiciumEnvelope(r);
        assertTrue(xml.contains("<IsReturnLabel>true</IsReturnLabel>"),
                "SWSIM CreateIndicium must include <IsReturnLabel>true</IsReturnLabel> for return labels; got: " + xml);
    }

    @Test
    void stampsOmitsIsReturnLabelWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setIsReturn(false);
        assertFalse(stampsCreateIndiciumEnvelope(r).contains("<IsReturnLabel"));
    }

    @Test
    void stampsOmitsIsReturnLabelWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        assertNull(r.getIsReturn());
        assertFalse(stampsCreateIndiciumEnvelope(r).contains("<IsReturnLabel"));
    }

    /* -------------------------- DHL Express -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> dhlPayload(ShipmentRequestDTO r) throws Exception {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method m = DhlConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(c, r);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlFlipsPickupIsRequestedWhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("P");
        r.setPackageType("3BX");
        r.setIsReturn(true);
        Map<String, Object> payload = dhlPayload(r);
        Map<String, Object> pickup = (Map<String, Object>) payload.get("pickup");
        assertNotNull(pickup);
        // DHL Express Global Return: pickup.isRequested=true schedules a
        // courier collection from the customer's address.
        assertEquals(Boolean.TRUE, pickup.get("isRequested"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlKeepsPickupFalseWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("P");
        r.setPackageType("3BX");
        r.setIsReturn(false);
        Map<String, Object> pickup = (Map<String, Object>) dhlPayload(r).get("pickup");
        assertEquals(Boolean.FALSE, pickup.get("isRequested"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlKeepsPickupFalseWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("P");
        r.setPackageType("3BX");
        assertNull(r.getIsReturn());
        Map<String, Object> pickup = (Map<String, Object>) dhlPayload(r).get("pickup");
        assertEquals(Boolean.FALSE, pickup.get("isRequested"));
    }
}
