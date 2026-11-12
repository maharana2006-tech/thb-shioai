package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The freight payment element each carrier gets when shipping charges are billed to a third party. */
class ThirdPartyBillingPayloadTest {

    private static ShipmentRequestDTO.ShipmentRequestDTOBuilder request(String carrier) {
        return ShipmentRequestDTO.builder()
                .carrierCode(carrier).accountNumber("SHIPPER1").serviceType("03").packageType("02")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567").shipperAddressLine1("1 Warehouse Way")
                .shipperCity("Louisville").shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane Doe").recipientPhone("5559876543").recipientAddressLine1("42 Broadway")
                .recipientCity("New York").recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .referenceNumber("PO-1001");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> upsPayment(ShipmentRequestDTO dto) throws Exception {
        UpsConnector connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(connector, dto);
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>) payload.get("ShipmentRequest")).get("Shipment");
        List<Map<String, Object>> charges = (List<Map<String, Object>>)
                ((Map<String, Object>) shipment.get("PaymentInformation")).get("ShipmentCharge");
        assertEquals("01", charges.get(0).get("Type"));
        return charges.get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ups_thirdParty_billsTheirAccountWithBillingAddress_shipperNumberUnchanged() throws Exception {
        Map<String, Object> freight = upsPayment(request("UPS")
                .billTo("THIRD_PARTY").thirdPartyAccount("ABC123").thirdPartyPostalCode("60606").thirdPartyCountry("US")
                .build());
        Map<String, Object> third = (Map<String, Object>) freight.get("BillThirdParty");
        assertNotNull(third, "freight goes to BillThirdParty");
        assertNull(freight.get("BillShipper"));
        assertEquals("ABC123", third.get("AccountNumber"));
        assertEquals(Map.of("PostalCode", "60606", "CountryCode", "US"), third.get("Address"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ups_sender_keepsBillShipper() throws Exception {
        Map<String, Object> freight = upsPayment(request("UPS").billTo("SENDER").thirdPartyAccount("ABC123").build());
        assertEquals("SHIPPER1", ((Map<String, Object>) freight.get("BillShipper")).get("AccountNumber"));
        assertNull(freight.get("BillThirdParty"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedex_thirdParty_setsPaymentTypeAndPayor() {
        FedExConnector connector = new FedExConnector(new CarrierProperties(), new ObjectMapper(), null);
        Map<String, Object> payment = connector.buildShippingChargesPayment(request("FEDEX")
                .billTo("THIRD_PARTY").thirdPartyAccount("123456789").thirdPartyCountry("US").build());
        assertEquals("THIRD_PARTY", payment.get("paymentType"));
        Map<String, Object> party = (Map<String, Object>) ((Map<String, Object>) payment.get("payor")).get("responsibleParty");
        assertEquals(Map.of("value", "123456789"), party.get("accountNumber"));
        assertEquals(Map.of("countryCode", "US"), party.get("address"));

        Map<String, Object> sender = connector.buildShippingChargesPayment(request("FEDEX").build());
        assertEquals("SENDER", sender.get("paymentType"));
        Map<String, Object> senderParty = (Map<String, Object>) ((Map<String, Object>) sender.get("payor")).get("responsibleParty");
        assertEquals(Map.of("value", "SHIPPER1"), senderParty.get("accountNumber"));
        assertFalse(senderParty.containsKey("address"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ups_rateShop_quotesOnThePayerAccount() throws Exception {
        UpsConnector connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildRateShopShipment", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> shipment = (Map<String, Object>) m.invoke(connector, request("UPS")
                .billTo("THIRD_PARTY").thirdPartyAccount("ABC123").thirdPartyPostalCode("60606").thirdPartyCountry("US").build());
        List<Map<String, Object>> charges = (List<Map<String, Object>>)
                ((Map<String, Object>) shipment.get("PaymentDetails")).get("ShipmentCharge");
        assertEquals("ABC123", ((Map<String, Object>) charges.get(0).get("BillThirdParty")).get("AccountNumber"));

        Map<String, Object> sender = (Map<String, Object>) m.invoke(connector, request("UPS").build());
        List<Map<String, Object>> senderCharges = (List<Map<String, Object>>)
                ((Map<String, Object>) sender.get("PaymentDetails")).get("ShipmentCharge");
        assertEquals("SHIPPER1", ((Map<String, Object>) senderCharges.get(0).get("BillShipper")).get("AccountNumber"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedex_rate_quotesOnThePayerAccount() {
        FedExConnector connector = new FedExConnector(new CarrierProperties(), new ObjectMapper(), null);
        Map<String, Object> body = connector.buildRateRequestBody(request("FEDEX")
                .billTo("THIRD_PARTY").thirdPartyAccount("123456789").build());
        Map<String, Object> payment = (Map<String, Object>)
                ((Map<String, Object>) body.get("requestedShipment")).get("shippingChargesPayment");
        assertEquals("THIRD_PARTY", payment.get("paymentType"));
    }

    @Test
    void dhl_thirdParty_addsPayerAccountBesideShipper() {
        assertEquals(List.of(
                        Map.of("typeCode", "shipper", "number", "SHIPPER1"),
                        Map.of("typeCode", "payer", "number", "123456789")),
                DhlConnector.buildAccounts(request("DHL").billTo("THIRD_PARTY").thirdPartyAccount("123456789").build()));
        assertEquals(List.of(Map.of("typeCode", "shipper", "number", "SHIPPER1")),
                DhlConnector.buildAccounts(request("DHL").build()));
    }
}
