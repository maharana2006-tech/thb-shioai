package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 29 — USPS N-call multi-package. SWSIM's CreateIndicium is
 * single-package by design, so multi-package USPS shipments issue N SOAP
 * calls and aggregate the results. Tests:
 *   - buildCreateIndiciumEnvelope emits a package-specific IntegratorTxID
 *     with a -pN suffix so N calls have unique tx IDs.
 *   - aggregateStampsShipmentResults joins tracking numbers, sums costs,
 *     and concatenates raw responses.
 */
class StampsMultiPackageTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        connector = new StampsConnector(new CarrierProperties(), new ObjectMapper());
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS").accountNumber("A99999")
                .serviceType("USPS GA").packageType("Package")
                .weight(new BigDecimal("2")).weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .referenceNumber("RMA-1001")
                .build();
    }

    private static PackageDetailDTO pkg(int seq, String weight) {
        return PackageDetailDTO.builder()
                .sequenceNumber(seq)
                .packageType("Package")
                .weight(new BigDecimal(weight))
                .weightUnit("LB")
                .build();
    }

    /* -------------------------- Envelope: per-package tx IDs -------------------------- */

    @Test
    void singlePackageEnvelopeKeepsBaseIntegratorTxId() {
        ShipmentRequestDTO r = baseRequest();
        r.setReferenceNumber("RMA-1001");
        String xml = connector.buildCreateIndiciumEnvelope(r, "AUTH-XYZ");
        assertTrue(xml.contains("<IntegratorTxID>RMA-1001</IntegratorTxID>"),
                "Single-package baseline should not suffix -pN; got: " + xml);
    }

    @Test
    void multiPackageEnvelopeSuffixesIntegratorTxIdWithPackageIndex() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setReferenceNumber("RMA-1001");
        r.setPackages(List.of(pkg(1, "2"), pkg(2, "3"), pkg(3, "1")));

        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, PackageDetailDTO.class, int.class, int.class, String.class);
        m.setAccessible(true);

        String xml1 = (String) m.invoke(connector, r, r.getPackages().get(0), 1, 3, "AUTH-XYZ");
        String xml2 = (String) m.invoke(connector, r, r.getPackages().get(1), 2, 3, "AUTH-XYZ");
        String xml3 = (String) m.invoke(connector, r, r.getPackages().get(2), 3, 3, "AUTH-XYZ");

        assertTrue(xml1.contains("<IntegratorTxID>RMA-1001-p1</IntegratorTxID>"), xml1);
        assertTrue(xml2.contains("<IntegratorTxID>RMA-1001-p2</IntegratorTxID>"), xml2);
        assertTrue(xml3.contains("<IntegratorTxID>RMA-1001-p3</IntegratorTxID>"), xml3);
    }

    @Test
    void multiPackageEnvelopeUsesPerPackageWeight() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setPackages(List.of(pkg(1, "2"), pkg(2, "5"), pkg(3, "1")));

        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, PackageDetailDTO.class, int.class, int.class, String.class);
        m.setAccessible(true);

        // 2 LB → 32 OZ; 5 LB → 80 OZ; 1 LB → 16 OZ.
        String xml1 = (String) m.invoke(connector, r, r.getPackages().get(0), 1, 3, "AUTH-XYZ");
        String xml2 = (String) m.invoke(connector, r, r.getPackages().get(1), 2, 3, "AUTH-XYZ");
        String xml3 = (String) m.invoke(connector, r, r.getPackages().get(2), 3, 3, "AUTH-XYZ");

        assertTrue(xml1.contains("<WeightOz>32"));
        assertTrue(xml2.contains("<WeightOz>80"));
        assertTrue(xml3.contains("<WeightOz>16"));
    }

    /* -------------------------- Aggregation -------------------------- */

    private static ShipmentResult result(String tracking, String url, String label,
                                          String cost, String raw) {
        return new ShipmentResult(
                tracking, url, label, label,
                cost == null ? null : new BigDecimal(cost),
                LocalDateTime.now(ZoneOffset.UTC),
                raw);
    }

    @Test
    void aggregateEmptyListReturnsBlankResult() {
        ShipmentResult r = connector.aggregateStampsShipmentResults(List.of());
        assertNull(r.trackingNumber());
        assertNull(r.trackingUrl());
        assertNull(r.labelUrl());
    }

    @Test
    void aggregateSinglePackageMasterMatchesTheSoleResult() {
        // Sprint 48 B1: aggregation always wraps into the packages[] shape;
        // master tracking equals the sole piece's tracking. Raw response
        // gets the "pkg 1" marker prefix for debug consistency.
        ShipmentResult sole = result("9400111899223811234567", "https://track/1",
                "https://labels/1", "10.20", "<xml1/>");
        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(sole));
        assertEquals(sole.trackingNumber(), agg.trackingNumber(),
                "single-pkg master tracking mirrors the sole piece");
        assertEquals(1, agg.packages().size(), "packages[] carries the sole piece");
        assertEquals(sole.trackingNumber(), agg.packages().get(0).trackingNumber());
    }

    @Test
    void aggregateMultiPackageMasterIsPiece1_pieceListHoldsAll() {
        // Sprint 48 B1: the old CSV-joined trackingNumber string is gone.
        // Master = piece 1's tracking (matches how customer-facing systems
        // reference "the shipment"); every piece lives in packages[].
        ShipmentResult r1 = result("PKG-1", "https://track/1", "https://labels/1", "10.20", "<xml1/>");
        ShipmentResult r2 = result("PKG-2", "https://track/2", "https://labels/2", "8.50", "<xml2/>");
        ShipmentResult r3 = result("PKG-3", "https://track/3", "https://labels/3", "3.10", "<xml3/>");

        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(r1, r2, r3));
        assertEquals("PKG-1", agg.trackingNumber(), "master = piece 1's tracking");
        assertEquals(3, agg.packages().size(), "packages[] holds all 3 pieces");
        assertEquals("PKG-1", agg.packages().get(0).trackingNumber());
        assertEquals("PKG-2", agg.packages().get(1).trackingNumber());
        assertEquals("PKG-3", agg.packages().get(2).trackingNumber());
    }

    @Test
    void aggregateSumsShippingCostsAcrossPackages() {
        ShipmentResult r1 = result("PKG-1", null, null, "10.20", null);
        ShipmentResult r2 = result("PKG-2", null, null, "8.50", null);
        ShipmentResult r3 = result("PKG-3", null, null, "3.10", null);

        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(r1, r2, r3));
        // 10.20 + 8.50 + 3.10 = 21.80
        assertEquals(0, new BigDecimal("21.80").compareTo(agg.shippingCost()));
    }

    @Test
    void aggregateReturnsFirstPackageTrackingUrlAndLabelUrl() {
        ShipmentResult r1 = result("PKG-1", "https://track/1", "https://labels/1.pdf", "10.20", null);
        ShipmentResult r2 = result("PKG-2", "https://track/2", "https://labels/2.pdf", "8.50", null);

        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(r1, r2));
        assertEquals("https://track/1", agg.trackingUrl(),
                "Multi-package tracking URL should be the first package's — same lane");
        assertEquals("https://labels/1.pdf", agg.labelUrl(),
                "Multi-package label URL is the first package's; per-package labels " +
                        "live in the raw response");
    }

    @Test
    void aggregateConcatenatesRawResponsesWithPackageMarkers() {
        ShipmentResult r1 = result("PKG-1", null, null, null, "<xml1><TrackingNumber>PKG-1</TrackingNumber></xml1>");
        ShipmentResult r2 = result("PKG-2", null, null, null, "<xml2><TrackingNumber>PKG-2</TrackingNumber></xml2>");

        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(r1, r2));
        assertNotNull(agg.rawResponse());
        assertTrue(agg.rawResponse().contains("<!-- pkg 1 -->"));
        assertTrue(agg.rawResponse().contains("<!-- pkg 2 -->"));
        assertTrue(agg.rawResponse().contains("<xml1>"));
        assertTrue(agg.rawResponse().contains("<xml2>"));
    }

    @Test
    void aggregateTolerantOfNullCosts() {
        // Fallback responses have null shippingCost; aggregation should
        // handle it without NPE and return null when EVERY entry is null.
        ShipmentResult r1 = result("PKG-1", null, null, null, null);
        ShipmentResult r2 = result("PKG-2", null, null, null, null);
        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(r1, r2));
        assertNull(agg.shippingCost());
    }

    @Test
    void aggregateTolerantOfBlankTrackingNumbers() {
        // Sprint 48 B1: master = piece 1's tracking (even if some pieces
        // fell back with null tracking); the packages[] list still carries
        // one entry per piece so downstream persistence can see the gap.
        ShipmentResult r1 = result("PKG-1", null, null, "10.20", null);
        ShipmentResult r2 = result(null, null, null, null, null);  // fallback path
        ShipmentResult r3 = result("PKG-3", null, null, "3.10", null);
        ShipmentResult agg = connector.aggregateStampsShipmentResults(List.of(r1, r2, r3));
        assertEquals("PKG-1", agg.trackingNumber(), "master = piece 1's tracking (non-null)");
        assertEquals(3, agg.packages().size(), "one PackageTracking per piece regardless of null tracking");
        assertNull(agg.packages().get(1).trackingNumber(), "piece 2 (fallback) has null tracking preserved");
        assertEquals("PKG-3", agg.packages().get(2).trackingNumber());
    }
}
