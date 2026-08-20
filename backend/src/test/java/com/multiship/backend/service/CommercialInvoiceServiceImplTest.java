package com.multiship.backend.service;

import com.multiship.backend.model.Address;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.model.OrderCustomsItem;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backend service-coverage backfill for {@link CommercialInvoiceServiceImpl}.
 * The service was the final untested Impl on the service layer — 457 LoC,
 * one public method {@code render(orderNo)}, but a rich decision tree
 * inside for party resolution (3-tier importer fallback), tracking/carrier
 * wiring, incoterms defaults, currency defaults, and PDF assembly.
 *
 * <p>Tests exercise the branches through the single {@code render()} entry
 * point and assert both the low-level contract (a parseable PDF byte
 * stream) and the observable text output (via PDFBox's own
 * {@link PDFTextStripper}) — the strings in the PDF are the surface
 * downstream consumers (brokers, customs) actually read.
 *
 * <p>Pattern matches the other service unit tests in this package
 * ({@link PackingSlipServiceTest}, {@link ClientServiceImplTest}):
 * pure Mockito, constructor injection, no context load.
 */
class CommercialInvoiceServiceImplTest {

    private OrderRepository orderRepo;
    private OrderCustomsRepository orderCustomsRepo;
    private ClientRepository clientRepo;
    private ClientCustomsProfileRepository profileRepo;
    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRefRepo;
    private CommercialInvoiceServiceImpl service;

    @BeforeEach
    void setUp() {
        orderRepo = mock(OrderRepository.class);
        orderCustomsRepo = mock(OrderCustomsRepository.class);
        clientRepo = mock(ClientRepository.class);
        profileRepo = mock(ClientCustomsProfileRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRefRepo = mock(CarrierAccountRefRepository.class);
        service = new CommercialInvoiceServiceImpl(
                orderRepo, orderCustomsRepo, clientRepo, profileRepo, trackingRepo, accountRefRepo);
    }

    // ===== helpers =====

    private Order sampleOrder(Integer orderNo, String tenantId) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setTenantId(tenantId);
        o.setCustNo(tenantId);
        o.setShipName("Jane Recipient");
        o.setShipAttn("Jane Recipient");
        o.setShipAddr1("42 Overseas Ave");
        o.setShiptoCity("London");
        o.setShiptoState("");
        o.setShiptoZip("SW1A 1AA");
        o.setShiptoCountryCd("GB");
        o.setCountryName("United Kingdom");
        o.setShipVia("UPS Worldwide Saver");
        o.setShipviaCd("UPS_WS");
        o.setTrack("1Z_LEGACY_FIELD");
        o.setWeight(new BigDecimal("2.500"));
        o.setPackageCount(1);
        o.setCreatedDate(LocalDate.of(2026, 3, 15));
        return o;
    }

    private OrderCustoms sampleCustoms(Integer orderNo, List<OrderCustomsItem> items) {
        return OrderCustoms.builder()
                .orderNo(String.valueOf(orderNo))
                .items(items)
                .reasonForExport("SALE")
                .build();
    }

    private OrderCustomsItem item(String desc, int qty, BigDecimal unit) {
        return OrderCustomsItem.builder()
                .description(desc)
                .hsCode("6109.10")
                .countryOfOrigin("US")
                .quantity(qty)
                .unitValue(unit)
                .build();
    }

    private Client sampleClient(String code) {
        return Client.builder()
                .id(1L)
                .clientCode(code)
                .name("Acme " + code)
                .shipFrom(Address.builder()
                        .line1("100 Main St").city("Denver").state("CO").zip("80202").country("US")
                        .build())
                .build();
    }

    private String extractText(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ===== error paths =====

    @Test
    void render_missingOrder_throwsIllegalArgumentException() {
        when(orderRepo.findByOrderNo(9999)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.render(9999));
        assertTrue(ex.getMessage().contains("9999"));
    }

    @Test
    void render_missingCustomsData_throwsIllegalStateException_becauseInternalNotIntl() {
        // A commercial invoice is only meaningful for international shipments
        // — those have an OrderCustoms row. Domestic orders have none, and
        // asking for an invoice on them is a caller error.
        when(orderRepo.findByOrderNo(500)).thenReturn(Optional.of(sampleOrder(500, "ACME")));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("500")).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.render(500));
        assertTrue(ex.getMessage().contains("no customs data"));
        assertTrue(ex.getMessage().contains("500"));
    }

    // ===== happy path — verify byte stream + core headings =====

    @Test
    void render_producesParseablePdf_withInvoiceHeaderAndClientName() throws Exception {
        Order order = sampleOrder(100, "ACME");
        OrderCustoms customs = sampleCustoms(100, List.of(item("T-shirt", 2, new BigDecimal("15.00"))));
        when(orderRepo.findByOrderNo(100)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("100")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        byte[] pdf = service.render(100);

        assertNotNull(pdf);
        assertTrue(pdf.length > 500, "a real PDF is at least a couple KB — got " + pdf.length + " bytes");
        String text = extractText(pdf);
        assertTrue(text.contains("COMMERCIAL INVOICE"), "must contain the header title");
        assertTrue(text.contains("Acme ACME"), "must contain the client name in the header block");
        assertTrue(text.contains("Invoice No: 100"), "invoice number pulled from order number");
    }

    // ===== invoice meta line =====

    @Test
    void render_metaLine_includesIncotermsCurrencyReasonAndDate() throws Exception {
        Order order = sampleOrder(101, "ACME");
        OrderCustoms customs = sampleCustoms(101, List.of(item("Widget", 1, BigDecimal.TEN)));
        customs.setIncoterms("DDP");
        customs.setCurrency("EUR");
        customs.setReasonForExport("GIFT");
        when(orderRepo.findByOrderNo(101)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("101")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(101));

        assertTrue(text.contains("Incoterms: DDP"));
        assertTrue(text.contains("Currency: EUR"));
        assertTrue(text.contains("Reason: GIFT"));
        assertTrue(text.contains("15 Mar 2026"), "date formatted dd MMM yyyy");
        // DDP branch: shipper prepays duties.
        assertTrue(text.contains("prepaid by shipper (DDP)"));
    }

    @Test
    void render_incotermsAndCurrencyDefaults_toDapAndUsd_whenAbsent() throws Exception {
        Order order = sampleOrder(102, "ACME");
        // No incoterms, no currency, no reasonForExport on the customs row.
        OrderCustoms customs = OrderCustoms.builder()
                .orderNo("102").items(List.of(item("Item", 1, BigDecimal.ONE))).build();
        when(orderRepo.findByOrderNo(102)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("102")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(102));

        // Defaults hard-coded in renderPdf + dutyTerms.
        assertTrue(text.contains("Incoterms: DAP"), "DAP is the B2C default when unset");
        assertTrue(text.contains("Currency: USD"), "USD is the default when unset");
        assertTrue(text.contains("Reason: SALE"), "SALE is the default when unset");
        assertTrue(text.contains("payable by consignee (DAP)"));
    }

    // ===== importer 3-tier fallback =====

    @Test
    void render_importerTier1_explicitCustomsImporter_wins() throws Exception {
        Order order = sampleOrder(200, "ACME");
        OrderCustoms customs = sampleCustoms(200, List.of(item("Item", 1, BigDecimal.ONE)));
        // Explicit importer captured on the customs row.
        Address importerAddress = Address.builder()
                .name("Sarah Broker").line1("55 Broker St").city("Dover")
                .state("KE").zip("DV1 1AB").country("GB")
                .build();
        customs.setImporterAddress(importerAddress);
        customs.setImporterCompany("Broker Ltd");
        customs.setImporterTaxId("TAX-9876");
        customs.setImporterVat("GB-VAT-42");

        when(orderRepo.findByOrderNo(200)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("200")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(200));

        assertTrue(text.contains("IMPORTER OF RECORD"), "importer heading");
        assertFalse(text.contains("(CONSIGNEE)"), "tier-1 does NOT use the consignee-fallback title");
        assertTrue(text.contains("Sarah Broker"));
        assertTrue(text.contains("55 Broker St"));
        // Tax ID + VAT line rendered.
        assertTrue(text.contains("Tax ID: TAX-9876"));
        assertTrue(text.contains("VAT: GB-VAT-42"));
    }

    @Test
    void render_importerTier2_clientCustomsProfile_whenNoOrderImporter() throws Exception {
        Order order = sampleOrder(201, "ACME");
        // No importer fields on the customs row — tier 1 falls through.
        OrderCustoms customs = sampleCustoms(201, List.of(item("Item", 1, BigDecimal.ONE)));

        // Client has a customs profile for GB.
        ClientCustomsProfile profile = ClientCustomsProfile.builder()
                .clientCode("ACME")
                .importerCountry("GB")
                .importerName("Acme UK Ltd")
                .importerAddress1("10 High St")
                .importerCity("Manchester")
                .importerPostcode("M1 1AB")
                .importerEori("GB123456789")
                .build();

        when(orderRepo.findByOrderNo(201)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("201")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));
        when(profileRepo.findByClientAndCountry("ACME", "GB"))
                .thenReturn(Optional.of(profile));

        String text = extractText(service.render(201));

        assertTrue(text.contains("Acme UK Ltd"), "importer from client profile");
        assertTrue(text.contains("10 High St"));
        // EORI line from profile tier.
        assertTrue(text.contains("EORI: GB123456789"));
    }

    @Test
    void render_importerTier3_consigneeFallback_whenNoOrderImporter_andNoClientProfile() throws Exception {
        Order order = sampleOrder(202, "ACME");
        OrderCustoms customs = sampleCustoms(202, List.of(item("Item", 1, BigDecimal.ONE)));

        when(orderRepo.findByOrderNo(202)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("202")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));
        // No client profile for GB.
        when(profileRepo.findByClientAndCountry("ACME", "GB")).thenReturn(Optional.empty());

        String text = extractText(service.render(202));

        // Tier-3 heading distinguishes itself as CONSIGNEE — a broker checking
        // the invoice should see this is DAP (consignee is importer of record).
        assertTrue(text.contains("IMPORTER OF RECORD (CONSIGNEE)"),
                "tier-3 heading tags itself as consignee fallback");
        assertTrue(text.contains("Jane Recipient"), "consignee name from ship-to");
        assertTrue(text.contains("42 Overseas Ave"));
    }

    // ===== manual-order client resolution via account book =====

    @Test
    void render_manualOrder_resolvesClientCode_viaTrackingAccountBook() throws Exception {
        // Manual + bulk orders persist custNo="MANUAL" and lose the client
        // linkage. The service recovers it via order_tracking.account_number
        // → carrier_account_ref.customer_no.
        Order manual = sampleOrder(300, "MANUAL");
        OrderCustoms customs = sampleCustoms(300, List.of(item("Widget", 1, BigDecimal.TEN)));

        OrderTracking tracking = new OrderTracking();
        tracking.setOrderNo(300);
        tracking.setAccountNumber(" A12345 ");    // whitespace-padded to prove trim()
        tracking.setTrackingNumber("1Z-REAL-999");
        tracking.setShipViaCd("UPS");

        CarrierAccountRef ref = new CarrierAccountRef();
        ref.setAccountNumber("A12345");
        ref.setCustomerNo("ACME");

        when(orderRepo.findByOrderNo(300)).thenReturn(Optional.of(manual));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("300")).thenReturn(Optional.of(customs));
        when(trackingRepo.findByOrderNo(300)).thenReturn(Optional.of(tracking));
        when(accountRefRepo.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("A12345"))
                .thenReturn(Optional.of(ref));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(300));

        // The recovered client name shows in the header block — proof the
        // account-book fallback found it.
        assertTrue(text.contains("Acme ACME"),
                "client name (recovered via account book) must appear in header");
        // Bonus: verify the account-book lookup actually happened.
        verify(accountRefRepo).findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("A12345");
    }

    // ===== tracking / carrier wiring =====

    @Test
    void render_trackingNumber_prefersOrderTrackingRow_overOrderTrackField() throws Exception {
        // Sprint 51 wiring fix — the real tracking number lives on the
        // order_label_tracking row. Verify the service prefers that over the
        // legacy Order.track field (which is null for bulk/manual orders).
        Order order = sampleOrder(400, "ACME");
        order.setTrack("1Z_LEGACY_FIELD");
        OrderCustoms customs = sampleCustoms(400, List.of(item("Item", 1, BigDecimal.ONE)));

        OrderTracking tracking = new OrderTracking();
        tracking.setOrderNo(400);
        tracking.setTrackingNumber("1Z-REAL-TRACK");

        when(orderRepo.findByOrderNo(400)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("400")).thenReturn(Optional.of(customs));
        when(trackingRepo.findByOrderNo(400)).thenReturn(Optional.of(tracking));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(400));

        assertTrue(text.contains("1Z-REAL-TRACK"), "must use the tracking-row number");
        assertFalse(text.contains("1Z_LEGACY_FIELD"),
                "legacy Order.track must NOT appear when the tracking row has a real number");
    }

    @Test
    void render_trackingFallsBackToOrderTrack_whenNoTrackingRow() throws Exception {
        Order order = sampleOrder(401, "ACME");
        order.setTrack("1Z_LEGACY_ONLY");
        OrderCustoms customs = sampleCustoms(401, List.of(item("Item", 1, BigDecimal.ONE)));

        when(orderRepo.findByOrderNo(401)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("401")).thenReturn(Optional.of(customs));
        when(trackingRepo.findByOrderNo(401)).thenReturn(Optional.empty());
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(401));

        assertTrue(text.contains("1Z_LEGACY_ONLY"),
                "with no tracking row, the legacy Order.track field is the fallback");
    }

    // ===== items totals =====

    @Test
    void render_itemsTable_computesRunningTotalCorrectly() throws Exception {
        Order order = sampleOrder(500, "ACME");
        // Two items: 2 × 15.00 = 30.00; 3 × 4.50 = 13.50; total = 43.50
        OrderCustoms customs = sampleCustoms(500, List.of(
                item("T-shirt small",  2, new BigDecimal("15.00")),
                item("T-shirt large",  3, new BigDecimal("4.50"))));
        customs.setCurrency("USD");
        when(orderRepo.findByOrderNo(500)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("500")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(500));

        assertTrue(text.contains("T-shirt small"));
        assertTrue(text.contains("T-shirt large"));
        assertTrue(text.contains("TOTAL (USD)"));
        assertTrue(text.contains("43.50"), "line-item total must add up correctly (2*15 + 3*4.50)");
    }

    @Test
    void render_summaryLine_reportsPackagesTotalQuantityAndGrossWeight() throws Exception {
        Order order = sampleOrder(600, "ACME");
        order.setPackageCount(3);
        order.setWeight(new BigDecimal("2.500"));
        OrderCustoms customs = sampleCustoms(600, List.of(
                item("Item A", 4, new BigDecimal("2.00")),
                item("Item B", 6, new BigDecimal("1.00"))));
        customs.setWeightUnit("KG");
        when(orderRepo.findByOrderNo(600)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("600")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(600));

        assertTrue(text.contains("Packages: 3"));
        assertTrue(text.contains("Total quantity: 10"), "4+6 items");
        assertTrue(text.contains("2.5 KG"), "stripTrailingZeros drops 2.500 → 2.5 + unit");
    }

    // ===== declaration line =====

    @Test
    void render_declarationLine_present_forSigningOff() throws Exception {
        Order order = sampleOrder(700, "ACME");
        OrderCustoms customs = sampleCustoms(700, List.of(item("Item", 1, BigDecimal.ONE)));
        when(orderRepo.findByOrderNo(700)).thenReturn(Optional.of(order));
        when(orderCustomsRepo.findByOrderNoIgnoreCase("700")).thenReturn(Optional.of(customs));
        when(clientRepo.findByClientCodeIgnoreCase("ACME"))
                .thenReturn(Optional.of(sampleClient("ACME")));

        String text = extractText(service.render(700));

        assertTrue(text.contains("I declare the information on this invoice"),
                "customs-required declaration line must be present");
        assertTrue(text.contains("Authorised signature"),
                "signature block label must be present");
        // Sanity: header rendered as the first identifying text.
        assertEquals(text.indexOf("COMMERCIAL INVOICE") >= 0, true);
    }
}
