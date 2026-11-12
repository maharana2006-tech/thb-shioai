package com.multiship.backend.service.dtc;

import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.DtcOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DtcThirdPartyBillingTest {

    @Test
    void oracleThirdPartyAcc_billsFreightToThatAccount_withPayerAddressFromCarrierAccount() {
        DtcOrder row = new DtcOrder();
        row.setThirdPartyAccount(" ABC123 ");
        CarrierAccountRef account = new CarrierAccountRef();
        account.setThirdPartyPostcode("06877");
        account.setThirdPartyCountry("US");
        ManualShipmentRequest req = new ManualShipmentRequest();

        DtcLabelGenerationService.applyThirdPartyBilling(req, row, account);

        assertEquals("THIRD_PARTY", req.getBillTo());
        assertEquals("ABC123", req.getThirdPartyAccount());
        assertEquals("06877", req.getThirdPartyPostalCode());
        assertEquals("US", req.getThirdPartyCountry());
    }

    @Test
    void blankThirdPartyAcc_leavesShipperBilling() {
        DtcOrder row = new DtcOrder();
        row.setThirdPartyAccount("  ");
        ManualShipmentRequest req = new ManualShipmentRequest();

        DtcLabelGenerationService.applyThirdPartyBilling(req, row, new CarrierAccountRef());

        assertNull(req.getBillTo());
        assertNull(req.getThirdPartyAccount());
    }
}
