package com.multiship.backend.service;

import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateShopServiceImpl}. All connectors mocked; runs
 * on a single-threaded executor so the fan-out is deterministic under
 * assertion. Exercises credential resolution, per-carrier fallback, and
 * the cheapest-first sort.
 */
class RateShopServiceImplTest {

    private CarrierService carrierService;
    private CarrierAccountRefRepository accountRepo;
    private FxRateService fxRateService;
    private CarrierConnector upsConn;
    private CarrierConnector fedexConn;
    private CarrierConnector uspsConn;
    private CarrierConnector dhlConn;
    private java.util.concurrent.ExecutorService executor;
    private RateShopServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        fxRateService = mock(FxRateService.class);
        // Default: no FX conversion succeeds — the comparator falls back to
        // raw amounts, so all Sprint 21 tests behave identically.
        when(fxRateService.convert(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        upsConn = mock(CarrierConnector.class);
        fedexConn = mock(CarrierConnector.class);
        uspsConn = mock(CarrierConnector.class);
        dhlConn = mock(CarrierConnector.class);
        when(carrierService.getCarrierConnector("UPS")).thenReturn(upsConn);
        when(carrierService.getCarrierConnector("FEDEX")).thenReturn(fedexConn);
        when(carrierService.getCarrierConnector("USPS")).thenReturn(uspsConn);
        when(carrierService.getCarrierConnector("DHL")).thenReturn(dhlConn);
        // Single-thread pool so ordering is stable across CI runners.
        executor = Executors.newSingleThreadExecutor();
        // Sprint 39 — no-op cache so existing tests skip the cache path.
        RateCacheService noCache = new RateCacheService() {
            @Override public java.util.Optional<CachedRates> lookup(String c, com.multiship.backend.dto.ShipmentRequestDTO s) { return java.util.Optional.empty(); }
            @Override public void store(String c, com.multiship.backend.dto.ShipmentRequestDTO s, java.util.List<com.multiship.backend.service.carriers.CarrierConnector.RateOption> o) {}
            @Override public int invalidate(String c) { return 0; }
            @Override public CacheStats stats() { return new CacheStats(0, 0, 0, 0, 0); }
        };
        service = new RateShopServiceImpl(carrierService, accountRepo, fxRateService, noCache, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private CarrierAccountRef acct(String carrier) {
        CarrierAccountRef ref = new CarrierAccountRef();
        ref.setCarrierCode(carrier);
        ref.setClientId("cid");
        ref.setClientSecret("csec");
        ref.setAccountNumber("A" + carrier);
        ref.setEnvironment("SANDBOX");
        return ref;
    }

    private CarrierConnector.RateOption option(String carrier, String svc, String amount) {
        return new CarrierConnector.RateOption(carrier, svc, carrier + " " + svc,
                new BigDecimal(amount), "USD", null, 2);
    }

    private ShipmentRequestDTO shipment() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS").serviceType("03").packageType("02")
                .weight(new BigDecimal("2")).weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .build();
    }

    private RateShopRequestDTO req() {
        return RateShopRequestDTO.builder().shipment(shipment()).build();
    }

    /* -------------------------- Request validation -------------------------- */

    @Test
    void rejectsNullRequest() {
        var response = service.rateShop(null);
        assertEquals("error", response.getStatus());
        assertEquals(400, response.getCode());
    }

    @Test
    void rejectsRequestWithoutShipment() {
        var response = service.rateShop(RateShopRequestDTO.builder().build());
        assertEquals("error", response.getStatus());
        assertEquals(400, response.getCode());
    }

    @Test
    void rejectsRequestWithEmptyCarrierWhitelist() {
        var request = RateShopRequestDTO.builder()
                .shipment(shipment())
                .carriers(List.of("BOGUS", "MADE_UP"))
                .build();
        var response = service.rateShop(request);
        assertEquals("error", response.getStatus());
        assertEquals(400, response.getCode());
    }

    /* -------------------------- Fan-out orchestration -------------------------- */

    @Test
    void mergesOptionsFromEveryCarrierSortedCheapestFirst() {
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok-ups");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok-fedex");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok-usps");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok-dhl");
        when(upsConn.getRates(any(), eq("tok-ups"), any())).thenReturn(List.of(
                option("UPS", "03", "12.50"),
                option("UPS", "01", "42.75")));
        when(fedexConn.getRates(any(), eq("tok-fedex"), any())).thenReturn(List.of(
                option("FEDEX", "FEDEX_GROUND", "11.20")));
        when(uspsConn.getRates(any(), eq("tok-usps"), any())).thenReturn(List.of(
                option("USPS", "USPS GA", "7.85")));
        when(dhlConn.getRates(any(), eq("tok-dhl"), any())).thenReturn(List.of(
                option("DHL", "P", "35.00")));

        var response = service.rateShop(req());
        assertEquals("success", response.getStatus());
        RateShopResponseDTO body = response.getData();
        assertNotNull(body);
        // 5 total options, cheapest first
        assertEquals(5, body.getOptions().size());
        assertEquals("USPS", body.getOptions().get(0).getCarrierCode());
        assertEquals(0, new BigDecimal("7.85").compareTo(body.getOptions().get(0).getTotalAmount()));
        // 4 carrier statuses, all LIVE
        assertEquals(4, body.getCarrierResults().size());
        assertTrue(body.getCarrierResults().stream()
                .allMatch(s -> "LIVE".equals(s.getSource())));
    }

    @Test
    void reportsStubWhenNoCredentialsAreConfigured() {
        // UPS has creds, others don't.
        when(accountRepo.findPlatformAccountsByCarrier("UPS")).thenReturn(List.of(acct("UPS")));
        when(accountRepo.findPlatformAccountsByCarrier("FEDEX")).thenReturn(List.of());
        when(accountRepo.findPlatformAccountsByCarrier("USPS")).thenReturn(List.of());
        when(accountRepo.findPlatformAccountsByCarrier("DHL")).thenReturn(List.of());
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), eq("tok"), any())).thenReturn(List.of(option("UPS", "03", "12.50")));

        var response = service.rateShop(req());
        RateShopResponseDTO body = response.getData();
        assertEquals(1, body.getOptions().size());
        // 3 STUB entries because no credentials — UPS should be LIVE.
        long stubCount = body.getCarrierResults().stream()
                .filter(s -> "STUB".equals(s.getSource())).count();
        assertEquals(3L, stubCount);
        assertTrue(body.getCarrierResults().stream().anyMatch(s ->
                "UPS".equals(s.getCarrierCode()) && "LIVE".equals(s.getSource())));
    }

    @Test
    void catchesConnectorExceptionsPerCarrier() {
        // FedEx throws during getRates; the others succeed.
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of(option("UPS", "03", "12.50")));
        when(fedexConn.getRates(any(), anyString(), any()))
                .thenThrow(new RuntimeException("FedEx API down"));
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of(option("DHL", "P", "35.00")));

        var response = service.rateShop(req());
        RateShopResponseDTO body = response.getData();
        // Only UPS + DHL contributed options — FedEx exception dropped, USPS empty.
        assertEquals(2, body.getOptions().size());
        // One ERROR status for FedEx.
        assertEquals(1, body.getCarrierResults().stream()
                .filter(s -> "ERROR".equals(s.getSource())).count());
        // FedEx status message should mention the failure.
        String fedexMsg = body.getCarrierResults().stream()
                .filter(s -> "FEDEX".equals(s.getCarrierCode())).findFirst()
                .orElseThrow().getMessage();
        assertTrue(fedexMsg.contains("FedEx API down"), "Expected fedex error message; got: " + fedexMsg);
    }

    @Test
    void tokenFailureIsReportedAsError() {
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("401 unauthorized"));
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of());

        var response = service.rateShop(req());
        RateShopResponseDTO body = response.getData();
        String upsStatus = body.getCarrierResults().stream()
                .filter(s -> "UPS".equals(s.getCarrierCode())).findFirst()
                .orElseThrow().getSource();
        assertEquals("ERROR", upsStatus);
    }

    @Test
    void emptyOptionsAcrossAllCarriersReturnsSuccessWithEmptyList() {
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of());

        var response = service.rateShop(req());
        assertEquals("success", response.getStatus());
        assertTrue(response.getData().getOptions().isEmpty());
        assertEquals(4, response.getData().getCarrierResults().size());
        // Empty → STUB source
        assertTrue(response.getData().getCarrierResults().stream()
                .allMatch(s -> "STUB".equals(s.getSource())));
    }

    /* -------------------------- Credential resolution -------------------------- */

    @Test
    void customerAccountsBeatPlatformAccounts() {
        CarrierAccountRef customer = acct("UPS");
        customer.setClientId("customer-cid");
        CarrierAccountRef platform = acct("UPS");
        platform.setClientId("platform-cid");
        // resolveAccount now walks the full owned-account list (default-flag
        // first, then sole account) instead of only the default-flag query.
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("C001"))
                .thenReturn(List.of(customer));
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(platform));

        CarrierAccountRef resolved = service.resolveAccount("UPS", "C001");
        assertNotNull(resolved);
        assertEquals("customer-cid", resolved.getClientId());
    }

    @Test
    void resolveAccountFallsBackToPlatformWhenCustomerHasNone() {
        when(accountRepo.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("C001"))
                .thenReturn(List.of());
        CarrierAccountRef platform = acct("UPS");
        when(accountRepo.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of(platform));

        CarrierAccountRef resolved = service.resolveAccount("UPS", "C001");
        assertEquals(platform, resolved);
    }

    @Test
    void resolveAccountReturnsNullWhenNothingConfigured() {
        when(accountRepo.findPlatformAccountsByCarrier(anyString())).thenReturn(List.of());
        assertNull(service.resolveAccount("UPS", null));
    }

    /* -------------------------- Whitelist + sort -------------------------- */

    @Test
    void carriersWhitelistLimitsFanOut() {
        var request = RateShopRequestDTO.builder()
                .shipment(shipment())
                .carriers(List.of("UPS", "FEDEX"))
                .build();
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of(option("UPS", "03", "12.50")));
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of(option("FEDEX", "FEDEX_GROUND", "11.20")));

        var response = service.rateShop(request);
        // Only two carriers, no USPS or DHL status entries.
        assertEquals(2, response.getData().getCarrierResults().size());
        assertTrue(response.getData().getCarrierResults().stream()
                .noneMatch(s -> "USPS".equals(s.getCarrierCode())));
    }

    @Test
    void nullPriceOptionsSinkToBottomOfSort() {
        var withPrice = RateShopResponseDTO.RateOptionDTO.builder()
                .carrierCode("UPS").serviceCode("03").totalAmount(new BigDecimal("12.50")).build();
        var withoutPrice = RateShopResponseDTO.RateOptionDTO.builder()
                .carrierCode("UPS").serviceCode("01").totalAmount(null).build();
        assertTrue(service.compareRateOptions(withPrice, withoutPrice) < 0,
                "priced option should sort before unpriced");
        assertTrue(service.compareRateOptions(withoutPrice, withPrice) > 0);
        assertEquals(0, service.compareRateOptions(withoutPrice, withoutPrice));
    }

    @Test
    void resolveCarriersDefaultsToAllWhenWhitelistNull() {
        assertEquals(RateShopServiceImpl.CARRIER_ORDER, service.resolveCarriers(null));
        assertEquals(RateShopServiceImpl.CARRIER_ORDER, service.resolveCarriers(List.of()));
    }

    @Test
    void resolveCarriersDropsUnknownAndDeduplicates() {
        assertEquals(
                List.of("UPS", "FEDEX"),
                service.resolveCarriers(List.of("ups", "FEDEX", "UPS", "MYSTERY_CARRIER")));
    }

    /* -------------------------- FX-normalised sort -------------------------- */

    @Test
    void mixedCurrencyOptionsSortByUsdNormalisedValue() {
        // EUR 20 @ 1.10 USD/EUR = 22 USD → beats USD 25, loses to USD 20.
        when(fxRateService.convert(new BigDecimal("20"), "EUR", "USD"))
                .thenReturn(Optional.of(new BigDecimal("22.00")));
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of(
                new CarrierConnector.RateOption("UPS", "07", "UPS Express",
                        new BigDecimal("25"), "USD", null, 3)));
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of(
                new CarrierConnector.RateOption("FEDEX", "INTL_ECON", "FedEx Intl Econ",
                        new BigDecimal("20"), "EUR", null, 5)));
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of(
                new CarrierConnector.RateOption("USPS", "USPS PMI", "USPS PMI",
                        new BigDecimal("20"), "USD", null, 4)));
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of());

        var response = service.rateShop(req());
        var options = response.getData().getOptions();
        assertEquals(3, options.size());
        // USPS 20 USD → cheapest, FEDEX 20 EUR (22 USD) → middle, UPS 25 USD → last
        assertEquals("USPS", options.get(0).getCarrierCode());
        assertEquals("FEDEX", options.get(1).getCarrierCode());
        assertEquals("UPS", options.get(2).getCarrierCode());
        // Native amount + currency NOT rewritten — we only normalise for
        // the sort key; the response carries the carrier's native quote.
        assertEquals("EUR", options.get(1).getCurrency());
        assertEquals(0, new BigDecimal("20").compareTo(options.get(1).getTotalAmount()));
    }

    @Test
    void unavailableFxRateFallsBackToRawAmountForSort() {
        // No FX rate is set on the mock (default empty). The comparator
        // should fall through to raw amounts — so USD 15 sorts before
        // EUR 20 even though EUR is nominally more valuable per unit.
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of(
                new CarrierConnector.RateOption("UPS", "03", "UPS Ground",
                        new BigDecimal("15"), "USD", null, 3)));
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of(
                new CarrierConnector.RateOption("FEDEX", "INTL", "FedEx Intl",
                        new BigDecimal("20"), "EUR", null, 5)));
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of());

        var response = service.rateShop(req());
        var options = response.getData().getOptions();
        assertEquals(2, options.size());
        assertEquals("UPS", options.get(0).getCarrierCode());
        assertEquals("FEDEX", options.get(1).getCarrierCode());
    }

    @Test
    void usdOptionsSkipFxServiceEntirely() {
        // Every option in USD → FX service should never be called.
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of(option("UPS", "03", "12.50")));
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of());

        service.rateShop(req());
        org.mockito.Mockito.verify(fxRateService, org.mockito.Mockito.never())
                .convert(any(), anyString(), anyString());
    }

    /* -------------------------- G4 — routing preview -------------------------- */

    @Test
    void routingOutcomeKeepWhenNoRuleMatches() {
        RoutingRuleService rules = mock(RoutingRuleService.class);
        com.multiship.backend.repository.ShippingServiceRepository svcRepo =
                mock(com.multiship.backend.repository.ShippingServiceRepository.class);
        // Service catalog: UPS/03 → id 100
        when(svcRepo.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase("UPS", "03"))
                .thenReturn(Optional.of(svc(100L, "UPS", "03")));
        // Rule engine returns NO_MATCH
        when(rules.evaluate(anyString(), any()))
                .thenReturn(com.multiship.backend.dto.RoutingEvaluationResult.builder()
                        .status("NO_MATCH").build());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "routingRuleService", rules);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shippingServiceRepository", svcRepo);

        stubUpsOnly("UPS", "03", "12.50");
        var response = service.rateShop(RateShopRequestDTO.builder()
                .shipment(shipment()).customerNo("ACME").build());
        var opt = response.getData().getOptions().get(0);
        assertEquals(100L, opt.getServiceId());
        assertEquals("KEEP", opt.getRoutingOutcome());
        assertNull(opt.getRoutingRuleName());
    }

    @Test
    void routingOutcomeRerouteRewritesTargetCarrierAndService() {
        RoutingRuleService rules = mock(RoutingRuleService.class);
        com.multiship.backend.repository.ShippingServiceRepository svcRepo =
                mock(com.multiship.backend.repository.ShippingServiceRepository.class);
        when(svcRepo.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase("UPS", "03"))
                .thenReturn(Optional.of(svc(100L, "UPS", "03")));
        // Rule reroutes to serviceId 200 (DHL/P)
        when(svcRepo.findById(200L)).thenReturn(Optional.of(svc(200L, "DHL", "P")));
        when(rules.evaluate(anyString(), any()))
                .thenReturn(com.multiship.backend.dto.RoutingEvaluationResult.builder()
                        .status("MATCH")
                        .matchedRuleName("EU→DHL")
                        .actionType(com.multiship.backend.model.RoutingRule.ActionType.REROUTE)
                        .targetServiceId(200L)
                        .targetCarrier("DHL")
                        .build());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "routingRuleService", rules);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shippingServiceRepository", svcRepo);

        stubUpsOnly("UPS", "03", "12.50");
        var response = service.rateShop(RateShopRequestDTO.builder()
                .shipment(shipment()).customerNo("ACME").build());
        var opt = response.getData().getOptions().get(0);
        assertEquals("REROUTE", opt.getRoutingOutcome());
        assertEquals("EU→DHL", opt.getRoutingRuleName());
        assertEquals("DHL", opt.getRoutingTargetCarrier());
        assertEquals("P", opt.getRoutingTargetServiceCode());
    }

    @Test
    void routingOutcomeBlockCarriesReason() {
        RoutingRuleService rules = mock(RoutingRuleService.class);
        com.multiship.backend.repository.ShippingServiceRepository svcRepo =
                mock(com.multiship.backend.repository.ShippingServiceRepository.class);
        when(svcRepo.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase("UPS", "03"))
                .thenReturn(Optional.of(svc(100L, "UPS", "03")));
        when(rules.evaluate(anyString(), any()))
                .thenReturn(com.multiship.backend.dto.RoutingEvaluationResult.builder()
                        .status("MATCH")
                        .matchedRuleName("Heavy weight")
                        .actionType(com.multiship.backend.model.RoutingRule.ActionType.BLOCK)
                        .blockReason("Weight over carrier max")
                        .build());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "routingRuleService", rules);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shippingServiceRepository", svcRepo);

        stubUpsOnly("UPS", "03", "12.50");
        var response = service.rateShop(RateShopRequestDTO.builder()
                .shipment(shipment()).customerNo("ACME").build());
        var opt = response.getData().getOptions().get(0);
        assertEquals("BLOCK", opt.getRoutingOutcome());
        assertEquals("Weight over carrier max", opt.getRoutingBlockReason());
    }

    @Test
    void routingPreviewSkippedWhenCustomerNoBlank() {
        RoutingRuleService rules = mock(RoutingRuleService.class);
        com.multiship.backend.repository.ShippingServiceRepository svcRepo =
                mock(com.multiship.backend.repository.ShippingServiceRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "routingRuleService", rules);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shippingServiceRepository", svcRepo);

        stubUpsOnly("UPS", "03", "12.50");
        // No customerNo → routing preview never called; option carries no tag.
        var response = service.rateShop(req());
        var opt = response.getData().getOptions().get(0);
        assertNull(opt.getRoutingOutcome());
        org.mockito.Mockito.verify(rules, org.mockito.Mockito.never())
                .evaluate(anyString(), any());
    }

    @Test
    void routingPreviewSkippedWhenServiceIdUnresolvable() {
        // The catalog doesn't have UPS/UNKNOWN → serviceId stays null →
        // routing engine is never called.
        RoutingRuleService rules = mock(RoutingRuleService.class);
        com.multiship.backend.repository.ShippingServiceRepository svcRepo =
                mock(com.multiship.backend.repository.ShippingServiceRepository.class);
        when(svcRepo.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase("UPS", "UNKNOWN"))
                .thenReturn(Optional.empty());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "routingRuleService", rules);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shippingServiceRepository", svcRepo);

        stubUpsOnly("UPS", "UNKNOWN", "12.50");
        var response = service.rateShop(RateShopRequestDTO.builder()
                .shipment(shipment()).customerNo("ACME").build());
        var opt = response.getData().getOptions().get(0);
        assertNull(opt.getServiceId());
        assertNull(opt.getRoutingOutcome());
        org.mockito.Mockito.verify(rules, org.mockito.Mockito.never())
                .evaluate(anyString(), any());
    }

    private void stubUpsOnly(String carrier, String serviceCode, String amount) {
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(acct("_")));
        when(upsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(fedexConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(uspsConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(dhlConn.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(upsConn.getRates(any(), anyString(), any())).thenReturn(List.of(option(carrier, serviceCode, amount)));
        when(fedexConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(uspsConn.getRates(any(), anyString(), any())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString(), any())).thenReturn(List.of());
    }

    private com.multiship.backend.model.ShippingService svc(Long id, String carrier, String serviceCode) {
        var s = com.multiship.backend.model.ShippingService.builder()
                .carrier(carrier).serviceCode(serviceCode)
                .name(carrier + " " + serviceCode)
                .scope("BOTH").enabled(true).sortOrder(0)
                .build();
        s.setId(id);
        return s;
    }

    /* -------------------------- Sprint 50 Tier 0.5 PR H — tenant clamp -------------------------- */

    @Test
    void rateShopClampsForeignCustomerNo() {
        // A scoped USER supplying customerNo=OTHER must be rejected at the
        // clamp — the fan-out never runs, so no carrier account is looked
        // up. Wire the enforcer via reflection (it's optional-injected).
        TenantScopeEnforcer enforcer = mock(TenantScopeEnforcer.class);
        when(enforcer.clampClientCode("OTHER"))
                .thenThrow(new org.springframework.security.access.AccessDeniedException(
                        "cross tenant"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tenantScope", enforcer);

        var request = RateShopRequestDTO.builder()
                .shipment(shipment()).customerNo("OTHER").build();

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.rateShop(request));

        // No carrier account resolution should have happened.
        org.mockito.Mockito.verify(accountRepo, org.mockito.Mockito.never())
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(anyString());
    }
}
