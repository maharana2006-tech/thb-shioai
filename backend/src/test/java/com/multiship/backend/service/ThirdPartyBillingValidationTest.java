package com.multiship.backend.service;

import com.multiship.backend.dto.ManualShipmentRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CarrierServiceImpl#validateThirdPartyBilling}: normalisation + the carrier rules. */
class ThirdPartyBillingValidationTest {

    private static ManualShipmentRequest thirdParty(String account, String postal, String country) {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setAccountNumber("SHIPPER1");
        req.setBillTo("third_party");
        req.setThirdPartyAccount(account);
        req.setThirdPartyPostalCode(postal);
        req.setThirdPartyCountry(country);
        return req;
    }

    @Test
    void blankBillToDefaultsToSenderAndClearsPayer() {
        ManualShipmentRequest req = thirdParty("123456789", "10001", "us");
        req.setBillTo(null);
        assertNull(CarrierServiceImpl.validateThirdPartyBilling(req, "FEDEX"));
        assertEquals("SENDER", req.getBillTo());
        assertNull(req.getThirdPartyAccount());
        assertEquals("SHIPPER1", req.getAccountNumber(), "the shipper account is never touched");
    }

    @Test
    void unknownBillToIsRejected() {
        ManualShipmentRequest req = thirdParty("123456789", null, null);
        req.setBillTo("RECIPIENT");
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(req, "FEDEX").contains("must be SENDER or THIRD_PARTY"));
    }

    @Test
    void uspsCannotBillAThirdParty() {
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("ABC123", "10001", "US"), "USPS")
                .contains("USPS"));
    }

    @Test
    void upsNeedsAccountPostalCodeAndCountry() {
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty(" ", "10001", "US"), "UPS")
                .contains("thirdPartyAccount is required"));
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("12", "10001", "US"), "UPS")
                .contains("not a valid UPS account"));
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("ABC123", null, "US"), "UPS")
                .contains("thirdPartyPostalCode and thirdPartyCountry are required"));
        ManualShipmentRequest ok = thirdParty(" abc123 ", " 10001 ", "us");
        assertNull(CarrierServiceImpl.validateThirdPartyBilling(ok, "UPS"));
        assertEquals("THIRD_PARTY", ok.getBillTo());
        assertEquals("abc123", ok.getThirdPartyAccount());
        assertEquals("10001", ok.getThirdPartyPostalCode());
        assertEquals("US", ok.getThirdPartyCountry());
        assertEquals("SHIPPER1", ok.getAccountNumber());
    }

    @Test
    void fedexNeedsNineDigitsAndPostalCodeIsOptional() {
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("ABC123", null, null), "FEDEX")
                .contains("not a valid FEDEX account"));
        assertNull(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("123456789", null, null), "FEDEX"));
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("123456789", null, "USA"), "FEDEX")
                .contains("2-letter ISO code"));
    }

    @Test
    void maskKeepsLastFour() {
        assertEquals("****6789", CarrierServiceImpl.maskAccount("123456789"));
        assertEquals("****", CarrierServiceImpl.maskAccount("123"));
        assertEquals("****", CarrierServiceImpl.maskAccount(null));
    }

    @Test
    void dhlPayerAccountIsNineToTwelveDigits() {
        assertNull(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("123456789", null, null), "DHL"));
        assertTrue(CarrierServiceImpl.validateThirdPartyBilling(thirdParty("12", null, null), "DHL")
                .contains("thirdPartyAccount"));
    }
}
