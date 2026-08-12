package com.multiship.backend.service.external;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.dto.external.ExternalParcel;
import com.multiship.backend.dto.external.ExternalRateRequest;
import com.multiship.backend.dto.external.ExternalRateResponse;
import com.multiship.backend.dto.external.ExternalShipmentRequest;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRawCodesRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.ShippingConfigService;
import com.multiship.backend.service.intake.ClientCodeTranslationService;
import com.multiship.backend.service.resolution.ShipmentResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 1 findings #16 + #4 regression guards.
 *
 * <p><b>Tier 1-A (finding #16):</b> The public "create shipment" API used
 * to silently default {@code parcel.weightUnit} to {@code lb} and
 * {@code currency} to {@code USD}. A KG parcel labeled as LB
 * under-declares by ~2.2×; EUR declared as USD is a customs
 * misdeclaration. Both now throw with a machine-readable error code.
 *
 * <p><b>Tier 1-B (finding #4):</b> Proves that {@link ExternalApiService}
 * consults {@code Client.defaultWeightUnit} / {@code defaultCurrency}
 * before the fail-loud, and that the fail-loud still fires when BOTH
 * the caller and the tenant have no default set.
 *
 * <p>Every test wires the same skeletal mocks in {@link #setUp()} so
 * each test only overrides the mock behaviour it cares about. Carrier +
 * service resolution is short-circuited via {@code req.setCarrierCode}
 * so the rate-rule / translation paths stay out of the way.
 */
class ExternalApiServiceTest {

    private static final String CLIENT_CODE = "ACME";

    private ShippingConfigService shippingConfigService;
    private ShippingServiceRepository serviceRepository;
    private CarrierAccountRefRepository carrierAccountRefRepository;
    private ClientRepository clientRepository;
    private OrderRepository orderRepository;
    private OrderTrackingRepository orderTrackingRepository;
    private CarrierService carrierService;
    private CarrierProperties carrierProperties;
    private ShipmentResolutionService resolutionService;
    private ClientCodeTranslationService translationService;
    private OrderRawCodesRepository orderRawCodesRepository;

    private ExternalApiService service;

    @BeforeEach
    void setUp() {
        shippingConfigService = mock(ShippingConfigService.class);
        serviceRepository = mock(ShippingServiceRepository.class);
        carrierAccountRefRepository = mock(CarrierAccountRefRepository.class);
        clientRepository = mock(ClientRepository.class);
        orderRepository = mock(OrderRepository.class);
        orderTrackingRepository = mock(OrderTrackingRepository.class);
        carrierService = mock(CarrierService.class);
        carrierProperties = mock(CarrierProperties.class);
        resolutionService = mock(ShipmentResolutionService.class);
        translationService = mock(ClientCodeTranslationService.class);
        orderRawCodesRepository = mock(OrderRawCodesRepository.class);

        // Translation service — no-op for all three codes.
        // any() (not anyString()) so null rawPackageCode / rawShipvia matches too.
        when(translationService.translateDestCountry(anyString(), any())).thenReturn(Optional.empty());
        when(translationService.translateShipvia(anyString(), any())).thenReturn(Optional.empty());
        when(translationService.translatePackage(anyString(), any())).thenReturn(Optional.empty());

        // Resolution service — no attached warehouse, no allowlists.
        when(resolutionService.resolveWarehouse(anyString(), any())).thenReturn(Optional.empty());
        when(resolutionService.allowedServiceIds(anyString(), any())).thenReturn(Set.of());

        // Shipper defaults — needed for origin-country fallback in
        // createShipment when the request has no shipFrom.
        CarrierProperties.ShipperDefaults shipperDefaults = new CarrierProperties.ShipperDefaults();
        shipperDefaults.setCountryCode("US");
        when(carrierProperties.getShipper()).thenReturn(shipperDefaults);

        // Bill-to: a platform account for UPS so the flow reaches the unit /
        // currency checks (the tests below either supply accountNumber or
        // rely on this fallback).
        CarrierAccountRef platformAcct = new CarrierAccountRef();
        platformAcct.setId(999L);
        platformAcct.setCarrierCode("UPS");
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of(platformAcct));
        when(carrierAccountRefRepository.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(anyString()))
                .thenReturn(List.of());

        // carrierService.generateManualLabel — default to a bare SUCCESS
        // envelope. Individual tests override with argument capture when
        // they want to inspect the ManualShipmentRequest.
        ApiResponse<LabelGenerationResponse> okResp = ApiResponse.<LabelGenerationResponse>builder()
                .status("SUCCESS")
                .code(200)
                .data(LabelGenerationResponse.builder().orderNo(1L).build())
                .build();
        when(carrierService.generateManualLabel(any(), any())).thenReturn(okResp);

        // No carrier connectors needed for these tests — createShipment
        // and rate() don't look them up on the fallback paths we exercise.
        service = new ExternalApiService(
                shippingConfigService,
                serviceRepository,
                carrierAccountRefRepository,
                clientRepository,
                orderRepository,
                orderTrackingRepository,
                carrierService,
                carrierProperties,
                resolutionService,
                translationService,
                orderRawCodesRepository,
                List.of());
    }

    // ─────────────── Tier 1-A finding #16 — fail-loud regression guards ──────

    @Test
    void createShipment_rejectsWeightWithoutUnit() {
        ExternalShipmentRequest req = baseRequest();
        req.getParcel().setWeightUnit(null); // silently defaulted to LB pre-fix

        ExternalApiException ex = assertThrows(ExternalApiException.class,
                () -> service.createShipment(clientBoundKey(), req));

        assertEquals(422, ex.getStatus());
        assertEquals(ErrorCode.UNIT_REQUIRED, ex.getErrorCode());
        // Post Tier 1-B: the fail-loud message names the client and points to
        // the Clients page (see rewritten copy in ExternalApiService), rather
        // than mentioning the removed 'lb' silent default.
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("weightUnit"),
                "Expected message to name the missing field; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("configure the default on the Clients page"),
                "Expected message to direct operator to Clients page; got: " + ex.getMessage());
    }

    @Test
    void createShipment_rejectsDeclaredValueWithoutCurrency() {
        ExternalShipmentRequest req = baseRequest();
        // Supply a valid weightUnit so the earlier guard passes; the
        // currency guard is the target of this test.
        req.getParcel().setWeightUnit("kg");
        req.setDeclaredValue(new BigDecimal("100.00"));
        req.setCurrency(null); // silently defaulted to USD pre-fix

        ExternalApiException ex = assertThrows(ExternalApiException.class,
                () -> service.createShipment(clientBoundKey(), req));

        assertEquals(422, ex.getStatus());
        assertEquals(ErrorCode.CURRENCY_REQUIRED, ex.getErrorCode());
        // Post Tier 1-B: the fail-loud message names the client and points to
        // the Clients page (see rewritten copy in ExternalApiService), rather
        // than mentioning the removed 'USD' silent default.
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("currency"),
                "Expected message to name the missing field; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("configure the default on the Clients page"),
                "Expected message to direct operator to Clients page; got: " + ex.getMessage());
    }

    // ─────────────── Tier 1-B finding #4 — Client-default fallback ──────────

    /**
     * The caller omits weightUnit and the client has
     * {@code defaultWeightUnit="KG"} — no throw, ManualShipmentRequest
     * downstream carries KG.
     */
    @Test
    void createShipment_usesClientDefaultWeightUnitWhenCallerOmits() {
        Client tenant = Client.builder()
                .clientCode(CLIENT_CODE)
                .defaultWeightUnit("KG")
                .defaultCurrency("USD")  // present so currency check passes independent of weight test
                .build();
        when(clientRepository.findByClientCodeIgnoreCase(CLIENT_CODE))
                .thenReturn(Optional.of(tenant));

        ExternalShipmentRequest req = validShipmentRequest();
        req.getParcel().setWeightUnit(null);  // caller omits — should fall to KG

        service.createShipment(clientBoundCaller(), req);

        ArgumentCaptor<ManualShipmentRequest> captor = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(captor.capture(), any());
        assertEquals("KG", captor.getValue().getWeightUnit(),
                "ManualShipmentRequest.weightUnit should fall back to Client.defaultWeightUnit");
    }

    /**
     * The caller omits currency and the client has
     * {@code defaultCurrency="EUR"} — no throw, ManualShipmentRequest
     * downstream carries EUR.
     */
    @Test
    void createShipment_usesClientDefaultCurrencyWhenCallerOmits() {
        Client tenant = Client.builder()
                .clientCode(CLIENT_CODE)
                .defaultWeightUnit("LB")  // present so weight-unit check passes independent of currency test
                .defaultCurrency("EUR")
                .build();
        when(clientRepository.findByClientCodeIgnoreCase(CLIENT_CODE))
                .thenReturn(Optional.of(tenant));

        ExternalShipmentRequest req = validShipmentRequest();
        req.setDeclaredValue(new BigDecimal("100.00"));
        req.setCurrency(null);  // caller omits — should fall to EUR

        service.createShipment(clientBoundCaller(), req);

        ArgumentCaptor<ManualShipmentRequest> captor = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(captor.capture(), any());
        assertEquals("EUR", captor.getValue().getCurrency(),
                "ManualShipmentRequest.currency should fall back to Client.defaultCurrency");
    }

    /**
     * Regression pin for Tier 1-A finding #16 — when BOTH the caller AND
     * the tenant default are missing, the loud UNIT_REQUIRED error still
     * fires. The message must name the client code and reference the
     * Clients page so ops can act on it.
     */
    @Test
    void createShipment_stillFailsLoudWhenBothCallerAndClientDefaultsMissing() {
        // Tenant row exists but ALL defaults are null.
        Client tenant = Client.builder().clientCode(CLIENT_CODE).build();
        when(clientRepository.findByClientCodeIgnoreCase(CLIENT_CODE))
                .thenReturn(Optional.of(tenant));

        ExternalShipmentRequest req = validShipmentRequest();
        req.getParcel().setWeightUnit(null);  // caller omits, tenant has no default either

        ExternalApiException ex = assertThrows(ExternalApiException.class,
                () -> service.createShipment(clientBoundCaller(), req));

        assertEquals(ErrorCode.UNIT_REQUIRED, ex.getErrorCode());
        assertEquals(422, ex.getStatus());
        String msg = ex.getMessage();
        assertTrue(msg.contains(CLIENT_CODE),
                "message must name the client code — was: " + msg);
        assertTrue(msg.contains("configure the default on the Clients page"),
                "message must direct the operator to the Clients page — was: " + msg);
    }

    // ─────────────────────── rate() — Client-default fallback ───────────────

    /**
     * When the client has {@code defaultCurrency="GBP"}, every stub
     * rate option returned by {@link ExternalApiService#rate} carries
     * GBP rather than the pre-Sprint-50 USD hardcode.
     */
    @Test
    void rate_usesClientDefaultCurrencyOnStubResponses() {
        Client tenant = Client.builder()
                .clientCode(CLIENT_CODE)
                .defaultCurrency("GBP")
                .build();
        when(clientRepository.findByClientCodeIgnoreCase(CLIENT_CODE))
                .thenReturn(Optional.of(tenant));

        // Resolve one enabled service for the carrier so the options list
        // is non-empty (otherwise rate() throws VALIDATION_ERROR before we
        // ever assemble the response).
        ShippingService svc = ShippingService.builder()
                .id(1L)
                .carrier("UPS")
                .serviceCode("03")
                .name("UPS Ground")
                .scope("DOMESTIC")
                .originCountry("US")
                .build();
        when(serviceRepository.findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc("UPS"))
                .thenReturn(List.of(svc));

        ExternalRateRequest req = new ExternalRateRequest();
        req.setClientCode(CLIENT_CODE);
        req.setCarrierCode("UPS");
        ExternalAddress to = new ExternalAddress();
        to.setCountryCode("US");
        req.setShipTo(to);

        ExternalRateResponse resp = service.rate(clientBoundCaller(), req);

        assertEquals(1, resp.getOptions().size(),
                "one enabled service should have produced one rate option");
        assertEquals("GBP", resp.getOptions().get(0).getCurrency(),
                "rate option currency should source from Client.defaultCurrency");
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /** Tier 1-A caller — bound to ACME with a narrow scope. */
    private ApiKeyPrincipal clientBoundKey() {
        return new ApiKeyPrincipal(1L, "test-key", "ACME", Set.of("shipments:write"));
    }

    /** Tier 1-B caller — bound to ACME with wildcard scope. */
    private static ApiKeyPrincipal clientBoundCaller() {
        return new ApiKeyPrincipal(1L, "test-key", CLIENT_CODE, Set.of("*"));
    }

    /**
     * Tier 1-A base request — has an explicit shipFrom and a full set of
     * overrides so the request reaches the weight-unit / currency guards
     * without exercising the warehouse-resolver or account-resolution paths.
     */
    private ExternalShipmentRequest baseRequest() {
        ExternalShipmentRequest r = new ExternalShipmentRequest();
        ExternalAddress to = new ExternalAddress();
        to.setName("Jane Doe");
        to.setAddressLine1("1 Test Street");
        to.setCity("Louisville");
        to.setState("KY");
        to.setPostalCode("40209");
        to.setCountryCode("US");
        r.setShipTo(to);

        ExternalAddress from = new ExternalAddress();
        from.setName("Acme Warehouse");
        from.setAddressLine1("1 Warehouse Way");
        from.setCity("Louisville");
        from.setState("KY");
        from.setPostalCode("40209");
        from.setCountryCode("US");
        r.setShipFrom(from);

        // Bypass warehouse resolver / ship-method rule / account resolution
        // by supplying overrides — none of these matter for the weight-unit
        // and currency guards under test.
        r.setCarrierCode("UPS");
        r.setAccountNumber("12345");
        r.setShipMethod("F77");

        ExternalParcel p = new ExternalParcel();
        p.setWeight(new BigDecimal("1.5"));
        r.setParcel(p);
        return r;
    }

    /**
     * A minimally-valid createShipment request — has a shipTo with an
     * address line, a positive parcel weight, a carrier code (so the
     * service-resolution branch is bypassed), and no shipFrom (the
     * client-side legacy shipFrom is also empty because we don't stub
     * one on the tenant row).
     */
    private static ExternalShipmentRequest validShipmentRequest() {
        ExternalShipmentRequest req = new ExternalShipmentRequest();
        req.setClientCode(CLIENT_CODE);
        req.setCarrierCode("UPS");  // bypass shipMethod resolution
        req.setAccountNumber("A1");  // bypass account resolution

        ExternalAddress to = new ExternalAddress();
        to.setName("Jane Doe");
        to.setAddressLine1("1 Main St");
        to.setCity("New York");
        to.setState("NY");
        to.setPostalCode("10001");
        to.setCountryCode("US");
        req.setShipTo(to);

        ExternalParcel parcel = new ExternalParcel();
        parcel.setWeight(new BigDecimal("1.5"));
        parcel.setWeightUnit("LB");  // default; tests that need omission null this out
        req.setParcel(parcel);

        return req;
    }
}
