package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.StampsTopupPolicyEntity;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.StampsTopupPolicyRepository;
import com.multiship.backend.service.carriers.CarrierConnector.BalanceResult;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import com.multiship.backend.service.mail.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * D5 — scheduled poller that keeps Stamps SERA prepay wallets funded.
 *
 * <p>Every {@code stamps.topup.poll-interval-ms} (default 30 min):
 * <ol>
 *   <li>Iterate every enabled {@code stamps_topup_policy} row.</li>
 *   <li>Resolve the {@link CarrierAccountRef} it points at.</li>
 *   <li>Open a {@link StampsSeraAuthContext} to push the refresh token.</li>
 *   <li>Mint an access token via {@link StampsConnector#getAccessToken}.</li>
 *   <li>Call {@link StampsConnector#getAccountBalance}.</li>
 *   <li>When the available balance ≤ threshold: alert via
 *       {@code STAMPS.LOW_FUNDS_ALERT} template, then call
 *       {@link StampsConnector#addFundsSera} with an
 *       (accountId, hour-bucket)-scoped idempotency key. Alert again
 *       with the top-up result (success or failure).</li>
 * </ol>
 *
 * <p>Fail-safe: any exception on a single policy is logged and the loop
 * moves to the next row — one bad account doesn't stop the poll.
 *
 * <p>SWSIM tenants are quietly skipped ({@code getAccountBalance} returns
 * NOT_SUPPORTED). The audit's open Q3 asks whether to force-migrate — for
 * now, admins get a log line and no top-up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StampsTopupService {

    static final String ALERT_TEMPLATE = "STAMPS.LOW_FUNDS_ALERT";

    private final StampsTopupPolicyRepository policyRepo;
    private final CarrierAccountRefRepository accountRepo;
    private final StampsConnector stampsConnector;
    private final NotificationService notifications;

    /** Bucket for the idempotency key — one top-up per account per hour max. */
    private static final DateTimeFormatter HOUR_BUCKET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    @Value("${stamps.topup.enabled:true}")
    private boolean pollerEnabled;

    /**
     * Scheduled entry point. {@code fixedDelayString} lets ops re-tune the
     * cadence via a property override without a code change.
     */
    @Scheduled(fixedDelayString = "${stamps.topup.poll-interval-ms:1800000}",
               initialDelayString = "${stamps.topup.poll-initial-delay-ms:120000}")
    public void pollAll() {
        if (!pollerEnabled) return;
        List<StampsTopupPolicyEntity> policies = policyRepo.findAllByEnabledTrue();
        if (policies.isEmpty()) return;
        log.info("Stamps top-up poll: {} enabled policy row(s)", policies.size());
        for (StampsTopupPolicyEntity policy : policies) {
            try {
                pollOne(policy);
            } catch (Exception ex) {
                log.warn("Stamps top-up poll failed for policy id={} account_ref_id={}: {}",
                        policy.getId(), policy.getCarrierAccountRefId(), ex.getMessage());
            }
        }
    }

    /** Package-private so tests can drive one policy directly. */
    void pollOne(StampsTopupPolicyEntity policy) {
        CarrierAccountRef account = accountRepo.findById(policy.getCarrierAccountRefId()).orElse(null);
        if (account == null) {
            log.warn("Stamps top-up policy id={} references missing carrier_account_ref {}",
                    policy.getId(), policy.getCarrierAccountRefId());
            return;
        }

        String accessToken;
        try (AutoCloseable ignored = StampsSeraAuthContext.openFor(account)) {
            accessToken = stampsConnector.getAccessToken(
                    account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Stamps top-up: token acquisition failed for account {}: {}",
                    account.getAccountNumber(), ex.getMessage());
            return;
        }

        BalanceResult balance;
        try (AutoCloseable ignored = StampsSeraAuthContext.openFor(account)) {
            balance = stampsConnector.getAccountBalance(accessToken, account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Stamps top-up: balance call failed for account {}: {}",
                    account.getAccountNumber(), ex.getMessage());
            return;
        }

        recordPoll(policy, balance.amountAvailable());

        if (balance.amountAvailable() == null) {
            log.info("Stamps top-up: balance unavailable for account {} (status={} — {})",
                    account.getAccountNumber(), balance.status(), balance.message());
            return;
        }
        if (balance.amountAvailable().compareTo(policy.getThresholdAmount()) > 0) {
            return;  // above threshold — nothing to do
        }

        alert(policy, account, balance.amountAvailable(), "POLLED_LOW", null, null);

        String idempotencyKey = IdempotencyKeys.forStampsTopup(
                account.getId(),
                LocalDateTime.now(ZoneOffset.UTC).format(HOUR_BUCKET));

        BalanceResult topup;
        try (AutoCloseable ignored = StampsSeraAuthContext.openFor(account)) {
            topup = stampsConnector.addFundsSera(accessToken, policy.getTopupAmount(),
                    policy.getCurrency(), idempotencyKey, account.getEnvironment());
        } catch (Exception ex) {
            alert(policy, account, balance.amountAvailable(), "TOPUP_FAILED",
                    policy.getTopupAmount(), ex.getMessage());
            return;
        }

        if ("OK".equals(topup.status())) {
            recordTopup(policy, topup.amountAvailable());
            alert(policy, account, topup.amountAvailable(), "TOPPED_UP",
                    policy.getTopupAmount(), null);
        } else {
            alert(policy, account, balance.amountAvailable(), "TOPUP_FAILED",
                    policy.getTopupAmount(), topup.message());
        }
    }

    private void recordPoll(StampsTopupPolicyEntity policy, BigDecimal available) {
        policy.setLastPolledAt(LocalDateTime.now(ZoneOffset.UTC));
        policy.setLastBalance(available);
        policyRepo.save(policy);
    }

    private void recordTopup(StampsTopupPolicyEntity policy, BigDecimal availableAfter) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        policy.setLastToppedUpAt(now);
        policy.setLastBalance(availableAfter);
        policyRepo.save(policy);
    }

    private void alert(StampsTopupPolicyEntity policy, CarrierAccountRef account,
                       BigDecimal available, String action, BigDecimal amount, String errorMessage) {
        if (policy.getAlertEmail() == null || policy.getAlertEmail().isBlank()) {
            log.info("Stamps top-up alert (no alert_email set): account={} action={} available={} amount={} err={}",
                    account.getAccountNumber(), action, available, amount, errorMessage);
            return;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("accountNumber", account.getAccountNumber());
        vars.put("available", available);
        vars.put("threshold", policy.getThresholdAmount());
        vars.put("currency", policy.getCurrency());
        vars.put("action", action);
        if (amount != null) vars.put("amount", amount);
        if (errorMessage != null) vars.put("errorMessage", errorMessage);
        try {
            notifications.send(ALERT_TEMPLATE, policy.getAlertEmail(), vars);
        } catch (RuntimeException ex) {
            log.warn("Stamps top-up alert send failed for account {}: {}",
                    account.getAccountNumber(), ex.getMessage());
        }
    }
}
