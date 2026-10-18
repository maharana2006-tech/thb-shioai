package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.StampsFunding;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.StampsFundingRepository;
import com.multiship.backend.service.AuditService;
import com.multiship.backend.service.carriers.CarrierConnector.BalanceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Adding USPS postage on Stamps.com: the limits, the double-click guard, and the history. */
class StampsPostageServiceTest {

    private static final String KEY = "2b3f8a52-6c1d-4e8a-9f3b-1a2b3c4d5e6f";

    private CarrierAccountRefRepository accounts;
    private StampsFundingRepository fundings;
    private StampsSeraOAuthService oauth;
    private StampsConnector stamps;
    private AuditService audit;
    private StampsPostageService service;

    @BeforeEach
    void setUp() {
        accounts = mock(CarrierAccountRefRepository.class);
        fundings = mock(StampsFundingRepository.class);
        oauth = mock(StampsSeraOAuthService.class);
        stamps = mock(StampsConnector.class);
        audit = mock(AuditService.class);
        service = new StampsPostageService(accounts, fundings, oauth, stamps, audit);

        CarrierAccountRef usps = new CarrierAccountRef();
        usps.setId(9L);
        usps.setCarrierCode("USPS");
        usps.setAccountNumber("874157");
        usps.setClientId("cid");
        usps.setEnvironment("SANDBOX");
        usps.setStampsRefreshToken("rt");
        when(accounts.findById(9L)).thenReturn(Optional.of(usps));
        when(oauth.refreshToken(eq("rt"), any(), any(), any()))
                .thenReturn(StampsSeraOAuthService.TokenExchangeResult.success("tok", null, 3600));
        when(stamps.getAccountBalance("tok", "SANDBOX")).thenReturn(balance("18.40"));
        when(fundings.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(fundings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static BalanceResult balance(String available) {
        return new BalanceResult("USPS", new BigDecimal(available), new BigDecimal("500"), "USD", "OK", null, null);
    }

    @Test
    void addsFunds_recordsTheTopUp_andReturnsTheNewBalance() {
        when(stamps.addFundsSera("tok", new BigDecimal("50"), "usd", KEY, "SANDBOX")).thenReturn(balance("68.40"));

        var result = service.addFunds(9L, new BigDecimal("50"), KEY, "admin");

        assertEquals(new BigDecimal("68.40"), result.balance().available());
        assertEquals(StampsFunding.ADDED, result.funding().status());
        assertEquals("admin", result.funding().createdBy());
        verify(audit).record(eq(AuditService.UPDATE), eq(AuditService.CARRIER_ACCOUNT), eq(9L), anyString(), any(),
                eq("Added $50.00 postage on Stamps.com"));
    }

    @Test
    void lessThanTenDollars_isRefusedBeforeCallingStamps() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addFunds(9L, new BigDecimal("5"), KEY, "admin"));
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("Add at least $10.00.", ex.getReason());
        verifyNoInteractions(stamps);
    }

    @Test
    void goingOverStampsBalanceLimit_saysHowMuchFits() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addFunds(9L, new BigDecimal("490"), KEY, "admin"));
        assertEquals("That would go over Stamps.com's balance limit of $500.00. You can add up to $481.60.",
                ex.getReason());
        verify(stamps, never()).addFundsSera(any(), any(), any(), any(), any());
    }

    @Test
    void theSamePurchaseTwice_chargesOnce() {
        StampsFunding earlier = StampsFunding.builder().id(1L).amount(new BigDecimal("50")).currency("USD")
                .status(StampsFunding.ADDED).balanceAfter(new BigDecimal("68.40")).idempotencyKey(KEY).build();
        when(fundings.findByIdempotencyKey(KEY)).thenReturn(Optional.of(earlier));

        var result = service.addFunds(9L, new BigDecimal("50"), KEY, "admin");

        assertEquals(1L, result.funding().id());
        verifyNoInteractions(stamps);
    }

    @Test
    void aPendingReservation_isRecoveredByReChargingIdempotently() {
        // A prior call reserved the key but didn't return (crash/timeout). The
        // retry must re-call Stamps (which is idempotent on the key) and settle
        // the SAME row, never reserve a second one or block on a stale cap read.
        StampsFunding pending = StampsFunding.builder().id(2L).carrierAccountRefId(9L)
                .amount(new BigDecimal("50")).currency("USD").status(StampsFunding.PENDING)
                .idempotencyKey(KEY).build();
        when(fundings.findByIdempotencyKey(KEY)).thenReturn(Optional.of(pending));
        when(stamps.addFundsSera("tok", new BigDecimal("50"), "usd", KEY, "SANDBOX")).thenReturn(balance("68.40"));

        var result = service.addFunds(9L, new BigDecimal("50"), KEY, "admin");

        assertEquals(StampsFunding.ADDED, result.funding().status());
        assertEquals(new BigDecimal("68.40"), result.balance().available());
        verify(stamps).addFundsSera("tok", new BigDecimal("50"), "usd", KEY, "SANDBOX");
        // No fresh reservation: the pre-charge balance read is skipped on recovery.
        verify(stamps, never()).getAccountBalance(any(), any());
    }

    @Test
    void stampsRefusing_isRecordedAndShownInItsOwnWords() {
        when(stamps.addFundsSera(any(), any(), any(), any(), any())).thenReturn(new BalanceResult("USPS", null, null,
                "usd", "ERROR", "SERA add-funds rejected (HTTP 400): Card declined", null));

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addFunds(9L, new BigDecimal("50"), KEY, "admin"));

        assertEquals(502, ex.getStatusCode().value());
        assertEquals("Stamps.com didn't add the funds: Card declined", ex.getReason());
        // Reserve-then-execute saves twice (PENDING then FAILED) on the same row,
        // so the settled state is asserted with atLeastOnce rather than once.
        verify(fundings, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(f ->
                StampsFunding.FAILED.equals(f.getStatus()) && "Card declined".equals(f.getMessage())));
    }

    @Test
    void anAccountNotYetAuthorized_isToldToAuthorizeFirst() {
        accounts.findById(9L).orElseThrow().setStampsRefreshToken(null);
        var ex = assertThrows(ResponseStatusException.class, () -> service.balance(9L));
        assertEquals(409, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("Authorize with Stamps.com"));
    }

    @Test
    void onlyKnownStampsPagesCanBeOpened() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.pageUrl(9L, "store_password"));
        assertEquals(400, ex.getStatusCode().value());
        // Stamps.com retired store_auto_buy ("Invalid URLType").
        assertThrows(ResponseStatusException.class, () -> service.pageUrl(9L, "store_auto_buy"));
        verifyNoInteractions(stamps);
    }

    @Test
    void aKeyThatIsNotAVersion4Uuid_isReplaced() {
        assertEquals(KEY, StampsPostageService.requestKey(KEY));
        String replaced = StampsPostageService.requestKey("not-a-uuid");
        assertNotEquals("not-a-uuid", replaced);
        assertEquals(4, UUID.fromString(replaced).version());
    }

    @Test
    void aLabelRefusedForLowPostage_isRecognised() {
        assertTrue(StampsConnector.isLowPostage("Insufficient funds in account to print label"));
        assertTrue(StampsConnector.isLowPostage("Your postage balance is too low."));
        assertTrue(!StampsConnector.isLowPostage("Invalid ZIP code"));
    }
}
