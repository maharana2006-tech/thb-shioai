package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.StampsTopupPolicyEntity;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.StampsTopupPolicyRepository;
import com.multiship.backend.service.carriers.CarrierConnector.BalanceResult;
import com.multiship.backend.service.mail.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D5 — poll → check balance → top-up when below threshold → alert on outcome. */
class StampsTopupServiceTest {

    private StampsTopupPolicyRepository policyRepo;
    private CarrierAccountRefRepository accountRepo;
    private StampsConnector connector;
    private NotificationService notifications;
    private StampsTopupService service;

    @BeforeEach
    void setUp() {
        policyRepo = mock(StampsTopupPolicyRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        connector = mock(StampsConnector.class);
        notifications = mock(NotificationService.class);
        service = new StampsTopupService(policyRepo, accountRepo, connector, notifications);
    }

    @Test
    void aboveThreshold_DoesNotTopUpOrAlert() {
        StampsTopupPolicyEntity policy = policy(1L, "50", "100");
        when(accountRepo.findById(policy.getCarrierAccountRefId())).thenReturn(Optional.of(account()));
        when(connector.getAccessToken(any(), any(), any(), any())).thenReturn("live-token");
        when(connector.getAccountBalance(eq("live-token"), any()))
                .thenReturn(balance("OK", "75"));

        service.pollOne(policy);

        verify(connector, never()).addFundsSera(any(), any(), any(), any(), any());
        verify(notifications, never()).send(anyString(), anyString(), any());
    }

    @Test
    void belowThreshold_FiresTopUpAndAlerts() {
        StampsTopupPolicyEntity policy = policy(1L, "50", "100");
        policy.setAlertEmail("ops@example.com");
        when(accountRepo.findById(policy.getCarrierAccountRefId())).thenReturn(Optional.of(account()));
        when(connector.getAccessToken(any(), any(), any(), any())).thenReturn("live-token");
        when(connector.getAccountBalance(eq("live-token"), any()))
                .thenReturn(balance("OK", "20"));
        when(connector.addFundsSera(eq("live-token"), eq(new BigDecimal("100")),
                eq("USD"), anyString(), any()))
                .thenReturn(balance("OK", "120"));

        service.pollOne(policy);

        verify(connector).addFundsSera(eq("live-token"), eq(new BigDecimal("100")),
                eq("USD"), anyString(), any());
        // Two alerts: POLLED_LOW + TOPPED_UP.
        verify(notifications).send(eq("STAMPS.LOW_FUNDS_ALERT"), eq("ops@example.com"),
                argMatchesAction("POLLED_LOW"));
        verify(notifications).send(eq("STAMPS.LOW_FUNDS_ALERT"), eq("ops@example.com"),
                argMatchesAction("TOPPED_UP"));
    }

    @Test
    void topUpFailure_EmitsFailureAlert() {
        StampsTopupPolicyEntity policy = policy(1L, "50", "100");
        policy.setAlertEmail("ops@example.com");
        when(accountRepo.findById(policy.getCarrierAccountRefId())).thenReturn(Optional.of(account()));
        when(connector.getAccessToken(any(), any(), any(), any())).thenReturn("live-token");
        when(connector.getAccountBalance(eq("live-token"), any()))
                .thenReturn(balance("OK", "20"));
        when(connector.addFundsSera(any(), any(), any(), any(), any()))
                .thenReturn(new BalanceResult("STAMPS", null, null, "USD", "ERROR",
                        "SERA add-funds rejected (HTTP 402): card declined", null));

        service.pollOne(policy);

        verify(notifications).send(eq("STAMPS.LOW_FUNDS_ALERT"), eq("ops@example.com"),
                argMatchesAction("TOPUP_FAILED"));
    }

    @Test
    void missingAccount_LogsAndReturnsWithoutCallingConnector() {
        StampsTopupPolicyEntity policy = policy(1L, "50", "100");
        when(accountRepo.findById(policy.getCarrierAccountRefId())).thenReturn(Optional.empty());

        service.pollOne(policy);

        verify(connector, never()).getAccountBalance(any(), any());
        verify(connector, never()).addFundsSera(any(), any(), any(), any(), any());
    }

    private static StampsTopupPolicyEntity policy(Long accountId, String threshold, String topup) {
        return StampsTopupPolicyEntity.builder()
                .id(42L)
                .carrierAccountRefId(accountId)
                .thresholdAmount(new BigDecimal(threshold))
                .topupAmount(new BigDecimal(topup))
                .currency("USD")
                .enabled(true)
                .build();
    }

    private static CarrierAccountRef account() {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setId(1L);
        a.setCarrierCode("STAMPS");
        a.setAccountNumber("ACME-STAMPS-01");
        a.setClientId("cid");
        a.setClientSecret("secret");
        a.setEnvironment("PRODUCTION");
        return a;
    }

    private static BalanceResult balance(String status, String available) {
        return new BalanceResult("STAMPS", new BigDecimal(available), new BigDecimal("500"),
                "USD", status, "ok", "{\"amount_available\":" + available + "}");
    }

    /** Match the `action` field of the vars map passed to NotificationService.send. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> argMatchesAction(String expected) {
        return org.mockito.ArgumentMatchers.argThat(m ->
                m != null && expected.equals(((Map<String, Object>) m).get("action")));
    }
}
