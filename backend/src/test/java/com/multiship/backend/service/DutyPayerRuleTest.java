package com.multiship.backend.service;

import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.model.OrderCustoms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-09-10 load test: FedEx DAP invoices to Canada said "Payable by consignee
 * (DAP)" next to "Duties billed to: Shipper" because the client's Canada customs
 * profile (DDP, duties billed to shipper) overrode the order's own Incoterm.
 */
class DutyPayerRuleTest {

    @Test
    void theIncotermDecidesWhenNothingWasChosen() {
        assertEquals("RECIPIENT", CarrierServiceImpl.effectiveDutyPayer(null, "DAP"));
        assertEquals("RECIPIENT", CarrierServiceImpl.effectiveDutyPayer(null, null));
        assertEquals("SENDER", CarrierServiceImpl.effectiveDutyPayer(null, "ddp"));
        assertEquals("THIRD_PARTY", CarrierServiceImpl.effectiveDutyPayer("THIRD_PARTY", "DAP"));
        assertEquals("RECIPIENT", CarrierServiceImpl.effectiveDutyPayer("RECEIVER", "DDP"));
    }

    @Test
    void regenerateUsesRecordedPayerThenOrderIncotermThenProfile() {
        ClientCustomsProfile profile = mock(ClientCustomsProfile.class);
        when(profile.getDutiesBillTo()).thenReturn("SENDER");

        OrderCustoms recorded = mock(OrderCustoms.class);
        when(recorded.getDutiesPaidBy()).thenReturn("recipient");
        assertEquals("RECIPIENT", CarrierServiceImpl.orderDutyPayer(recorded, profile));

        OrderCustoms dapOrder = mock(OrderCustoms.class);
        when(dapOrder.getIncoterms()).thenReturn("DAP");
        assertNull(CarrierServiceImpl.orderDutyPayer(dapOrder, profile),
                "an order with its own Incoterm must not inherit the profile's 'billed to shipper'");

        OrderCustoms noIncoterm = mock(OrderCustoms.class);
        assertEquals("SENDER", CarrierServiceImpl.orderDutyPayer(noIncoterm, profile));
        assertNull(CarrierServiceImpl.orderDutyPayer(null, null));
    }
}
