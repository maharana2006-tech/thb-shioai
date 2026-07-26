package com.multiship.backend.service;

import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
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
        service = new RateShopServiceImpl(carrierService, accountRepo, executor);
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
        when(upsConn.getRates(any(), eq("tok-ups"))).thenReturn(List.of(
                option("UPS", "03", "12.50"),
                option("UPS", "01", "42.75")));
        when(fedexConn.getRates(any(), eq("tok-fedex"))).thenReturn(List.of(
                option("FEDEX", "FEDEX_GROUND", "11.20")));
        when(uspsConn.getRates(any(), eq("tok-usps"))).thenReturn(List.of(
                option("USPS", "USPS GA", "7.85")));
        when(dhlConn.getRates(any(), eq("tok-dhl"))).thenReturn(List.of(
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
        when(upsConn.getRates(any(), eq("tok"))).thenReturn(List.of(option("UPS", "03", "12.50")));

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
        when(upsConn.getRates(any(), anyString())).thenReturn(List.of(option("UPS", "03", "12.50")));
        when(fedexConn.getRates(any(), anyString()))
                .thenThrow(new RuntimeException("FedEx API down"));
        when(uspsConn.getRates(any(), anyString())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString())).thenReturn(List.of(option("DHL", "P", "35.00")));

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
        when(fedexConn.getRates(any(), anyString())).thenReturn(List.of());
        when(uspsConn.getRates(any(), anyString())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString())).thenReturn(List.of());

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
        when(upsConn.getRates(any(), anyString())).thenReturn(List.of());
        when(fedexConn.getRates(any(), anyString())).thenReturn(List.of());
        when(uspsConn.getRates(any(), anyString())).thenReturn(List.of());
        when(dhlConn.getRates(any(), anyString())).thenReturn(List.of());

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
        when(accountRepo.findByCustomerNoIgnoreCaseAndClientDefaultTrue("C001"))
                .thenReturn(List.of(customer));
        when(accountRepo.findPlatformAccountsByCarrier(anyString()))
                .thenReturn(List.of(platform));

        CarrierAccountRef resolved = service.resolveAccount("UPS", "C001");
        assertNotNull(resolved);
        assertEquals("customer-cid", resolved.getClientId());
    }

    @Test
    void resolveAccountFallsBackToPlatformWhenCustomerHasNone() {
        when(accountRepo.findByCustomerNoIgnoreCaseAndClientDefaultTrue("C001"))
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
        when(upsConn.getRates(any(), anyString())).thenReturn(List.of(option("UPS", "03", "12.50")));
        when(fedexConn.getRates(any(), anyString())).thenReturn(List.of(option("FEDEX", "FEDEX_GROUND", "11.20")));

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
}
