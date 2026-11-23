package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.carriers.exceptions.CarrierException;
import com.multiship.backend.service.carriers.exceptions.CarrierValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Click &amp; Drop wire-shape guards — no HTTP. Covers the conversions the
 * API is strict about (grams, millimetres, alpha-3 countries, package
 * formats) and both halves of the create-orders response.
 */
class RoyalMailConnectorTest {

    private RoyalMailConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        props.getRoyalMail().setApiBaseUrl("https://api.parcel.royalmail.com/api/v1");
        props.getRoyalMail().setDefaultServiceType("TPS");
        props.getRoyalMail().setDefaultPackageType("parcel");
        props.getRoyalMail().setTrackingBaseUrl("https://api.royalmail.net/mailpieces/v2");
        SystemSettingService settings = org.mockito.Mockito.mock(SystemSettingService.class);
        org.mockito.Mockito.when(settings.getDecrypted(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        connector = new RoyalMailConnector(props, new ObjectMapper(), settings);
    }

    @Test
    void parsesTrackingEventsOldestFirstAndDetectsDelivery() {
        String body = """
            {"mailPieces":{"mailPieceId":"AB123456789GB","carrierShortName":"RM",
              "summary":{"lastEventName":"Delivered","lastEventCode":"EVKSP","lastEventDateTime":"2026-11-18T11:02:00+00:00",
                         "lastEventLocationName":"Buffalo DO","statusCategory":"DELIVERED","statusDescription":"Delivered"},
              "estimatedDelivery":{"date":"2026-11-18"},
              "events":[
                {"eventCode":"EVKSP","eventName":"Delivered","eventDateTime":"2026-11-18T11:02:00+00:00","locationName":"Buffalo DO"},
                {"eventCode":"EVGPD","eventName":"Item received","eventDateTime":"2026-11-17T18:30:00+00:00","locationName":"Mount Pleasant MC"}]}}""";
        CarrierConnector.TrackingResult r = connector.parseTrackingResponse(body, "AB123456789GB");

        assertThat(r.delivered()).isTrue();
        assertThat(r.status()).isEqualTo("Delivered");
        assertThat(r.currentLocation()).isEqualTo("Buffalo DO");
        assertThat(r.estimatedDelivery()).isEqualTo(java.time.LocalDate.of(2026, 11, 18).atStartOfDay());
        assertThat(r.events()).extracting(CarrierConnector.TrackingEvent::status).containsExactly("EVGPD", "EVKSP");
        assertThat(r.events().get(0).timestamp()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 17, 18, 30));
    }

    @Test
    void trackingApiErrorsBecomeACarrierException() {
        String body = """
            {"httpCode":"404","httpMessage":"Not Found",
             "errors":[{"errorCode":"E1142","errorDescription":"Tracking number not found.","errorResolution":"Check the number."}]}""";
        assertThatThrownBy(() -> connector.parseTrackingResponse(body, "ZZ000000000GB"))
                .isInstanceOf(CarrierException.class)
                .hasMessageContaining("Tracking number not found");
    }

    @Test
    void withoutTrackingCredentialsTrackingIsUrlOnly() {
        CarrierConnector.TrackingResult r = connector.trackShipment("AB123456789GB");
        assertThat(r.events()).isEmpty();
        assertThat(r.trackingUrl()).endsWith("/AB123456789GB");
    }

    private static ShipmentRequestDTO ukParcel() {
        ShipmentRequestDTO r = new ShipmentRequestDTO();
        r.setCarrierCode("ROYAL_MAIL");
        r.setServiceType("TPN");
        r.setPackageType("YOUR_PACKAGING");
        r.setWeight(new BigDecimal("2"));
        r.setWeightUnit("LB");
        r.setLength(new BigDecimal("10"));
        r.setWidth(new BigDecimal("8"));
        r.setHeight(new BigDecimal("6"));
        r.setDimUnit("IN");
        r.setReferenceNumber("ZZ-RM-0001");
        r.setShipperName("ZZ Test Client");
        r.setShipperPhone("02079460958");
        r.setRecipientName("Sam Patel");
        r.setRecipientCompany("Acme Ltd");
        r.setRecipientAddressLine1("1 High Street");
        r.setRecipientCity("London");
        r.setRecipientPostalCode("SW1A 1AA");
        r.setRecipientCountryCode("GB");
        r.setRecipientPhone("02079460000");
        return r;
    }

    @Test
    void registersUnderTheRoyalMailCarrierCode() {
        assertThat(connector.getCarrierCode()).isEqualTo("ROYAL_MAIL");
        assertThat(connector.getCarrierName()).isEqualTo("Royal Mail");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsAClickAndDropOrderInGramsMillimetresAndAlpha3() {
        Map<String, Object> body = connector.buildOrdersPayload(ukParcel());
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, Object> order = items.get(0);

        assertThat(order.get("orderReference")).isEqualTo("ZZ-RM-0001");
        assertThat(order.get("isRecipientABusiness")).isEqualTo(true);
        assertThat(order.get("currencyCode")).isEqualTo("GBP");

        Map<String, Object> address = (Map<String, Object>) ((Map<String, Object>) order.get("recipient")).get("address");
        assertThat(address.get("countryCode")).isEqualTo("GBR");
        assertThat(address.get("postcode")).isEqualTo("SW1A 1AA");

        Map<String, Object> pkg = ((List<Map<String, Object>>) order.get("packages")).get(0);
        assertThat(pkg.get("weightInGrams")).isEqualTo(908);          // 2 lb, rounded up
        assertThat(pkg.get("packageFormatIdentifier")).isEqualTo("parcel");
        Map<String, Object> dims = (Map<String, Object>) pkg.get("dimensions");
        assertThat(dims.get("depthInMms")).isEqualTo(254);             // 10 in
        assertThat(dims.get("heightInMms")).isEqualTo(153);            // 6 in, rounded up

        Map<String, Object> postage = (Map<String, Object>) order.get("postageDetails");
        assertThat(postage.get("serviceCode")).isEqualTo("TPN");
        Map<String, Object> label = (Map<String, Object>) order.get("label");
        assertThat(label).containsEntry("includeLabelInResponse", true).containsEntry("includeCN", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void internationalLaneAddsCustomsCategoryAndCn22() {
        ShipmentRequestDTO r = ukParcel();
        r.setRecipientCountryCode("FR");
        IntlShipmentBlockDTO intl = new IntlShipmentBlockDTO();
        intl.setReasonForExport("GIFT");
        CustomsCommodityDTO line = new CustomsCommodityDTO();
        line.setDescription("Wool scarf");
        line.setQuantity(2);
        line.setUnitValue(new BigDecimal("15.00"));
        line.setHsCode("621420");
        line.setCountryOfOrigin("GB");
        intl.setCommodities(List.of(line));
        r.setIntl(intl);

        Map<String, Object> order = ((List<Map<String, Object>>) connector.buildOrdersPayload(r).get("items")).get(0);
        assertThat(order.get("customsDeclarationCategory")).isEqualTo("gift");
        assertThat(((Map<String, Object>) order.get("label")).get("includeCN")).isEqualTo(true);
        Map<String, Object> pkg = ((List<Map<String, Object>>) order.get("packages")).get(0);
        Map<String, Object> contents = ((List<Map<String, Object>>) pkg.get("contents")).get(0);
        assertThat(contents).containsEntry("customsCode", "621420").containsEntry("originCountryCode", "GBR")
                .containsEntry("quantity", 2);
    }

    @Test
    void unitHelpersConvertTheWayClickAndDropExpects() {
        assertThat(RoyalMailConnector.grams(new BigDecimal("1.5"), "KG")).isEqualTo(1500);
        assertThat(RoyalMailConnector.grams(new BigDecimal("16"), "OZ")).isEqualTo(454);
        assertThat(RoyalMailConnector.mm(new BigDecimal("30"), "CM")).isEqualTo(300);
        assertThat(RoyalMailConnector.alpha3("US")).isEqualTo("USA");
        assertThat(RoyalMailConnector.alpha3("UK")).isEqualTo("GBR");
        assertThat(RoyalMailConnector.alpha3("GBR")).isEqualTo("GBR");
        assertThat(RoyalMailConnector.packageFormat("Large Letter")).isEqualTo("largeLetter");
        assertThat(RoyalMailConnector.packageFormat("CUSTOM_BOX")).isEqualTo("parcel");
    }

    @Test
    void parsesACreatedOrderIntoTrackingAndInlineLabel() {
        String response = """
            {"successCount":1,"errorsCount":0,
             "createdOrders":[{"orderIdentifier":123456,"orderReference":"ZZ-RM-0001","createdOn":"2026-11-18T10:00:00Z",
                               "trackingNumber":"AB123456789GB","label":"JVBERi0xLjQK",
                               "packages":[{"packageNumber":1,"trackingNumber":"AB123456789GB"}]}],
             "failedOrders":[]}""";
        CarrierConnector.ShipmentResult result = connector.parseCreateOrdersResponse(response);

        assertThat(result.trackingNumber()).isEqualTo("AB123456789GB");
        assertThat(result.labelPdf()).isEqualTo("JVBERi0xLjQK");
        assertThat(result.trackingUrl()).endsWith("/AB123456789GB");
        assertThat(result.packages()).hasSize(1);
        assertThat(result.packages().get(0).labelPdf()).isEqualTo("JVBERi0xLjQK");
    }

    @Test
    void surfacesAFailedOrderAsAValidationErrorWithTheFieldName() {
        String response = """
            {"successCount":0,"errorsCount":1,"createdOrders":[],
             "failedOrders":[{"order":{},"errors":[{"errorCode":400,"errorMessage":"Postcode is invalid",
                                                    "fields":[{"fieldName":"recipient.address.postcode","value":"XYZ"}]}]}]}""";
        assertThatThrownBy(() -> connector.parseCreateOrdersResponse(response))
                .isInstanceOf(CarrierValidationException.class)
                .hasMessageContaining("Postcode is invalid")
                .hasMessageContaining("recipient.address.postcode");
    }

    @Test
    void labelErrorsOnACreatedOrderStillFailTheShipment() {
        String response = """
            {"createdOrders":[{"orderIdentifier":9,"createdOn":"2026-11-18T10:00:00Z",
                               "labelErrors":[{"code":"E1010","message":"Service not available on this account"}]}]}""";
        assertThatThrownBy(() -> connector.parseCreateOrdersResponse(response))
                .isInstanceOf(CarrierValidationException.class)
                .hasMessageContaining("Service not available");
    }

    @Test
    void apiKeyLivesInTheSecretSlotButTheIdSlotIsTolerated() {
        assertThat(RoyalMailConnector.apiKeyOf(null, " key-1 ")).isEqualTo("key-1");
        assertThat(RoyalMailConnector.apiKeyOf("key-2", "")).isEqualTo("key-2");
        assertThatThrownBy(() -> connector.validateCredentials("", " "))
                .isInstanceOf(CarrierConnectionException.class);
    }

    @Test
    void refusesToBuyLabelsWithoutALiveToken() {
        assertThatThrownBy(() -> connector.createShipment(ukParcel(), "ROYAL_MAIL-local-401", "SANDBOX"))
                .isInstanceOf(CarrierException.class)
                .hasMessageContaining("not verified live");
        assertThat(connector.listServices("GB", null, "SANDBOX").live()).isFalse();
        assertThat(connector.listServices("GB", null, "SANDBOX").offerings()).isNotEmpty();
    }

    @Test
    void buildsThePublicTrackingUrlWithoutCredentials() {
        CarrierConnector.TrackingResult result = connector.trackShipment("  AB123456789GB  ");
        assertThat(result.trackingNumber()).isEqualTo("AB123456789GB");
        assertThat(result.trackingUrl()).endsWith("/AB123456789GB");
        assertThatThrownBy(() -> connector.trackShipment(" ")).isInstanceOf(CarrierException.class);
    }
}
