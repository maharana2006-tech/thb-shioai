package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.ShipmentSplitter;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import com.multiship.backend.service.carriers.usps.UspsCustomsFormBuilder;
import com.multiship.backend.service.carriers.usps.UspsCustomsLineItemSplitter;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-F3 — pure-Mockito tests for the connector's commodity-cap dispatch.
 *
 * <p>Extends the intl label test's StubConnector pattern:
 * {@link UspsDirectConnector} is subclassed to override
 * {@link UspsDirectConnector#executeIntlLabelPost} so the wire call is a
 * counter + canned response. Tests then verify how the connector
 * dispatches SPLIT / INVOICE_REFERENCE / null strategies + how the
 * aggregate {@link ShipmentResult} carries multiple tracking numbers.
 *
 * <p><b>REGULATORY_REFERENCE.</b> The 30-line cap on the connector is a
 * mirror of USPS Publication 52 §12.4 (CN23/PS-2976-A print constraints);
 * changing the constant requires compliance-officer sign-off.
 */
class UspsDirectConnectorCommodityCapTest {

    private CarrierProperties props;
    private ObjectMapper objectMapper;
    private UspsOAuthTokenCache tokenCache;
    private UspsPaymentAuthCache paymentAuthCache;
    private JdbcTemplate jdbc;
    private UspsCustomsFormBuilder formBuilder;
    private UspsCustomsLineItemSplitter splitter;

    @BeforeEach
    void setUp() {
        props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        objectMapper = new ObjectMapper();
        tokenCache = new UspsOAuthTokenCache(objectMapper);
        paymentAuthCache = mock(UspsPaymentAuthCache.class);
        jdbc = mock(JdbcTemplate.class);
        formBuilder = new UspsCustomsFormBuilder();
        splitter = new UspsCustomsLineItemSplitter(new ShipmentSplitter(), formBuilder);
    }

    /** Wire the JdbcTemplate + payment-auth mocks for happy-path calls. */
    private void seedTenantAndPaymentAuth() {
        doAnswer(invocation -> {
            org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                    invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("acct")).thenReturn("EPS-CAP-1");
            when(rs.getString("crid")).thenReturn("CRID-CAP-1");
            when(rs.getString("mid")).thenReturn("MID-CAP-1");
            return extractor.extractData(rs);
        }).when(jdbc).query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class));
        when(paymentAuthCache.getToken(anyString(), anyString(), anyString(),
                        anyString(), anyString()))
                .thenReturn(java.util.Optional.of("pay-auth-token-42"));
    }

    /** Build a connector subclass that counts label calls + records the
     *  wire body for the FIRST call. Returns a per-call synthetic tracking
     *  number so aggregate assertions can pin the order. */
    private CountingConnector newCountingConnector() {
        CountingConnector c = new CountingConnector(props, objectMapper, tokenCache,
                paymentAuthCache, jdbc);
        c.setCustomsFormBuilder(formBuilder);
        c.setCustomsLineItemSplitter(splitter);
        return c;
    }

    private static class CountingConnector extends UspsDirectConnector {
        final AtomicInteger calls = new AtomicInteger();
        final List<Map<String, Object>> bodies = new ArrayList<>();

        CountingConnector(CarrierProperties p, ObjectMapper om, UspsOAuthTokenCache tc,
                          UspsPaymentAuthCache pac, JdbcTemplate jt) {
            super(p, om, tc, pac, jt);
        }

        @Override
        String executeIntlLabelPost(String url, Map<String, Object> body, String accessToken,
                                     Map<String, String> extraHeaders) {
            int n = calls.incrementAndGet();
            bodies.add(body);
            // Emit distinct tracking numbers per call so aggregate assertions
            // can pin ordering + count.
            return "{\"labelMetadata\":{\"trackingNumber\":\"CP-" + n
                    + "\",\"postage\":\"" + n + ".00\"}}";
        }
    }

    // ================================================================
    // Under-cap — no guard fires, no split
    // ================================================================

    @Test
    void twentyFive_commodities_no_strategy_happy_path_no_split() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(25), null);
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals(1, connector.calls.get(),
                "under-cap → single label call regardless of strategy");
        assertEquals("CP-1", result.trackingNumber());
    }

    @Test
    void thirty_commodities_at_boundary_no_strategy_happy_path() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(30), null);
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals(1, connector.calls.get(),
                "30 == cap fits on one form; guard must not fire");
    }

    // ================================================================
    // Over-cap — no strategy → actionable IAE
    // ================================================================

    @Test
    void thirtyOne_commodities_no_strategy_throws_actionable_iae() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(31), null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("31 commodities"),
                "message must name the actual commodity count; got: " + msg);
        assertTrue(msg.contains("30"),
                "message must name the form line cap; got: " + msg);
        assertTrue(msg.contains("SPLIT"),
                "message must offer SPLIT as remediation; got: " + msg);
        assertTrue(msg.contains("INVOICE_REFERENCE"),
                "message must offer INVOICE_REFERENCE as remediation; got: " + msg);
        assertTrue(msg.contains("2 sub-parcels"),
                "message must preview the SPLIT sub-parcel count; got: " + msg);
        assertTrue(msg.contains("PO-CAP-1"),
                "message must name the order; got: " + msg);
        assertEquals(0, connector.calls.get(),
                "guard fires BEFORE any wire call");
    }

    // ================================================================
    // Over-cap — SPLIT strategy
    // ================================================================

    @Test
    void thirtyOne_commodities_split_strategy_yields_two_label_calls() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(31), CustomsSplitStrategy.SPLIT);
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals(2, connector.calls.get(),
                "31 → 2 sub-parcels → 2 label calls");
        // Master tracking = first sub-shipment's tracking; aggregate
        // packages list carries both.
        assertEquals("CP-1", result.trackingNumber(),
                "master tracking = first sub-shipment's tracking");
        assertEquals(2, result.packages().size(),
                "aggregate packages[] carries one entry per sub-parcel");
        assertEquals("CP-1", result.packages().get(0).trackingNumber());
        assertEquals("CP-2", result.packages().get(1).trackingNumber());
    }

    @Test
    void hundred_commodities_split_strategy_yields_four_label_calls() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(100), CustomsSplitStrategy.SPLIT);
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals(4, connector.calls.get(),
                "100 → ceil(100/30) = 4 sub-parcels → 4 label calls");
        assertEquals(4, result.packages().size(),
                "aggregate carries 4 tracking numbers");
        // Sequence numbers are 1..4 in order.
        assertEquals(1, result.packages().get(0).sequenceNumber());
        assertEquals(4, result.packages().get(3).sequenceNumber());
        // Aggregate shippingCost sums per-piece postages (1 + 2 + 3 + 4).
        assertEquals(0, result.shippingCost().compareTo(new BigDecimal("10.00")),
                "aggregate shippingCost = sum of per-piece postages (1+2+3+4)");
    }

    @Test
    void split_strategy_first_wire_body_carries_first_slice_of_commodities() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(45), CustomsSplitStrategy.SPLIT);
        connector.createShipment(req, "real-token", "SANDBOX");
        assertEquals(2, connector.calls.get(), "45 → 2 sub-parcels");
        // First body: 30-commodity customs form (first slice). Second: 15.
        @SuppressWarnings("unchecked")
        Map<String, Object> firstBody = connector.bodies.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstCustomsForm = (Map<String, Object>) firstBody.get("customsForm");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstCommodities =
                (List<Map<String, Object>>) firstCustomsForm.get("commodities");
        assertEquals(30, firstCommodities.size(),
                "first sub-parcel's customs form carries the first 30 commodities as separate lines");
        // Sanity — the wire body should NOT contain an invoiceReference under SPLIT.
        assertNull(firstCustomsForm.get("invoiceReference"),
                "SPLIT strategy must not stamp invoiceReference on the wire");
    }

    // ================================================================
    // Over-cap — INVOICE_REFERENCE strategy
    // ================================================================

    @Test
    void thirtyOne_commodities_invoice_reference_strategy_one_label_call() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(31), CustomsSplitStrategy.INVOICE_REFERENCE);
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals(1, connector.calls.get(),
                "INVOICE_REFERENCE keeps as ONE label call");
        assertEquals("CP-1", result.trackingNumber());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = connector.bodies.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> customsForm = (Map<String, Object>) body.get("customsForm");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) customsForm.get("commodities");
        assertEquals(1, lines.size(),
                "INVOICE_REFERENCE wire body carries exactly ONE summary commodity line");
        assertTrue(((String) lines.get(0).get("description")).startsWith("See attached invoice"),
                "summary line description must follow the USPS 'See attached invoice' convention");
        assertNotNull(customsForm.get("invoiceReference"),
                "INVOICE_REFERENCE strategy must stamp an invoiceReference on the wire");
        assertTrue(((String) customsForm.get("contentComments")).contains("See attached invoice"),
                "contentComments must explain the physical-attachment obligation to the operator");
    }

    @Test
    void hundred_commodities_invoice_reference_strategy_still_one_label_call() {
        seedTenantAndPaymentAuth();
        CountingConnector connector = newCountingConnector();
        ShipmentRequestDTO req = intlRequest(commodities(100), CustomsSplitStrategy.INVOICE_REFERENCE);
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals(1, connector.calls.get(),
                "even 100 commodities under INVOICE_REFERENCE → one label call");
        assertEquals(1, result.packages().size(),
                "single-package aggregate result under INVOICE_REFERENCE");
    }

    // ================================================================
    // Fixtures
    // ================================================================

    private static List<CustomsCommodityDTO> commodities(int count) {
        List<CustomsCommodityDTO> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(CustomsCommodityDTO.builder()
                    .description("Item " + (i + 1))
                    .quantity(1)
                    .unitValue(new BigDecimal("10.00"))
                    .unitWeight(new BigDecimal("0.5"))
                    .hsCode("610910")
                    .countryOfOrigin("US")
                    .build());
        }
        return out;
    }

    private static ShipmentRequestDTO intlRequest(List<CustomsCommodityDTO> commodities,
                                                    CustomsSplitStrategy strategy) {
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .customsCurrency("USD")
                .incoterms("DAP")
                .commodities(commodities)
                .customsSplitStrategy(strategy)
                .build();
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("ACCT-CAP-1")
                .serviceType("PRIORITY_MAIL_INTERNATIONAL")
                .packageType("USPS_PACKAGE")
                .weight(new BigDecimal("2"))
                .weightUnit("LB")
                .length(new BigDecimal("10"))
                .width(new BigDecimal("6"))
                .height(new BigDecimal("4"))
                .dimUnit("IN")
                .shipperName("ACME Warehouse")
                .shipperCompany("ACME Inc")
                .shipperAddressLine1("1 Warehouse Rd")
                .shipperCity("Denver").shipperState("CO")
                .shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Alice Recipient")
                .recipientAddressLine1("10 Downing St")
                .recipientCity("London")
                .recipientPostalCode("SW1A 2AA")
                .recipientCountryCode("GB")
                .referenceNumber("PO-CAP-1")
                .intl(intl)
                .build();
    }
}
