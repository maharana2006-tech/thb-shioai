package com.multiship.backend.service;

import com.multiship.backend.model.Address;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderLine;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderLineRepository;
import com.multiship.backend.repository.OrderRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Sprint 42 — packing slip renderer coverage.
 *
 * <p>Tests use a mocked {@link LabelTemplateService} to drive the
 * template branches (custom template, platform default, none) and
 * assert on the extracted PDF text via PDFBox's own text stripper.
 */
class PackingSlipServiceTest {

    private OrderRepository orderRepository;
    private OrderLineRepository orderLineRepository;
    private ClientRepository clientRepository;
    private LabelTemplateService labelTemplateService;
    private PackingSlipServiceImpl service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderLineRepository = mock(OrderLineRepository.class);
        clientRepository = mock(ClientRepository.class);
        labelTemplateService = mock(LabelTemplateService.class);
        service = new PackingSlipServiceImpl(orderRepository, orderLineRepository,
                clientRepository, labelTemplateService);
    }

    @Test
    void render_missingOrder_throws() {
        when(orderRepository.findByOrderNo(9999)).thenReturn(Optional.empty());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.render(9999));
        assertTrue(ex.getMessage().contains("9999"));
    }

    @Test
    void render_withNoTemplate_producesValidPdfWithDefaults() throws Exception {
        Order order = buildOrder(100, "ARHDEV");
        when(orderRepository.findByOrderNo(100)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(100)).thenReturn(List.of());
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.empty());

        byte[] pdf = service.render(100);

        assertNotNull(pdf);
        assertTrue(pdf.length > 100, "PDF should have some bytes");
        String text = extractText(pdf);
        assertTrue(text.contains("PACKING SLIP"),
                "Default header should appear; got: " + text);
        assertTrue(text.contains("SHIP TO"));
        assertTrue(text.contains("Jane Doe"));
        assertTrue(text.contains("100"));  // order number
    }

    @Test
    void render_withCustomTemplate_appliesHeaderAndFooter() throws Exception {
        Order order = buildOrder(101, "ARHDEV");
        LabelTemplate tmpl = new LabelTemplate();
        tmpl.setTenantId("ARHDEV");
        tmpl.setTemplateType("PACKING_SLIP");
        tmpl.setHeaderText("Acme Fulfillment");
        tmpl.setPrimaryColor("#663366");
        tmpl.setFooterText("Thanks for your order!\nReturns within 30 days.");
        tmpl.setShowItems(true);

        when(orderRepository.findByOrderNo(101)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(101)).thenReturn(List.of(
                buildLine("SKU-1", "Widget 3000", 2),
                buildLine("SKU-2", "Gadget Deluxe", 1)));
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.of(tmpl));

        byte[] pdf = service.render(101);
        String text = extractText(pdf);

        assertTrue(text.contains("Acme Fulfillment"), text);
        assertTrue(text.contains("Thanks for your order!"), text);
        assertTrue(text.contains("Returns within 30 days."), text);
        assertTrue(text.contains("SKU-1"), text);
        assertTrue(text.contains("Widget 3000"), text);
        assertTrue(text.contains("Gadget Deluxe"), text);
    }

    @Test
    void render_withShowItemsFalse_omitsLineItems() throws Exception {
        Order order = buildOrder(102, "ARHDEV");
        LabelTemplate tmpl = new LabelTemplate();
        tmpl.setHeaderText("Minimal");
        tmpl.setShowItems(false);

        when(orderRepository.findByOrderNo(102)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(102)).thenReturn(List.of(
                buildLine("SKU-HIDDEN", "Should not appear", 1)));
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.of(tmpl));

        byte[] pdf = service.render(102);
        String text = extractText(pdf);

        assertFalse(text.contains("SKU-HIDDEN"),
                "SKU column should be hidden when showItems=false; text=" + text);
        assertFalse(text.contains("Should not appear"), text);
    }

    @Test
    void render_withClientAddress_showsFromBlock() throws Exception {
        Order order = buildOrder(103, "ARHDEV");
        Client client = new Client();
        client.setClientCode("ARHDEV");
        client.setName("Acme");
        Address from = Address.builder()
                .name("Acme Warehouse")
                .line1("500 Depot Way")
                .city("Reno")
                .state("NV")
                .zip("89501")
                .country("US")
                .build();
        client.setShipFrom(from);

        when(orderRepository.findByOrderNo(103)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(103)).thenReturn(List.of());
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV"))
                .thenReturn(Optional.of(client));
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.empty());

        byte[] pdf = service.render(103);
        String text = extractText(pdf);

        assertTrue(text.contains("FROM"), text);
        assertTrue(text.contains("Acme Warehouse"), text);
        assertTrue(text.contains("500 Depot Way"), text);
        assertTrue(text.contains("Reno"), text);
    }

    @Test
    void render_falls_back_to_platform_default_when_tenant_missing() {
        Order order = buildOrder(104, null);
        when(orderRepository.findByOrderNo(104)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(104)).thenReturn(List.of());
        when(labelTemplateService.resolve(any(), any())).thenReturn(Optional.empty());

        byte[] pdf = service.render(104);
        assertNotNull(pdf);
        assertTrue(pdf.length > 100);
        // resolve was called with null tenant (falls through to platform default lookup)
        verify(labelTemplateService).resolve(any(), eq("PACKING_SLIP"));
    }

    @Test
    void render_customFields_appearOnPackingSlip() throws Exception {
        Order order = buildOrder(106, "ARHDEV");
        when(orderRepository.findByOrderNo(106)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(106)).thenReturn(List.of());
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP")).thenReturn(Optional.empty());

        // G5 — wire a mock CustomFieldService with two values on the order.
        CustomFieldService customFieldService = mock(CustomFieldService.class);
        when(customFieldService.loadValues(106)).thenReturn(java.util.Map.of(
                "po_number", "PO-42",
                "dept", "Warehouse-West"));
        com.multiship.backend.model.CustomFieldDefinition po =
                new com.multiship.backend.model.CustomFieldDefinition();
        po.setFieldKey("po_number");
        po.setLabel("PO Number");
        po.setPosition(10);
        com.multiship.backend.model.CustomFieldDefinition dept =
                new com.multiship.backend.model.CustomFieldDefinition();
        dept.setFieldKey("dept");
        dept.setLabel("Department");
        dept.setPosition(20);
        when(customFieldService.listApplicable("ARHDEV")).thenReturn(List.of(po, dept));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "customFieldService", customFieldService);

        byte[] pdf = service.render(106);
        String text = extractText(pdf);

        assertTrue(text.contains("CUSTOM FIELDS"),
                "Section header should appear; got: " + text);
        assertTrue(text.contains("PO Number"), text);
        assertTrue(text.contains("PO-42"), text);
        assertTrue(text.contains("Department"), text);
        assertTrue(text.contains("Warehouse-West"), text);
    }

    @Test
    void render_customFields_orphanedValueFallsBackToRawKey() throws Exception {
        // A value whose definition has been deleted since. Slip must still
        // render it (with the raw key as the label) — we never silently drop.
        Order order = buildOrder(107, "ARHDEV");
        when(orderRepository.findByOrderNo(107)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(107)).thenReturn(List.of());
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP")).thenReturn(Optional.empty());

        CustomFieldService customFieldService = mock(CustomFieldService.class);
        when(customFieldService.loadValues(107)).thenReturn(java.util.Map.of("legacy_ref", "ORPHAN-9"));
        when(customFieldService.listApplicable("ARHDEV")).thenReturn(List.of()); // no definitions
        org.springframework.test.util.ReflectionTestUtils.setField(service, "customFieldService", customFieldService);

        byte[] pdf = service.render(107);
        String text = extractText(pdf);
        assertTrue(text.contains("legacy_ref"), "Orphan key should appear as label; got: " + text);
        assertTrue(text.contains("ORPHAN-9"), text);
    }

    @Test
    void render_customFields_serviceUnwired_behavesLikeBefore() throws Exception {
        // Nothing plumbed → renderInternal skips the custom-fields section
        // entirely; existing packing slip layout unchanged.
        Order order = buildOrder(108, "ARHDEV");
        when(orderRepository.findByOrderNo(108)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(108)).thenReturn(List.of(
                buildLine("SKU-A", "Widget", 1)));
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP")).thenReturn(Optional.empty());

        byte[] pdf = service.render(108);
        String text = extractText(pdf);
        assertFalse(text.contains("CUSTOM FIELDS"),
                "No section header when service is unwired; got: " + text);
        assertTrue(text.contains("SKU-A"), text);
    }

    @Test
    void render_ignoresGarbageLogoBase64_ratherThanFailing() throws Exception {
        Order order = buildOrder(105, "ARHDEV");
        LabelTemplate tmpl = new LabelTemplate();
        tmpl.setHeaderText("With Bad Logo");
        tmpl.setLogoBase64("!!!not-a-real-b64!!!");
        tmpl.setShowItems(true);

        when(orderRepository.findByOrderNo(105)).thenReturn(Optional.of(order));
        when(orderLineRepository.findOrderLinesByOrderNo(105)).thenReturn(List.of());
        when(clientRepository.findByClientCodeIgnoreCase("ARHDEV")).thenReturn(Optional.empty());
        when(labelTemplateService.resolve("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.of(tmpl));

        byte[] pdf = service.render(105);
        assertNotNull(pdf);
        String text = extractText(pdf);
        assertTrue(text.contains("With Bad Logo"), text);
    }

    // ===== helpers =====

    private static Order buildOrder(Integer no, String tenantId) {
        Order o = new Order();
        o.setOrderNo(no);
        o.setTenantId(tenantId);
        o.setCustNo(tenantId);
        o.setShipName("Jane Doe");
        o.setShipAttn("Jane Doe");
        o.setShipAddr1("1 Test Way");
        o.setShiptoCity("Boston");
        o.setShiptoState("MA");
        o.setShiptoZip("02110");
        o.setShiptoCountryCd("US");
        o.setCountryName("United States");
        o.setShipVia("UPS Ground");
        o.setShipviaCd("UPS_GROUND");
        o.setTrack("1Z999AA10123456784");
        o.setCreatedDate(LocalDate.of(2026, 7, 27));
        return o;
    }

    private static OrderLine buildLine(String sku, String desc, int qty) {
        OrderLine ln = new OrderLine();
        ln.setItemNo(sku);
        ln.setItemDescription(desc);
        ln.setQtyShipped(qty);
        return ln;
    }

    private static String extractText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
