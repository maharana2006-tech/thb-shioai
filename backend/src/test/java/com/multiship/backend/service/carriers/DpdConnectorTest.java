package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.service.carriers.exceptions.CarrierValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DPD UK wire-shape guards — no HTTP. */
class DpdConnectorTest {

    private DpdConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("PRODUCTION");
        props.getDpd().setApiBaseUrl("https://api.dpd.co.uk");
        props.getDpd().setDefaultNetworkCode("1^12");
        props.getDpd().setLabelAccept("application/pdf");
        props.getDpd().setSandboxBaseUrl("http://localhost:8099");
        connector = new DpdConnector(props, new ObjectMapper());
    }

    private static ShipmentRequestDTO request() {
        ShipmentRequestDTO r = new ShipmentRequestDTO();
        r.setCarrierCode("DPD");
        r.setAccountNumber("1234567");
        r.setShipperName("Warehouse Team");
        r.setShipperCompany("ZZTEST1 Ltd");
        r.setShipperPhone("+44 (0)121 496 0000");
        r.setShipperAddressLine1("1 Depot Road");
        r.setShipperCity("Birmingham");
        r.setShipperState("West Midlands");
        r.setShipperPostalCode("B1 1AA");
        r.setShipperCountryCode("GB");
        r.setRecipientName("Pat Example");
        r.setRecipientPhone("07700 900123");
        r.setRecipientEmail("pat@example.test");
        r.setRecipientAddressLine1("10 Downing Street");
        r.setRecipientCity("London");
        r.setRecipientPostalCode("SW1A 2AA");
        r.setRecipientCountryCode("gb");
        r.setReferenceNumber("ORDER-123456789012345678901234567890");
        r.setSpecialInstructions("Leave with neighbour");
        r.setWeight(new BigDecimal("4.4"));
        r.setWeightUnit("LB");
        r.setShipDateOverride(LocalDate.of(2026, 11, 20));
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsConsignmentWithDefaultNetworkKgWeightAndTrimmedFields() {
        Map<String, Object> body = connector.buildShipmentPayload(request());

        assertThat(body.get("collectionDate")).isEqualTo("2026-11-20T09:00:00");
        List<Map<String, Object>> consignments = (List<Map<String, Object>>) body.get("consignment");
        Map<String, Object> c = consignments.get(0);
        assertThat(c.get("networkCode")).isEqualTo("1^12");
        assertThat(c.get("numberOfParcels")).isEqualTo(1);
        assertThat(c.get("totalWeight")).isEqualTo(new BigDecimal("2.0")); // 4.4 lb = 1.996 kg, rounded up
        assertThat(c.get("shippingRef1")).isEqualTo("ORDER-1234567890123456789");
        assertThat(c).doesNotContainKey("customsValue");

        Map<String, Object> delivery = (Map<String, Object>) c.get("deliveryDetails");
        Map<String, Object> address = (Map<String, Object>) delivery.get("address");
        assertThat(address.get("countryCode")).isEqualTo("GB");
        assertThat(address.get("street")).isEqualTo("10 Downing Street");
        Map<String, Object> contact = (Map<String, Object>) delivery.get("contactDetails");
        assertThat(contact.get("telephone")).isEqualTo("07700900123");
        Map<String, Object> notify = (Map<String, Object>) delivery.get("notificationDetails");
        assertThat(notify.get("email")).isEqualTo("pat@example.test");

        Map<String, Object> collection = (Map<String, Object>) c.get("collectionDetails");
        assertThat(((Map<String, Object>) collection.get("address")).get("organisation")).isEqualTo("ZZTEST1 Ltd");
        assertThat(collection).doesNotContainKey("notificationDetails");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiPieceSumsWeightAndCountsParcels() {
        ShipmentRequestDTO r = request();
        r.setServiceType("1^32");
        r.setRecipientCountryCode("DE");
        r.setDeclaredValue(new BigDecimal("120.00"));
        PackageDetailDTO a = new PackageDetailDTO(); a.setWeight(new BigDecimal("1.5")); a.setWeightUnit("KG"); a.setDescription("Books");
        PackageDetailDTO b = new PackageDetailDTO(); b.setWeight(new BigDecimal("500")); b.setWeightUnit("G");
        r.setPackages(List.of(a, b));

        Map<String, Object> c = ((List<Map<String, Object>>) connector.buildShipmentPayload(r).get("consignment")).get(0);
        assertThat(c.get("networkCode")).isEqualTo("1^32");
        assertThat(c.get("numberOfParcels")).isEqualTo(2);
        assertThat(c.get("totalWeight")).isEqualTo(new BigDecimal("2.0"));
        assertThat(c.get("customsValue")).isEqualTo(new BigDecimal("120.00"));
        assertThat(c.get("parcelDescription")).isEqualTo("Books");
    }

    @Test
    void kgRoundsUpAndNeverDeclaresZero() {
        assertThat(DpdConnector.kg(new BigDecimal("1"), "LB")).isEqualTo(new BigDecimal("0.5"));
        assertThat(DpdConnector.kg(new BigDecimal("16"), "OZ")).isEqualTo(new BigDecimal("0.5"));
        assertThat(DpdConnector.kg(new BigDecimal("250"), "G")).isEqualTo(new BigDecimal("0.3"));
        assertThat(DpdConnector.kg(new BigDecimal("0.01"), "KG")).isEqualTo(new BigDecimal("0.1"));
        assertThat(DpdConnector.kg(null, "KG")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void parsesCreatedShipmentIntoConsignmentAndParcels() {
        String body = """
            {"error":null,"data":{"shipmentId":1807000123,"consolidated":false,
              "consignmentDetail":[{"consignmentNumber":"15501234567890","parcelNumbers":["15501234567890A","15501234567890B"]}]}}""";
        CarrierConnector.ShipmentResult r = connector.parseShipmentResponse(body, "UERGLQ==");

        assertThat(r.trackingNumber()).isEqualTo("15501234567890");
        assertThat(r.trackingUrl()).isEqualTo("https://track.dpd.co.uk/parcels/15501234567890");
        assertThat(r.labelPdf()).isEqualTo("UERGLQ==");
        assertThat(r.packages()).hasSize(2);
        assertThat(r.packages().get(1).trackingNumber()).isEqualTo("15501234567890B");
        assertThat(r.packages().get(0).carrierLabelRef()).isEqualTo("1807000123");
    }

    @Test
    void dpdErrorObjectAndArrayBothBecomeValidationErrors() {
        String object = """
            {"error":{"errorCode":"1","errorType":"VALIDATION","obj":"consignment[0].deliveryDetails.address.postcode","errorMessage":"Invalid postcode"},"data":null}""";
        assertThatThrownBy(() -> connector.parseShipmentResponse(object, null))
                .isInstanceOf(CarrierValidationException.class)
                .hasMessageContaining("Invalid postcode")
                .hasMessageContaining("postcode]");

        String array = """
            {"error":[{"errorType":"AUTH","errorMessage":"Session expired"},{"errorType":"X","errorMessage":"Second"}],"data":null}""";
        assertThatThrownBy(() -> connector.dataOrThrow(array, "login"))
                .isInstanceOf(CarrierValidationException.class)
                .hasMessageContaining("Session expired; Second");
    }

    @Test
    void sessionTokenCarriesTheAccountForTheGeoClientHeader() {
        String token = DpdConnector.sessionToken("1234567", "abc|def");
        assertThat(DpdConnector.splitToken(token)).containsExactly("1234567", "abc|def");
        assertThat(DpdConnector.splitToken("bare")).containsExactly("", "bare");
    }

    @Test
    void refusesToLogInWithoutCredentialsOrAccount() {
        assertThat(connector.getAccessToken("user", "", "1234567", null)).isEqualTo("DPD-local-missing-credentials");
        assertThat(connector.consumeAuthFailureDetail()).contains("username and password");
        assertThat(connector.getAccessToken("user", "pass")).isEqualTo("DPD-local-missing-account");
        assertThat(connector.consumeAuthFailureDetail()).contains("GEOClient");
        assertThatThrownBy(() -> connector.validateCredentials("user", " "))
                .isInstanceOf(CarrierConnectionException.class);
    }

    @Test
    void parsesNetworksAndPrefixesCarrierName() throws Exception {
        String body = """
            {"error":null,"data":[
              {"network":{"networkCode":"1^12","networkDescription":"DPD Next Day"},"product":{"productDescription":"Parcel"},"service":{"serviceDescription":"Next Day"}},
              {"network":{"networkCode":"1^13"},"product":{"productDescription":"Parcel"},"service":{"serviceDescription":"By 12:00"}},
              {"network":{}}]}""";
        List<CarrierConnector.ServiceOffering> offerings = connector.parseNetworks(body, "DOMESTIC");
        assertThat(offerings).extracting(CarrierConnector.ServiceOffering::serviceCode).containsExactly("1^12", "1^13");
        assertThat(offerings.get(1).name()).isEqualTo("DPD Parcel By 12:00");
        assertThat(offerings.get(1).scope()).isEqualTo("DOMESTIC");
    }

    @Test
    void matchesShipmentIdByConsignmentOrParcelNumber() throws Exception {
        var data = new ObjectMapper().readTree("""
            {"results":[{"shipmentId":77,"consignmentDetail":[{"consignmentNumber":"15501234567890","parcelNumbers":["15501234567890A"]}]},
                        {"shipmentId":78,"consignmentDetail":[{"consignmentNumber":"15509999999999","parcelNumbers":[]}]}]}""");
        assertThat(DpdConnector.matchShipmentId(data, "15509999999999")).isEqualTo("78");
        assertThat(DpdConnector.matchShipmentId(data, "15501234567890A")).isEqualTo("77");
        assertThat(DpdConnector.matchShipmentId(data, "nope")).isNull();
    }

    @Test
    void doesNotClaimToQuotePrices() {
        assertThat(connector.lacksRateApi()).isTrue();
        assertThat(connector.getRates(request(), "1234567|MOCK-x", "SANDBOX")).isEmpty();
    }

    @Test
    void sandboxAccountsGoToTheMockAndProductionToDpd() {
        assertThat(connector.baseUrl("SANDBOX")).isEqualTo("http://localhost:8099");
        assertThat(connector.baseUrl("sandbox")).isEqualTo("http://localhost:8099");
        assertThat(connector.baseUrl("PRODUCTION")).isEqualTo("https://api.dpd.co.uk");
        assertThat(connector.baseUrl(null)).isEqualTo("https://api.dpd.co.uk");
    }

    @Test
    void trackingIsUrlOnlyAndFallbackCatalogueIsNotLive() {
        CarrierConnector.TrackingResult t = connector.trackShipment(" 15501234567890 ");
        assertThat(t.trackingUrl()).isEqualTo("https://track.dpd.co.uk/parcels/15501234567890");
        assertThat(t.delivered()).isFalse();

        CarrierConnector.ServiceAvailability s = connector.listServices("GB", "DPD-local-missing-account", "PRODUCTION");
        assertThat(s.live()).isFalse();
        assertThat(s.offerings()).isEqualTo(DpdConnector.FALLBACK_SERVICES);
    }
}
